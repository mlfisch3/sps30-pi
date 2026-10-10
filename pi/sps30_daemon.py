#!/usr/bin/env python3
"""Raspberry Pi acquisition daemon for the Sensirion SPS30.

This is the always-on server half of the dashboard. It owns the serial port,
runs a ``checking`` <-> ``measuring`` state machine, writes the authoritative
CSV, and broadcasts readings/state to any number of TCP clients (the Textual
GUI) over the JSONL protocol defined in :mod:`wire`.

Design (see ARCHITECTURE.md "Hard requirements"):

* **One acquisition thread** owns the ``sps30.SPS30`` port and is the *only*
  code that ever touches the serial line. It probes for the sensor, starts
  measurement, reads at ``--interval`` and writes every reading to a fresh CSV.
  If reads start failing it closes the port, returns to ``STATE_CHECKING`` and
  keeps retrying so it auto-recovers when the sensor comes back.
* **Control commands** (pause/resume/clean/restart/raw) are enqueued on a
  ``queue.Queue`` and executed by the acquisition thread *between* reads - never
  concurrently with a read and never on another thread.
* **Clients never block acquisition.** Each connected client is fed by a bounded
  queue; on overflow the oldest message is dropped. A slow, dead or erroring
  client cannot pause the sensor loop or disturb other clients.

The core FSM is written so it can be driven one ``step()`` at a time with an
injected sensor factory and sleep function, which is how the hardware-free tests
exercise it.
"""
from __future__ import annotations

import argparse
import base64
import csv
import json
import logging
import os
import queue
import socket
import subprocess
import tempfile
import threading
import time
import zipfile
from collections import deque
from dataclasses import astuple, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Iterable, Optional

import serial  # pyserial

import wire
from sps30 import SPS30, SPS30Error, Measurement

log = logging.getLogger("sps30d")

# --- tunables ---------------------------------------------------------------
CLIENT_QUEUE_MAX = 1000     # per-client outbound backlog before drop-oldest
FSYNC_EVERY = 30            # fsync the CSV every N rows (flush is every row)
FILE_CHUNK_ROWS = 500       # rows per MSG_FILE_DATA chunk for CMD_GET_FILE
DEFAULT_POLL_INTERVAL = 2.0  # seconds between sensor probes while checking
# After a (re)start or a fan clean the SPS30's reading spikes and takes several
# seconds to settle. Flag readings for this long (in wall-clock seconds, converted
# to a row count from --interval) so clients can exclude the unreliable spike.
SETTLE_SECONDS = 20.0
# The SPS30's own auto-clean interval is one week of *powered* run time, and its
# counter resets on every power-down (datasheet 4.2). Duty cycling therefore has
# to accumulate run time itself and force a clean at the same cadence.
CLEAN_AFTER_SECONDS = 604800.0  # 1 week, matching the sensor's own default
CLEAN_DURATION_SECONDS = 12.0   # fan spins at max for ~10s; allow a little slack
# Sensirion "Low-Power Operation of the SPS30", app note v1 (Aug 2020): the
# sensor draws 45-65 mA measuring (55 mA average) and 38 uA in Sleep-Mode.
MEASURE_MA = 55.0
SLEEP_MA = 0.038
# The SPS30 publishes exactly one new measurement per second.
SAMPLE_PERIOD = 1.0
POWER_POLL_SECONDS = 60.0   # how often host power telemetry is re-read
# Consecutive read timeouts tolerated before declaring the sensor missing. The
# SPS30 answers every second, so this is a few seconds of silence - long enough
# to ride out a glitch or a brief brown-out, short enough that a genuinely
# unplugged sensor is noticed promptly.
READ_FAILURES_BEFORE_LOSS = 5


SensorFactory = Callable[[str, float], SPS30]


def _default_factory(port: str, timeout: float) -> SPS30:
    return SPS30(port=port, timeout=timeout)


@dataclass
class Command:
    """A control command enqueued for the acquisition thread to run."""

    name: str
    params: dict
    client: Optional["ClientHandler"] = None


# ---------------------------------------------------------------------------
# CSV file inspection helpers (read-only; safe to call off the acq thread)
# ---------------------------------------------------------------------------
def scan_files(csv_dir: Path) -> list[dict]:
    """Return metadata for every ``SPS30-*.csv`` in *csv_dir* (newest first)."""
    files = []
    for path in csv_dir.glob("SPS30-*.csv"):
        try:
            st = path.stat()
            rows, start = _summarize_file(path)
        except OSError:
            continue
        files.append(
            {"name": path.name, "rows": rows, "bytes": st.st_size, "start": start}
        )
    files.sort(key=lambda f: f["name"], reverse=True)
    return files


def _summarize_file(path: Path) -> tuple[int, str]:
    """Return (data_row_count, start_timestamp_iso) for one CSV file.

    The row count is a cheap buffered newline scan (total lines minus the
    header) rather than a full CSV parse - listing must stay O(bytes read) and
    not materialise every row of every file. The start timestamp is recovered by
    parsing only the first data line. The CSV schema (see ``wire.CSV_COLUMNS``)
    has no embedded newlines, so a raw newline count matches the row count.
    """
    lines = 0
    last = b""
    with open(path, "rb") as fh:
        while True:
            buf = fh.read(1 << 20)
            if not buf:
                break
            lines += buf.count(b"\n")
            last = buf[-1:]
    if last not in (b"", b"\n"):
        lines += 1  # a final line with no trailing newline still counts
    rows = max(0, lines - 1)  # subtract the header line

    start = ""
    with open(path, "r", newline="") as fh:
        reader = csv.reader(fh)
        try:
            next(reader)  # header
            first = next(reader)  # first data row
        except StopIteration:
            return 0, ""
        if len(first) > 1:
            start = first[1]  # timestamp column
    return rows, start


def read_file_rows(
    path: Path, since_index: Optional[int] = None
) -> tuple[list[str], Iterable[list[str]]]:
    """Yield ``(columns, rows)`` from a CSV, optionally only rows past an index.

    ``since_index`` filters to rows whose integer index is strictly greater than
    the given value - exactly what a reconnecting client needs to backfill the
    gap after its last locally stored row.
    """
    with open(path, "r", newline="") as fh:
        reader = csv.reader(fh)
        columns = next(reader)
        selected = []
        for row in reader:
            if not row:
                continue
            if since_index is not None:
                try:
                    if int(row[0]) <= since_index:
                        continue
                except ValueError:
                    continue
            selected.append(row)
    return columns, selected


def _chunks(seq: list, size: int) -> Iterable[list]:
    if not seq:
        yield []
        return
    for i in range(0, len(seq), size):
        yield seq[i : i + size]


# ---------------------------------------------------------------------------
# Per-client connection handler
# ---------------------------------------------------------------------------
class ClientHandler:
    """Serves one TCP client: a reader thread for commands, a writer thread fed
    by a priority queue.

    Live broadcasts (readings/state) are *lossy*: when the backlog exceeds
    ``CLIENT_QUEUE_MAX`` the oldest lossy message is dropped so a slow client can
    never stall acquisition. File-transfer chunks and command responses are
    *lossless*: losing one would corrupt a file (a hole the client's
    contiguous-watermark backfill never re-requests) or wedge the transfer
    (a lost final ``done`` chunk leaves the client's request queue stuck
    forever), so they are never dropped and the queue is allowed to exceed the
    soft cap while only lossless messages remain.
    """

    _SHUTDOWN = object()

    _next_id = 0
    _id_lock = threading.Lock()

    def __init__(self, sock: socket.socket, addr, daemon: "Daemon"):
        self.sock = sock
        self.addr = addr
        self.daemon = daemon
        with ClientHandler._id_lock:
            ClientHandler._next_id += 1
            self.client_id = ClientHandler._next_id
        # Until the client identifies itself, show where it came from - better
        # than "unknown" when working out who is holding the control lock.
        self.name = f"{addr[0]}" if addr else "client"
        # Each entry is (message_or_SHUTDOWN, lossless: bool).
        self._outq: "deque[tuple[Any, bool]]" = deque()
        self._cond = threading.Condition()
        self._alive = True
        self._closed = False
        self._lock = threading.Lock()
        self._reader = threading.Thread(
            target=self._read_loop, name=f"client-r-{addr}", daemon=True
        )
        self._writer = threading.Thread(
            target=self._write_loop, name=f"client-w-{addr}", daemon=True
        )

    def start(self) -> None:
        self._writer.start()
        self._reader.start()

    # --- outbound -----------------------------------------------------------
    def send(self, message: dict, lossless: bool = False) -> None:
        """Enqueue a message for delivery. Never blocks the caller. Lossy
        messages past the cap evict the oldest lossy message; lossless messages
        are never dropped (see the class docstring)."""
        if not self._alive:
            return
        with self._cond:
            self._outq.append((message, lossless))
            if len(self._outq) > CLIENT_QUEUE_MAX:
                self._evict_one_lossy_locked()
            self._cond.notify()

    def _evict_one_lossy_locked(self) -> None:
        """Drop the oldest droppable (lossy) message. If the backlog is entirely
        lossless, let it grow rather than corrupt a file transfer."""
        for i, (_msg, lossless) in enumerate(self._outq):
            if not lossless:
                del self._outq[i]
                return

    def _write_loop(self) -> None:
        while True:
            with self._cond:
                while not self._outq:
                    self._cond.wait()
                msg, _lossless = self._outq.popleft()
            if msg is self._SHUTDOWN:
                break
            try:
                self.sock.sendall(wire.encode(msg))
            except OSError:
                break
        self.close()

    # --- inbound ------------------------------------------------------------
    def _read_loop(self) -> None:
        buffer = bytearray()
        try:
            while self._alive:
                try:
                    data = self.sock.recv(4096)
                except OSError:
                    break
                if not data:
                    break  # peer closed
                buffer.extend(data)
                for message in wire.iter_messages(buffer):
                    self._dispatch(message)
        finally:
            self.close()

    def _dispatch(self, message: dict) -> None:
        cmd = message.get("cmd")

        if cmd == wire.CMD_IDENTIFY:
            name = str(message.get("name", "")).strip()
            if name:
                self.name = name[:40]
                self.daemon.broadcast_clients()
            return
        if cmd == wire.CMD_CLAIM:
            self.daemon.claim_control(self)
            return
        if cmd == wire.CMD_RELEASE:
            self.daemon.release_control(self)
            return

        # Anything that changes what the sensor is doing needs the control lock.
        # Viewing never does, so a reader is never forced to take control away
        # from whoever is currently driving.
        if cmd in wire.MUTATING_COMMANDS and not self.daemon.may_control(self):
            holder = self.daemon.control_holder_name()
            self.send(
                {
                    "type": wire.MSG_ERROR,
                    "message": f"{holder} has control of the sensor",
                    "needs_control": True,
                },
                lossless=True,
            )
            return

        if cmd in (
            wire.CMD_PAUSE,
            wire.CMD_RESUME,
            wire.CMD_CLEAN,
            wire.CMD_RESTART,
            wire.CMD_RAW,
            wire.CMD_SET_PROFILE,
            wire.CMD_SHUTDOWN,
        ):
            # Anything that touches the serial port goes to the acquisition
            # thread; it is the single owner of the port.
            self.daemon.enqueue_command(cmd, message, self)
        elif cmd == wire.CMD_LIST_FILES:
            self._send_file_list()
        elif cmd == wire.CMD_GET_FILE:
            self._send_file_data(message)
        elif cmd == wire.CMD_BACKUP:
            self._send_backup()
        else:
            self.send({"type": wire.MSG_ERROR, "message": f"unknown cmd {cmd!r}"},
                      lossless=True)

    def _send_backup(self) -> None:
        """Stream every recorded session as one zip archive.

        Runs on this client's own reader thread, never the acquisition thread, so
        a multi-megabyte transfer cannot delay a reading. Deliberately requires no
        control claim either: taking a copy of the data is not a change to the
        sensor, and making people queue for it would be perverse.

        The archive is compressed rather than sent raw, which is the opposite of
        the intuition that compression costs the gadget power. Radio time
        dominates that budget by an order of magnitude - transmitting is hundreds
        of milliamps against tens for the CPU - and this CSV deflates by roughly
        20x, so compressing trades a little computation for a great deal less
        time with the radio lit. That reasoning matters more, not less, on the
        battery-powered ESP32 this is headed for.
        """
        files = sorted(self.daemon.csv_dir.glob("SPS30-*.csv"))
        if not files:
            self.send({"type": wire.MSG_ERROR, "message": "no recorded sessions to back up"},
                      lossless=True)
            return

        stamp = wire.now_eastern().strftime("%Y%m%d-%H%M%S")
        name = f"sps30-sessions-{stamp}.zip"
        tmp = None
        try:
            with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as fh:
                tmp = Path(fh.name)
            rows = 0
            with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
                for path in files:
                    try:
                        zf.write(path, arcname=path.name)
                        rows += max(0, _summarize_file(path)[0])
                    except OSError as exc:
                        log.warning("skipping %s in backup: %s", path.name, exc)

            total = tmp.stat().st_size
            log.info(
                "backup: %d sessions, %d rows, %.1f MB compressed -> %s",
                len(files), rows, total / 1e6, self.addr,
            )
            self.send(
                {
                    "type": wire.MSG_BACKUP_META,
                    "name": name,
                    "bytes": total,
                    "sessions": len(files),
                    "rows": rows,
                },
                lossless=True,
            )

            seq = 0
            with open(tmp, "rb") as fh:
                while True:
                    block = fh.read(wire.BACKUP_CHUNK_BYTES)
                    if not block:
                        break
                    self.send(
                        {
                            "type": wire.MSG_BACKUP_CHUNK,
                            "seq": seq,
                            "data": base64.b64encode(block).decode("ascii"),
                            "done": False,
                        },
                        lossless=True,
                    )
                    seq += 1
            # A final empty chunk marks the end, so the client never has to infer
            # completion from a byte count that a truncated transfer would match.
            self.send(
                {"type": wire.MSG_BACKUP_CHUNK, "seq": seq, "data": "", "done": True},
                lossless=True,
            )
        except OSError as exc:
            log.exception("backup failed")
            self.send({"type": wire.MSG_ERROR, "message": f"backup failed: {exc}"}, lossless=True)
        finally:
            if tmp is not None:
                try:
                    tmp.unlink()
                except OSError:
                    pass

    def _send_file_list(self) -> None:
        files = scan_files(self.daemon.csv_dir)
        self.send({"type": wire.MSG_FILES, "files": files}, lossless=True)

    def _send_file_data(self, message: dict) -> None:
        name = message.get("name", "")
        since = message.get("since_index")
        since_index = int(since) if since not in (None, "") else None
        path = self.daemon.csv_dir / name
        # Guard against path traversal; only serve files in the data dir.
        if os.path.basename(name) != name or not path.is_file():
            self.send({"type": wire.MSG_ERROR, "message": f"no such file {name!r}"},
                      lossless=True)
            return
        try:
            columns, rows = read_file_rows(path, since_index)
        except OSError as exc:
            self.send({"type": wire.MSG_ERROR, "message": f"read failed: {exc}"},
                      lossless=True)
            return
        chunks = list(_chunks(rows, FILE_CHUNK_ROWS))
        for i, chunk in enumerate(chunks):
            self.send(
                {
                    "type": wire.MSG_FILE_DATA,
                    "name": name,
                    "columns": columns,
                    "rows": chunk,
                    "done": i == len(chunks) - 1,
                },
                lossless=True,
            )

    # --- lifecycle ----------------------------------------------------------
    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            self._alive = False
        self.daemon.remove_client(self)
        with self._cond:
            self._outq.append((self._SHUTDOWN, True))
            self._cond.notify()
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


# ---------------------------------------------------------------------------
# The daemon: acquisition FSM + broadcast fan-out + TCP server
# ---------------------------------------------------------------------------
def read_power_status() -> dict:
    """Best-effort power telemetry for the host this daemon runs on.

    The Pi is mains-powered and has no battery to report, so the useful signal
    here is the firmware's under-voltage flag - this project lost hours to a
    weak supply once already (the sensor simply never booted its comms). A
    battery-powered ESP32-C6 host will fill in the remaining fields against the
    same message shape.
    """
    status = {
        "source": "mains",
        "charging": False,
        "percent": None,
        "voltage_v": None,
        "runtime_hours": None,
        "method": "none",
    }
    try:
        out = subprocess.run(
            ["vcgencmd", "get_throttled"],
            capture_output=True, text=True, timeout=2, check=False,
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return status
    # "throttled=0x50005": bit 0 = under-voltage now, bit 16 = since boot.
    if "=" in out:
        try:
            bits = int(out.split("=", 1)[1], 16)
        except ValueError:
            return status
        status["undervoltage"] = bool(bits & 0x1)
        status["undervoltage_since_boot"] = bool(bits & 0x10000)
    return status


def _poweroff_host() -> None:
    """Power the host off. Split out so tests can substitute a no-op."""
    subprocess.run(["sudo", "-n", "systemctl", "poweroff"], check=False, timeout=10)


class Daemon:
    def __init__(
        self,
        port: str = "/dev/serial0",
        tcp_port: int = wire.DEFAULT_PORT,
        host: str = wire.DEFAULT_HOST,
        csv_dir: Optional[Path] = None,
        interval: float = 1.0,
        timeout: float = 2.0,
        poll_interval: float = DEFAULT_POLL_INTERVAL,
        fsync_every: int = FSYNC_EVERY,
        sensor_factory: SensorFactory = _default_factory,
        sleep: Optional[Callable[[float], None]] = None,
        profile: Optional[dict] = None,
        state_file: Optional[Path] = None,
        power_reader: Optional[Callable[[], dict]] = None,
        shutdown_hook: Optional[Callable[[], None]] = None,
    ):
        self.port = port
        self.tcp_port = tcp_port
        self.host = host
        self.csv_dir = Path(csv_dir) if csv_dir else Path.home() / "sps30-data"
        # `interval` stays the continuous-mode read period; an explicit profile
        # wins, otherwise the CLI's --interval seeds the default profile.
        self.profile = wire.normalize_profile(profile or {"interval": interval})
        self.timeout = timeout
        self.poll_interval = poll_interval
        # fsync the CSV every N rows (flush is every row). >=1; larger trades
        # power-loss durability for less I/O. See ARCHITECTURE.md requirement 3.
        self.fsync_every = max(1, int(fsync_every))
        self._sensor_factory = sensor_factory

        self._wake = threading.Event()
        self._sleep = sleep or self._interruptible_sleep

        # Acquisition state (owned by the acquisition thread).
        self._sensor: Optional[SPS30] = None
        self._state = wire.STATE_CHECKING
        self._detail = "starting up"
        # How long the current phase should last, for client-side countdowns.
        self._phase_seconds: Optional[float] = None
        self._info = {"product": "", "serial": "", "firmware": ""}
        self._csv_file = None
        self._writer: Optional[csv.DictWriter] = None
        self._csv_name = ""
        self._index = 0
        self._rows_since_sync = 0
        # Spike-settling flag: while >0, readings carry _settle_event and are
        # counted down. _start_event labels the next measuring session's spike.
        self._settle_rows = 0
        self._settle_event = ""
        self._start_event = "startup"
        # True while the sensor is parked in Sleep-Mode (duty cycling only).
        self._asleep = False
        # Consecutive failed reads; see READ_FAILURES_BEFORE_LOSS.
        self._read_failures = 0
        # True between a dropout and its recovery, so the CSV is continued
        # rather than replaced.
        self._recovering = False
        # Bumped on every profile change. A duty cycle spans minutes, so a
        # command can land mid-cycle; the cycle compares generations and bails
        # rather than carrying on and (for instance) sleeping a sensor the user
        # has just switched back to continuous.
        self._profile_gen = 0

        # Cumulative seconds the fan has actually run since the last clean.
        # The SPS30's own auto-clean counter resets whenever the sensor powers
        # down (datasheet 4.2), so under duty cycling it would essentially never
        # fire - we have to track run time ourselves and force a clean. Persisted
        # so a daemon restart does not lose the accrual.
        self.state_file = Path(state_file) if state_file else self.csv_dir / "daemon-state.json"
        self._run_seconds = 0.0
        self._load_state()

        self._power_reader = power_reader or read_power_status
        self._shutdown_hook = shutdown_hook or _poweroff_host
        self._power: dict = {}
        self._power_checked_at = 0.0

        # Command queue (client threads -> acquisition thread).
        self._cmdq: "queue.Queue[Command]" = queue.Queue()

        # Client registry (broadcast fan-out).
        self._clients: set[ClientHandler] = set()
        self._clients_lock = threading.Lock()

        # Snapshot lock guards the fields read by hello_message().
        self._snap_lock = threading.Lock()

        # Control arbitration: at most one client may change the sensor's state.
        self._control_lock = threading.Lock()
        self._control_holder: Optional[ClientHandler] = None
        self._control_expires = 0.0
        self._last_announced_holder: Optional[ClientHandler] = None

        self._stop = threading.Event()
        self._server_sock: Optional[socket.socket] = None
        self._acq_thread: Optional[threading.Thread] = None
        self._accept_thread: Optional[threading.Thread] = None

    # --- interruptible sleep -----------------------------------------------
    def _interruptible_sleep(self, seconds: float) -> None:
        # Woken early when a command arrives so control feels responsive.
        self._wake.wait(seconds)
        self._wake.clear()

    # --- broadcast fan-out --------------------------------------------------
    def broadcast(self, message: dict) -> None:
        with self._clients_lock:
            clients = list(self._clients)
        for client in clients:
            client.send(message)

    def add_client(self, client: ClientHandler) -> None:
        with self._clients_lock:
            self._clients.add(client)
        self.broadcast_clients()

    def remove_client(self, client: ClientHandler) -> None:
        with self._clients_lock:
            self._clients.discard(client)
        # A client that disconnects holding the lock must not strand the others.
        with self._control_lock:
            if self._control_holder is client:
                self._control_holder = None
                self._control_expires = 0.0
                released = True
            else:
                released = False
        if released:
            log.info("control released: holder disconnected")
            self.broadcast(self.control_message())
        self.broadcast_clients()

    def client_count(self) -> int:
        with self._clients_lock:
            return len(self._clients)

    # --- control arbitration -------------------------------------------------
    #
    # Everyone may watch; only the lock holder may change what the sensor does.
    # The lock auto-expires so a phone that goes out of range, or a user who
    # simply walks off, cannot leave the sensor permanently uncontrollable - and
    # any mutating command refreshes it, so someone actively working is not
    # interrupted mid-task.

    def _control_expired(self) -> bool:
        return self._control_holder is not None and time.monotonic() >= self._control_expires

    def may_control(self, client: ClientHandler) -> bool:
        """True if [client] may issue mutating commands, taking the lock if free."""
        with self._control_lock:
            if self._control_expired():
                self._control_holder = None
            holder = self._control_holder
            if holder is None:
                # Uncontested: the first person to act simply gets it, so a
                # single user never has to know the mechanism exists.
                self._control_holder = client
                self._control_expires = time.monotonic() + wire.CONTROL_TTL_SECONDS
                granted = True
            elif holder is client:
                self._control_expires = time.monotonic() + wire.CONTROL_TTL_SECONDS
                granted = True
            else:
                granted = False
        if granted and self._announce_control_change(client):
            self.broadcast(self.control_message())
        return granted

    def _announce_control_change(self, client: ClientHandler) -> bool:
        changed = self._last_announced_holder is not client
        self._last_announced_holder = client
        return changed

    def claim_control(self, client: ClientHandler) -> None:
        """Take the lock, even from a current holder.

        Taking over is deliberately allowed rather than requiring the holder to
        release: the common case is that the previous holder has simply walked
        away, and a lock only its owner can free is a lock that strands people.
        """
        with self._control_lock:
            self._control_holder = client
            self._control_expires = time.monotonic() + wire.CONTROL_TTL_SECONDS
        self._last_announced_holder = client
        log.info("control claimed by %s", client.name)
        self.broadcast(self.control_message())

    def release_control(self, client: ClientHandler) -> None:
        with self._control_lock:
            if self._control_holder is not client:
                return
            self._control_holder = None
            self._control_expires = 0.0
        self._last_announced_holder = None
        log.info("control released by %s", client.name)
        self.broadcast(self.control_message())

    def control_holder_name(self) -> str:
        with self._control_lock:
            if self._control_expired() or self._control_holder is None:
                return "nobody"
            return self._control_holder.name

    def control_message(self) -> dict:
        with self._control_lock:
            expired = self._control_expired()
            holder = None if expired else self._control_holder
            remaining = 0.0 if holder is None else max(0.0, self._control_expires - time.monotonic())
        return {
            "type": wire.MSG_CONTROL,
            "holder": getattr(holder, "client_id", None),
            "holder_name": getattr(holder, "name", None),
            "expires_in": round(remaining, 1),
        }

    def clients_message(self) -> dict:
        with self._clients_lock:
            roster = [{"id": c.client_id, "name": c.name} for c in self._clients]
        roster.sort(key=lambda c: c["id"])
        return {"type": wire.MSG_CLIENTS, "clients": roster, "max": wire.MAX_CLIENTS}

    def broadcast_clients(self) -> None:
        self.broadcast(self.clients_message())

    def hello_message(self) -> dict:
        with self._snap_lock:
            return {
                "type": wire.MSG_HELLO,
                "product": self._info["product"],
                "serial": self._info["serial"],
                "firmware": self._info["firmware"],
                "state": self._state,
                "detail": self._detail,
                "seconds": self._phase_seconds,
                "csv": self._csv_name,
                "index": wire.format_index(self._index),
                "profile": dict(self.profile),
                "power": self.power_message()["power"],
                # So a client can grey out low-power mode rather than offering
                # something this sensor cannot do.
                "supports_sleep": wire.firmware_supports_sleep(self._info["firmware"]),
                "client_id": None,  # filled in per-connection by the handler
                "max_clients": wire.MAX_CLIENTS,
            }

    def profile_message(self) -> dict:
        return {"type": wire.MSG_PROFILE, "profile": dict(self.profile)}

    def power_message(self) -> dict:
        """Current host power state, with the modelled sensor draw folded in."""
        try:
            power = dict(self._power_reader())
        except Exception:  # telemetry must never take acquisition down
            log.debug("power read failed", exc_info=True)
            power = {"source": "unknown", "method": "none"}
        power["average_ma"] = round(self.estimated_average_ma(), 3)
        power["clean_due_in_hours"] = round(
            max(0.0, CLEAN_AFTER_SECONDS - self._run_seconds) / 3600.0, 1
        )
        return {"type": wire.MSG_POWER, "power": power}

    def _maybe_broadcast_power(self) -> None:
        """Push power telemetry when it changes, or on a slow keep-alive.

        Polling `vcgencmd` is a subprocess spawn, so it is deliberately not done
        per reading; and an unchanged payload is not worth waking every client.
        """
        now = time.monotonic()
        if now - self._power_checked_at < POWER_POLL_SECONDS:
            return
        self._power_checked_at = now
        message = self.power_message()
        if message["power"] != self._power:
            self._power = message["power"]
            self.broadcast(message)
            self._save_state()

    def estimated_average_ma(self) -> float:
        """Modelled average sensor current for the active profile.

        Figures from Sensirion's low-power app note: ~55 mA while measuring,
        ~38 uA asleep. There is no current sense on this hardware, but the duty
        cycle is known exactly, so modelling it beats guessing - and it is what
        a battery-runtime estimate on the ESP32-C6 will be built from.
        """
        if not self.duty_cycling:
            return MEASURE_MA
        cycle = float(self.profile["cycle_s"])
        awake = float(self.profile["warmup_s"]) + float(self.profile["average_s"])
        awake = min(awake, cycle)
        asleep = max(0.0, cycle - awake)
        return (MEASURE_MA * awake + SLEEP_MA * asleep) / cycle

    # --- command entry point (called from client threads) -------------------
    def enqueue_command(
        self, name: str, params: dict, client: Optional[ClientHandler] = None
    ) -> None:
        self._cmdq.put(Command(name, params, client))
        self._wake.set()  # break out of the inter-read sleep promptly

    # ==================================================================
    # Acquisition FSM
    # ==================================================================
    def run(self) -> None:
        """Acquisition loop. Runs until :meth:`stop` is called."""
        log.info(
            "acquisition starting: port=%s interval=%.3fs csv-dir=%s",
            self.port,
            self.interval,
            self.csv_dir,
        )
        self.csv_dir.mkdir(parents=True, exist_ok=True)
        while not self._stop.is_set():
            try:
                self.step()
                self._maybe_broadcast_power()
            except Exception:  # never let the acquisition thread die
                log.exception("unexpected error in acquisition step")
                self._handle_sensor_loss("internal error")
                self._sleep(self.poll_interval)
        self._teardown_sensor("shutting down")
        log.info("acquisition stopped")

    @property
    def interval(self) -> float:
        """Continuous-mode read period; a view onto the active profile."""
        return float(self.profile["interval"])

    @interval.setter
    def interval(self, seconds: float) -> None:
        self.profile = wire.normalize_profile({**self.profile, "interval": seconds})

    @property
    def duty_cycling(self) -> bool:
        return self.profile["mode"] == wire.MODE_DUTY_CYCLE

    def step(self) -> None:
        """Run exactly one FSM iteration (drivable directly in tests)."""
        self._drain_commands()
        if self._stop.is_set():
            return
        if self._state == wire.STATE_CHECKING:
            self._try_start()
        elif self._state == wire.STATE_MEASURING:
            if self.duty_cycling:
                self._duty_cycle_once()
            else:
                self._measure_once()
                self._accrue_run_time(self.interval)
                self._sleep(self.interval)
        elif self._state in (wire.STATE_SLEEPING, wire.STATE_WARMUP):
            # Duty-cycle states. If the profile is no longer duty cycling (the
            # user switched back mid-cycle) these would otherwise be a dead end,
            # since only a duty cycle leaves them - so recover explicitly.
            if self.duty_cycling:
                self._set_state(wire.STATE_MEASURING, "next cycle")
            else:
                self._start_event = "profile"
                self._begin_measuring("profile: continuous")
        else:  # PAUSED / CLEANING: idle, waiting for commands
            self._sleep(self.poll_interval)

    # --- duty cycling (Sensirion low-power recommendation) -------------------
    def _duty_cycle_once(self) -> None:
        """One wake -> warm up -> average -> sleep cycle.

        Follows the pseudo-code in Sensirion's "Low-Power Operation of the
        SPS30": wake, start measuring, discard `warmup_s` while the fan spins up
        and the PM statistics accumulate, average `average_s` of readings into a
        single recorded point, then stop and sleep out the rest of `cycle_s`.
        The whole cycle is interruptible - `_sleep` returns early when a command
        arrives - so pause/profile changes stay responsive despite the long waits.
        """
        cycle = float(self.profile["cycle_s"])
        warmup = float(self.profile["warmup_s"])
        average = float(self.profile["average_s"])
        started = time.monotonic()
        generation = self._profile_gen

        if not self._wake_and_start():
            return
        self._set_state(wire.STATE_WARMUP, "warming up", warmup)
        if not self._sleep_checked(warmup, generation):
            return self._abandon_cycle(generation)
        self._accrue_run_time(warmup)

        self._set_state(wire.STATE_MEASURING, "averaging", average)
        samples = self._collect_average(average)
        self._accrue_run_time(average)
        if self._profile_gen != generation:
            return self._abandon_cycle(generation)
        if samples is not None:
            self._write_reading(samples)

        self._park_sensor()
        if self._maybe_auto_clean():
            return
        elapsed = time.monotonic() - started
        remaining = max(0.0, cycle - elapsed)
        self._set_state(wire.STATE_SLEEPING, "sleeping", remaining)
        self._sleep_checked(remaining, generation)

    def _wake_and_start(self) -> bool:
        """Bring the sensor from Sleep-Mode to measuring. False if it is gone."""
        try:
            if self._asleep:
                self._sensor.wake()
                self._asleep = False
            # The sensor rejects Start with 0x43 "not allowed in current state"
            # if it is already measuring - which it is on the first cycle after a
            # profile switch, because _begin_measuring has just started it. Stop
            # first to reach a known idle state; ignore the error when idle.
            try:
                self._sensor.stop_measurement()
            except (SPS30Error, serial.SerialException, OSError):
                pass
            self._sensor.start_measurement()
        except (SPS30Error, serial.SerialException, OSError) as exc:
            self._handle_sensor_loss(f"duty-cycle start failed: {exc}")
            return False
        return True

    def _park_sensor(self) -> None:
        """Stop measuring and drop into Sleep-Mode (< 50 uA) until the next cycle."""
        if self._sensor is None:
            return
        try:
            self._sensor.stop_measurement()
            self._sensor.sleep()
            self._asleep = True
        except (SPS30Error, serial.SerialException, OSError) as exc:
            # Not fatal: the next cycle re-probes. Worst case we burn idle power.
            log.warning("could not park sensor for sleep: %s", exc)
            self._asleep = False

    def _collect_average(self, seconds: float):
        """Average one reading per second over [seconds] into one Measurement.

        Averaging (rather than taking a single sample) is what the app note asks
        for: after warm-up it recommends folding another 30 s of readings into
        one value for repeatability. Its pseudo-code counts samples rather than
        watching the clock, and so do we - the SPS30 produces exactly one new
        value per second, and counting keeps the loop honest when the sleep
        function is substituted.
        """
        samples = max(1, round(seconds / SAMPLE_PERIOD))
        totals: list[float] = []
        count = 0
        for _ in range(samples):
            if self._stop.is_set():
                break
            try:
                m = self._sensor.read_measurement()
            except (SPS30Error, serial.SerialException, OSError) as exc:
                self._handle_sensor_loss(f"duty-cycle read failed: {exc}")
                return None
            if m is not None:
                values = list(astuple(m))
                if not totals:
                    totals = [0.0] * len(values)
                totals = [t + v for t, v in zip(totals, values)]
                count += 1
            self._sleep(SAMPLE_PERIOD)
        if count == 0:
            log.warning("duty cycle produced no readings")
            return None
        return Measurement(*[t / count for t in totals])

    def _sleep_checked(self, seconds: float, generation: int) -> bool:
        """Sleep, then report whether this cycle should still continue.

        A command (pause, profile change, shutdown) can land mid-cycle, so the
        caller has to re-check rather than blindly carry on.
        """
        self._sleep(seconds)
        self._drain_commands()
        return (
            not self._stop.is_set()
            and self._sensor is not None
            and self._profile_gen == generation
            and self._state in (wire.STATE_MEASURING, wire.STATE_WARMUP, wire.STATE_SLEEPING)
        )

    def _abandon_cycle(self, generation: int) -> None:
        """Give up on the current cycle. Only park the sensor if we are still
        duty cycling - if the profile just switched to continuous, the new
        session is already running and must not be put to sleep."""
        if self._profile_gen == generation and self.duty_cycling:
            self._park_sensor()

    # --- fan-clean accounting ------------------------------------------------
    def _accrue_run_time(self, seconds: float) -> None:
        self._run_seconds += max(0.0, seconds)

    def _maybe_auto_clean(self) -> bool:
        """Run a fan clean if the sensor has measured for a week in total.

        The SPS30 does this itself when left running, but its counter resets on
        every power-down, so duty cycling has to drive it explicitly or the fan
        never gets cleaned. Returns True if a clean ran.
        """
        if self._run_seconds < CLEAN_AFTER_SECONDS or self._sensor is None:
            return False
        log.info("auto-clean: %.0fh of measurement since last clean", self._run_seconds / 3600)
        try:
            if self._asleep:
                self._sensor.wake()
                self._asleep = False
            self._sensor.start_measurement()
            self._set_state(wire.STATE_CLEANING, "scheduled fan self-clean")
            self._sensor.fan_clean()
            self._sleep(CLEAN_DURATION_SECONDS)
            self._sensor.stop_measurement()
            self._sensor.sleep()
            self._asleep = True
        except (SPS30Error, serial.SerialException, OSError) as exc:
            self._handle_sensor_loss(f"scheduled clean failed: {exc}")
            return True
        self._run_seconds = 0.0
        self._save_state()
        self._set_state(wire.STATE_SLEEPING, "clean complete")
        return True

    # --- persisted counters --------------------------------------------------
    def _load_state(self) -> None:
        try:
            data = json.loads(self.state_file.read_text())
            self._run_seconds = float(data.get("run_seconds", 0.0))
        except (OSError, ValueError, TypeError):
            self._run_seconds = 0.0

    def _save_state(self) -> None:
        try:
            self.state_file.parent.mkdir(parents=True, exist_ok=True)
            self.state_file.write_text(json.dumps({"run_seconds": self._run_seconds}))
        except OSError as exc:
            log.warning("could not persist daemon state: %s", exc)

    # --- checking -> measuring ---------------------------------------------
    def _try_start(self) -> None:
        sensor = None
        try:
            sensor = self._sensor_factory(self.port, self.timeout)
            product, serial_no, firmware = self._probe(sensor)
        except (SPS30Error, serial.SerialException, OSError) as exc:
            # A silent sensor is not necessarily an absent one: in Sleep-Mode the
            # UART is switched off entirely, so a sensor left asleep by a previous
            # duty-cycle run (a daemon restart mid-cycle, say) answers nothing at
            # all. Send the wake sequence and probe once more before writing it
            # off - otherwise low-power mode would lose the sensor permanently
            # across a restart.
            woken = False
            if sensor is not None:
                try:
                    sensor.wake()
                    product, serial_no, firmware = self._probe(sensor)
                    woken = True
                    log.info("sensor was asleep; woken during detection")
                except (SPS30Error, serial.SerialException, OSError):
                    pass
            if not woken:
                if sensor is not None:
                    try:
                        sensor.close()
                    except Exception:
                        pass
                log.debug("sensor not present yet: %s", exc)
                self._sleep(self.poll_interval)
                return
        self._asleep = False

        self._sensor = sensor
        with self._snap_lock:
            self._info = {
                "product": product,
                "serial": serial_no,
                "firmware": firmware,
            }
        log.info(
            "sensor detected: product=%s serial=%s firmware=%s",
            product,
            serial_no,
            firmware,
        )
        resumed = self._recovering
        self._recovering = False
        if resumed:
            log.info("sensor recovered; continuing the same recording")
        self._begin_measuring(
            "sensor recovered" if resumed else "sensor detected",
            new_session=not resumed,
        )

    @staticmethod
    def _probe(sensor) -> tuple[str, str, str]:
        """Identify a sensor. Raises if it does not answer."""
        return (
            sensor.product_type(),
            sensor.serial_number(),
            sensor.firmware_version(),
        )

    def _begin_measuring(self, detail: str, new_session: bool = True) -> None:
        """Start measuring. [new_session] False continues the current CSV.

        Recovering from a transient sensor dropout is not a new session: the
        sensor, the settings and the user's intent are all unchanged, so the
        readings belong in the file they were already going to. Opening a fresh
        one on every recovery is what made the client - which backfills the
        current file - lose everything before the most recent glitch.
        """
        try:
            # The sensor may already be measuring from a prior unclean shutdown
            # (a systemd restart while running), in which case start_measurement
            # returns 0x43 "command not allowed in current state". Stop first to
            # reach a known idle state; ignore errors if it was already idle.
            try:
                self._sensor.stop_measurement()
            except (SPS30Error, serial.SerialException, OSError):
                pass
            self._sensor.start_measurement()
        except (SPS30Error, serial.SerialException, OSError) as exc:
            self._handle_sensor_loss(f"start_measurement failed: {exc}")
            return
        if new_session or self._csv_file is None:
            self._open_new_csv()
        # The fresh session's first readings are a settling spike; flag them with
        # the cause set by the caller (startup/resume/restart), then reset.
        self._flag_settle(self._start_event)
        self._start_event = "startup"
        self._set_state(wire.STATE_MEASURING, detail)

    def _flag_settle(self, event: str) -> None:
        """Flag the next few readings as an unreliable settling spike labelled
        [event]. The count is derived from SETTLE_SECONDS and the read interval.

        Duty cycling never flags: every point it records is already the average
        of readings taken *after* a full warm-up, which is precisely what makes
        it trustworthy. Flagging by row count would also be badly wrong there -
        one row per cycle means SETTLE_SECONDS worth of rows is hours of data,
        all of it hidden from the plot by the client's exclude-flagged filter.
        """
        if self.duty_cycling:
            self._settle_rows = 0
            self._settle_event = ""
            return
        self._settle_event = event
        self._settle_rows = max(1, round(SETTLE_SECONDS / max(self.interval, 1e-3)))

    def _measure_once(self) -> None:
        try:
            m = self._sensor.read_measurement()
        except (SPS30Error, serial.SerialException, OSError) as exc:
            # One timed-out read is not a missing sensor. A marginal supply or a
            # momentary line glitch drops the odd response, and tearing the
            # session down for each one ends the CSV, restarts the index and
            # makes the client's history appear to begin at the last hiccup -
            # which is exactly how days of data went missing from the plot.
            # Only give up after the sensor has been quiet for several reads.
            self._read_failures += 1
            if self._read_failures < READ_FAILURES_BEFORE_LOSS:
                log.debug(
                    "read failed (%d/%d), retrying: %s",
                    self._read_failures, READ_FAILURES_BEFORE_LOSS, exc,
                )
                return
            self._handle_sensor_loss(f"read failed {self._read_failures}x: {exc}")
            return
        self._read_failures = 0
        if m is None:
            return  # no new value ready this tick - normal, keep going
        self._write_reading(m)

    def _write_reading(self, measurement) -> None:
        with self._snap_lock:
            self._index += 1
            index = self._index
            csv_name = self._csv_name
        when = wire.now_eastern()
        if self._settle_rows > 0:
            event = self._settle_event
            self._settle_rows -= 1
        else:
            event = ""
        row = wire.measurement_row(index, when, measurement, event)
        self._writer.writerow(row)
        self._csv_file.flush()
        self._rows_since_sync += 1
        if self._rows_since_sync >= self.fsync_every:
            os.fsync(self._csv_file.fileno())
            self._rows_since_sync = 0
        # Tag the reading with its source CSV. Per-file indices restart at 1
        # whenever a new CSV opens (resume/restart/clean), so the client keys its
        # de-duplication watermark on this name and resets when it changes -
        # otherwise the restarted low indices look "already seen" and the live
        # chart silently freezes until the client reconnects.
        self.broadcast({"type": wire.MSG_READING, "csv": csv_name, **row})

    # --- csv ---------------------------------------------------------------
    def _open_new_csv(self) -> None:
        """Start a brand-new CSV file for this acquisition session."""
        self._close_csv()
        start = wire.now_eastern()
        name = wire.csv_filename(start)
        path = self.csv_dir / name
        # Names are second-resolution, so two sessions started inside the same
        # second would collide and the second would truncate the first. Rare, but
        # it is someone's data - disambiguate rather than overwrite.
        if path.exists():
            stem, suffix = path.stem, path.suffix
            n = 2
            while path.exists():
                path = self.csv_dir / f"{stem}-{n}{suffix}"
                n += 1
            name = path.name
        self.csv_dir.mkdir(parents=True, exist_ok=True)
        self._csv_file = open(path, "w", newline="")
        self._writer = csv.DictWriter(self._csv_file, fieldnames=wire.CSV_COLUMNS)
        self._writer.writeheader()
        self._csv_file.flush()
        with self._snap_lock:
            self._csv_name = name
            self._index = 0
        self._rows_since_sync = 0
        log.info("recording to %s", path)

    def _close_csv(self) -> None:
        if self._csv_file is not None:
            try:
                self._csv_file.flush()
                os.fsync(self._csv_file.fileno())
            except (OSError, ValueError):
                pass
            try:
                self._csv_file.close()
            except OSError:
                pass
        self._csv_file = None
        self._writer = None

    # --- state helpers ------------------------------------------------------
    def _set_state(self, state: str, detail: str, seconds: Optional[float] = None) -> None:
        """Publish a state change. [seconds] is how long this phase is expected to
        last, so a client can count it down locally instead of the daemon
        emitting a tick a second - which matters most while duty cycling, where
        the whole point is that nothing is running between measurements."""
        with self._snap_lock:
            self._state = state
            self._detail = detail
            self._phase_seconds = seconds
        log.info("state -> %s (%s)", state, detail)
        message = {"type": wire.MSG_STATE, "state": state, "detail": detail}
        if seconds is not None:
            message["seconds"] = round(float(seconds), 1)
        self.broadcast(message)

    def _handle_sensor_loss(self, detail: str) -> None:
        log.warning("sensor lost: %s", detail)
        # Keep the CSV open: if the sensor comes back this is one continuous
        # recording with a gap, not two recordings.
        self._teardown_sensor(detail, close_csv=False)
        self._recovering = True
        self._set_state(wire.STATE_CHECKING, detail)

    def _teardown_sensor(self, detail: str = "", close_csv: bool = True) -> None:
        if self._sensor is not None:
            try:
                self._sensor.stop_measurement()
            except Exception:
                pass
            try:
                self._sensor.close()
            except Exception:
                pass
        self._sensor = None
        if close_csv:
            self._close_csv()
        with self._snap_lock:
            if close_csv:
                self._csv_name = ""
            self._info = {"product": "", "serial": "", "firmware": ""}

    # --- command execution (acquisition thread) -----------------------------
    def _drain_commands(self) -> None:
        while True:
            try:
                cmd = self._cmdq.get_nowait()
            except queue.Empty:
                return
            try:
                self._handle_command(cmd)
            except (SPS30Error, serial.SerialException, OSError) as exc:
                log.warning("command %s failed: %s", cmd.name, exc)
                if cmd.client is not None:
                    cmd.client.send(
                        {"type": wire.MSG_ERROR, "message": f"{cmd.name}: {exc}"},
                        lossless=True,
                    )
                self._handle_sensor_loss(f"{cmd.name} failed: {exc}")

    def _handle_command(self, cmd: Command) -> None:
        if cmd.name == wire.CMD_PAUSE:
            self._cmd_pause()
        elif cmd.name == wire.CMD_RESUME:
            self._cmd_resume()
        elif cmd.name == wire.CMD_CLEAN:
            self._cmd_clean(cmd)
        elif cmd.name == wire.CMD_RESTART:
            self._cmd_restart()
        elif cmd.name == wire.CMD_RAW:
            self._cmd_raw(cmd)
        elif cmd.name == wire.CMD_SET_PROFILE:
            self._cmd_set_profile(cmd)
        elif cmd.name == wire.CMD_SHUTDOWN:
            self._cmd_shutdown(cmd)

    def _cmd_set_profile(self, cmd: Command) -> None:
        """Switch acquisition profile and restart the session cleanly.

        Duty cycling needs SPS30 firmware >= 2.0 for Sleep/Wake, so an older
        sensor is refused with a clear reason instead of failing obscurely on the
        first sleep command.
        """
        profile = wire.normalize_profile(cmd.params.get("profile"))
        if profile["mode"] == wire.MODE_DUTY_CYCLE:
            firmware = self._info.get("firmware", "")
            if firmware and not wire.firmware_supports_sleep(firmware):
                message = (
                    f"low-power mode needs sensor firmware 2.0 or newer "
                    f"(this one reports {firmware})"
                )
                log.warning(message)
                if cmd.client is not None:
                    cmd.client.send(
                        {"type": wire.MSG_ERROR, "message": message}, lossless=True
                    )
                return

        was = self.profile
        self.profile = profile
        self._profile_gen += 1
        log.info("profile -> %s", profile)
        self.broadcast(self.profile_message())
        # The modelled current is the whole point of switching modes, so push it
        # now rather than leaving the UI showing the old figure until the next
        # scheduled power poll.
        self._power = self.power_message()["power"]
        self._power_checked_at = time.monotonic()
        self.broadcast({"type": wire.MSG_POWER, "power": self._power})
        if was == profile or self._sensor is None:
            return
        # A new profile means a new acquisition regime; start a fresh CSV session
        # so the file never mixes 1 Hz rows with 15-minute averages.
        if self._asleep:
            try:
                self._sensor.wake()
            except (SPS30Error, serial.SerialException, OSError):
                pass
            self._asleep = False
        self._start_event = "profile"
        self._begin_measuring(f"profile: {profile['mode']}")

    def _cmd_shutdown(self, cmd: Command) -> None:
        """Park the sensor, close the CSV durably, then power the host off.

        Pulling the plug on a Pi risks the SD card, not just the last few rows -
        so this exists to make "turn it off" a safe, one-tap operation.
        """
        log.info("shutdown requested")
        self._set_state(wire.STATE_SHUTDOWN, "powering off - safe to unplug shortly")
        if self._sensor is not None:
            try:
                self._sensor.stop_measurement()
                if wire.firmware_supports_sleep(self._info.get("firmware", "")):
                    self._sensor.sleep()
                    self._asleep = True
            except (SPS30Error, serial.SerialException, OSError) as exc:
                log.warning("could not park sensor before shutdown: %s", exc)
        self._close_csv()
        self._save_state()
        self._stop.set()
        try:
            self._shutdown_hook()
        except Exception:
            log.exception("poweroff failed")

    def _cmd_pause(self) -> None:
        if self._sensor is None:
            return
        if self._state == wire.STATE_MEASURING:
            try:
                self._sensor.stop_measurement()
            except (SPS30Error, serial.SerialException, OSError) as exc:
                self._handle_sensor_loss(f"pause failed: {exc}")
                return
            self._close_csv()
            self._set_state(wire.STATE_PAUSED, "paused by user")

    def _cmd_resume(self) -> None:
        if self._sensor is None:
            # No sensor yet: fall back to checking so it picks one up.
            self._set_state(wire.STATE_CHECKING, "resume with no sensor")
            return
        self._start_event = "resume"
        self._begin_measuring("resumed by user")

    def _cmd_clean(self, cmd: Command) -> None:
        if self._sensor is None:
            if cmd.client is not None:
                cmd.client.send(
                    {"type": wire.MSG_ERROR, "message": "no sensor to clean"},
                    lossless=True,
                )
            return
        was_measuring = self._state == wire.STATE_MEASURING
        self._set_state(wire.STATE_CLEANING, "fan self-clean")
        self._sensor.fan_clean()
        if was_measuring:
            # Same CSV/session continues, but the clean disturbed the air: flag
            # the post-clean spike so it can be excluded like a startup spike.
            self._set_state(wire.STATE_MEASURING, "clean complete")
            self._flag_settle("clean")
        else:
            self._start_event = "clean"
            self._begin_measuring("clean complete")

    def _cmd_restart(self) -> None:
        # Force back to a known-good measuring+streaming state, even after a bad
        # raw command left the device confused: fully tear down and re-probe.
        self._teardown_sensor("restart requested")
        self._start_event = "restart"
        self._set_state(wire.STATE_CHECKING, "restart requested")
        self._try_start()

    def _cmd_raw(self, cmd: Command) -> None:
        req_hex = cmd.params.get("hex", "")
        client = cmd.client
        if self._sensor is None:
            if client is not None:
                client.send({"type": wire.MSG_ERROR, "message": "no sensor for raw"},
                            lossless=True)
            return
        try:
            resp_hex = self._raw_transaction(req_hex)
        except (SPS30Error, serial.SerialException, OSError, ValueError) as exc:
            if client is not None:
                client.send({"type": wire.MSG_ERROR, "message": f"raw: {exc}"},
                            lossless=True)
            return
        if client is not None:
            client.send(
                {"type": wire.MSG_RAW, "request": req_hex, "response": resp_hex},
                lossless=True,
            )

    def _raw_transaction(self, req_hex: str) -> str:
        """Write raw hex bytes to the port and return the raw response hex.

        Runs on the acquisition thread only (the single serial owner), using the
        port the daemon already owns via ``sps30.SPS30``. Bytes are passed
        through verbatim by ``SPS30.transceive_raw`` so the raw console can probe
        the device however it likes.
        """
        req = bytes.fromhex(req_hex.replace(" ", ""))
        return self._sensor.transceive_raw(req).hex()

    # ==================================================================
    # TCP server
    # ==================================================================
    def _serve(self) -> None:
        assert self._server_sock is not None
        self._server_sock.settimeout(0.5)
        while not self._stop.is_set():
            try:
                conn, addr = self._server_sock.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

            # Refuse politely past the cap rather than accepting and starving
            # everyone. The limit exists so the Pi behaves exactly as the
            # ESP32-C6 will, instead of working here and failing there.
            if self.client_count() >= wire.MAX_CLIENTS:
                log.warning("refusing %s: already serving %d clients", addr, wire.MAX_CLIENTS)
                try:
                    conn.sendall(wire.encode({
                        "type": wire.MSG_ERROR,
                        "message": f"sensor is already serving {wire.MAX_CLIENTS} clients",
                        "at_capacity": True,
                    }))
                except OSError:
                    pass
                try:
                    conn.close()
                except OSError:
                    pass
                continue

            client = ClientHandler(conn, addr, self)
            self.add_client(client)
            try:
                hello = self.hello_message()
                hello["client_id"] = client.client_id
                conn.sendall(wire.encode(hello))
                conn.sendall(wire.encode(self.control_message()))
            except OSError:
                self.remove_client(client)
                try:
                    conn.close()
                except OSError:
                    pass
                continue
            client.start()
            log.info("client connected: %s", addr)

    # ==================================================================
    # lifecycle
    # ==================================================================
    def start(self) -> None:
        """Start the TCP server and the acquisition thread."""
        self.csv_dir.mkdir(parents=True, exist_ok=True)
        self._server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server_sock.bind((self.host, self.tcp_port))
        self._server_sock.listen(16)
        # Reflect the actual bound port (useful when tcp_port == 0 in tests).
        self.tcp_port = self._server_sock.getsockname()[1]
        log.info("TCP server listening on %s:%d", self.host, self.tcp_port)

        self._accept_thread = threading.Thread(
            target=self._serve, name="tcp-accept", daemon=True
        )
        self._acq_thread = threading.Thread(
            target=self.run, name="acquisition", daemon=True
        )
        self._accept_thread.start()
        self._acq_thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._wake.set()
        if self._server_sock is not None:
            try:
                self._server_sock.close()
            except OSError:
                pass
        with self._clients_lock:
            clients = list(self._clients)
        for client in clients:
            client.close()
        if self._acq_thread is not None:
            self._acq_thread.join(timeout=5.0)
        if self._accept_thread is not None:
            self._accept_thread.join(timeout=5.0)

    def wait(self) -> None:
        """Block the calling (main) thread until the acquisition thread exits."""
        if self._acq_thread is not None:
            while self._acq_thread.is_alive():
                self._acq_thread.join(timeout=1.0)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def _env(name: str, default: str) -> str:
    return os.environ.get(name, default)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="SPS30 acquisition daemon (Raspberry Pi).")
    p.add_argument(
        "--port",
        default=_env("SPS30_PORT", "/dev/serial0"),
        help="serial device for the SPS30 (env SPS30_PORT)",
    )
    p.add_argument(
        "--tcp-port",
        type=int,
        default=int(_env("SPS30_TCP_PORT", str(wire.DEFAULT_PORT))),
        help="TCP port to serve on (env SPS30_TCP_PORT)",
    )
    p.add_argument(
        "--host",
        default=_env("SPS30_HOST", wire.DEFAULT_HOST),
        help="TCP bind address (env SPS30_HOST)",
    )
    p.add_argument(
        "--csv-dir",
        default=_env("SPS30_CSV_DIR", str(Path.home() / "sps30-data")),
        help="directory for authoritative CSV files (env SPS30_CSV_DIR)",
    )
    p.add_argument(
        "--interval",
        type=float,
        default=float(_env("SPS30_INTERVAL", "1.0")),
        help="seconds between measurement reads (env SPS30_INTERVAL)",
    )
    p.add_argument(
        "--mode",
        choices=[wire.MODE_CONTINUOUS, wire.MODE_DUTY_CYCLE],
        default=_env("SPS30_MODE", wire.MODE_CONTINUOUS),
        help=(
            "acquisition mode (env SPS30_MODE). '%s' measures non-stop; '%s' is "
            "Sensirion's low-power duty cycle (env SPS30_MODE)"
            % (wire.MODE_CONTINUOUS, wire.MODE_DUTY_CYCLE)
        ),
    )
    p.add_argument(
        "--cycle",
        type=float,
        default=float(_env("SPS30_CYCLE", str(wire.CYCLE_DEFAULT))),
        help="low-power: seconds from one measurement to the next (env SPS30_CYCLE)",
    )
    p.add_argument(
        "--warmup",
        type=float,
        default=float(_env("SPS30_WARMUP", str(wire.WARMUP_DEFAULT))),
        help=(
            "low-power: seconds of fan spin-up discarded before averaging; "
            "Sensirion recommend >= 30 and never below 8 (env SPS30_WARMUP)"
        ),
    )
    p.add_argument(
        "--average",
        type=float,
        default=float(_env("SPS30_AVERAGE", str(wire.AVERAGE_DEFAULT))),
        help="low-power: seconds of readings averaged into one point (env SPS30_AVERAGE)",
    )
    p.add_argument(
        "--fsync-every",
        type=int,
        default=int(_env("SPS30_FSYNC_EVERY", str(FSYNC_EVERY))),
        help=(
            "fsync the CSV every N rows; flush() still happens every row "
            "(env SPS30_FSYNC_EVERY, default %(default)s). On power loss up to "
            "this many rows may be lost; 1 = fsync every row"
        ),
    )
    return p


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    daemon = Daemon(
        port=args.port,
        tcp_port=args.tcp_port,
        host=args.host,
        csv_dir=Path(args.csv_dir),
        fsync_every=args.fsync_every,
        profile={
            "mode": args.mode,
            "interval": args.interval,
            "cycle_s": args.cycle,
            "warmup_s": args.warmup,
            "average_s": args.average,
        },
    )
    daemon.start()
    try:
        daemon.wait()
    except KeyboardInterrupt:
        log.info("interrupted; shutting down")
    finally:
        daemon.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
