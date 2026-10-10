#!/usr/bin/env python3
"""Textual TUI client for the SPS30 live dashboard daemon.

Connects to ``sps30_daemon.py`` over TCP (JSONL framed by ``wire.iter_messages``)
and renders a terminal dashboard that works over plain SSH on WSL2 and Android
JuiceSSH - no GUI toolkit, no browser. It shows a live time-series chart of the
PM mass values, a status panel, pause/resume/clean/restart controls, a raw SHDLC
command console, a Pi file browser with historical import, and it keeps its own
local CSV mirror of every reading. The socket runs on a background thread and
auto-reconnects with backoff so losing the connection never crashes the UI.

The design deliberately keeps all non-UI logic in plain, dependency-light
classes (``LocalMirror``, ``FileAssembler``, ``RateMeter``, ``DashboardState``)
plus the pure chart/view model in :mod:`chartview` (``TimeSeriesHistory``,
``ChartView``, ``parse_duration``) and small pure functions (``normalize_hex``,
``backfill_request``). Those are unit-tested without a running Textual app or a
socket; the widgets are thin adapters over them.
"""
from __future__ import annotations

import argparse
import csv
import socket
import sys
import threading
import time
from collections import deque
from datetime import datetime
from pathlib import Path

import wire
import chartview

# sps30 is imported lazily/defensively only to compute example raw frames for
# the help text; the GUI never opens a serial port.
try:  # pragma: no cover - import guard
    import sps30
except Exception:  # pragma: no cover - sps30 pulls in pyserial, optional here
    sps30 = None


# --- shared constants -------------------------------------------------------
# The four PM *mass* fields are what the live chart plots (ug/m3 vs time).
PM_FIELDS = ["pm1_0", "pm2_5", "pm4_0", "pm10"]
PM_LABELS = {
    "pm1_0": "PM1.0",
    "pm2_5": "PM2.5",
    "pm4_0": "PM4.0",
    "pm10": "PM10",
}


# --- pure helpers -----------------------------------------------------------
def index_int(value) -> int:
    """Parse a wire index (``'0000007'`` or ``7``) into a plain int."""
    if isinstance(value, bool):  # guard: bool is an int subclass
        return int(value)
    if isinstance(value, int):
        return value
    text = str(value).strip()
    if not text:
        return 0
    return int(text)


def normalize_hex(text: str) -> str:
    """Validate and canonicalise a raw SHDLC frame typed by the user.

    Accepts forms like ``"7e0000020103f97e"``, ``"7E 00 00 02 ..."``,
    ``"0x7e..."`` or colon-separated bytes. Returns a lowercase, un-spaced hex
    string of whole bytes. Raises ``ValueError`` on anything that is not a
    valid, byte-aligned hex string.
    """
    s = text.strip().lower()
    for junk in (" ", "\t", "\n", ":", "-", "0x"):
        s = s.replace(junk, "")
    if not s:
        raise ValueError("empty hex string")
    if len(s) % 2 != 0:
        raise ValueError("hex must be a whole number of bytes (even length)")
    if any(c not in "0123456789abcdef" for c in s):
        raise ValueError("contains non-hex characters")
    return s


def is_valid_hex(text: str) -> bool:
    """True if ``text`` is a valid byte-aligned hex frame."""
    try:
        normalize_hex(text)
        return True
    except ValueError:
        return False


def _epoch_of_iso(ts) -> float | None:
    """Epoch seconds from an ISO timestamp string, or None if blank/unparseable."""
    if not ts:
        return None
    try:
        return datetime.fromisoformat(str(ts)).timestamp()
    except (ValueError, TypeError):
        return None


def _human_span(seconds: float) -> str:
    """Compact human label for a duration, e.g. 90 -> '1m30s', 43200 -> '12h'."""
    seconds = int(round(max(0.0, seconds)))
    if seconds < 60:
        return f"{seconds}s"
    if seconds < 3600:
        m, s = divmod(seconds, 60)
        return f"{m}m" if s == 0 else f"{m}m{s}s"
    if seconds < 86400:
        h, rem = divmod(seconds, 3600)
        m = rem // 60
        return f"{h}h" if m == 0 else f"{h}h{m}m"
    d, rem = divmod(seconds, 86400)
    h = rem // 3600
    return f"{d}d" if h == 0 else f"{d}d{h}h"


def backfill_request(name: str, since_index: int) -> dict | None:
    """Build the ``CMD_GET_FILE`` command that backfills rows we missed.

    ``since_index`` is our highest locally known Pi index; the daemon returns
    only rows *after* it. Returns ``None`` when there is nothing to backfill.
    """
    if not name or since_index <= 0:
        return None
    return {"cmd": wire.CMD_GET_FILE, "name": name, "since_index": since_index}


# The chart history + view model lives in ``chartview`` (TimeSeriesHistory,
# ChartView, parse_duration): a dependency-light, exhaustively unit-tested pure
# module. The GUI below is a thin adapter over it.


# --- local CSV mirror -------------------------------------------------------
class LocalMirror:
    """Append every reading to a local CSV using the shared ``wire`` schema."""

    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._needs_header = (not self.path.exists()) or self.path.stat().st_size == 0
        self._fh = None
        self._writer = None

    def _ensure_open(self) -> None:
        if self._fh is None:
            self._fh = open(self.path, "a", newline="", encoding="utf-8")
            self._writer = csv.DictWriter(
                self._fh, fieldnames=wire.CSV_COLUMNS, extrasaction="ignore"
            )
            if self._needs_header:
                self._writer.writeheader()
                self._needs_header = False

    def append(self, reading: dict) -> None:
        """Write one reading row (index/timestamp preserved as sent)."""
        self._ensure_open()
        row = {}
        for column in wire.CSV_COLUMNS:
            value = reading.get(column, "")
            if column == "index" and value != "" and not isinstance(value, str):
                # keep the zero-padded on-wire form even if given an int
                value = wire.format_index(index_int(value))
            row[column] = value
        self._writer.writerow(row)
        self._fh.flush()

    def close(self) -> None:
        if self._fh is not None:
            try:
                self._fh.close()
            finally:
                self._fh = None
                self._writer = None


# --- file import reassembly -------------------------------------------------
class FileAssembler:
    """Reassemble a possibly-chunked ``MSG_FILE_DATA`` stream, in arrival order."""

    def __init__(self):
        self.name = None
        self.columns = None
        self.rows: list = []
        self.done = False

    def reset(self, name: str | None = None) -> None:
        self.name = name
        self.columns = None
        self.rows = []
        self.done = False

    def add_chunk(self, message: dict) -> bool:
        """Add one ``MSG_FILE_DATA`` message. Returns True once ``done`` seen."""
        name = message.get("name")
        if name and self.name is None:
            self.name = name
        columns = message.get("columns")
        if columns:
            self.columns = columns
        self.rows.extend(message.get("rows") or [])
        if message.get("done"):
            self.done = True
        return self.done

    def result(self) -> dict:
        return {"name": self.name, "columns": self.columns, "rows": self.rows}


# --- serialized file transfers ----------------------------------------------
class FileRequestQueue:
    """Serialize ``CMD_GET_FILE`` requests so only one transfer is in flight.

    The wire protocol has no correlation id, so a single ``FileAssembler`` and
    ``purpose`` slot must not be shared by two overlapping transfers. If a
    request (a reconnect backfill or a user import) arrives while another is
    still streaming, it is queued and dispatched only once the in-flight
    transfer delivers its final ``done=true`` chunk. Each entry is a
    ``(purpose, command)`` tuple.
    """

    def __init__(self):
        self._inflight: tuple | None = None
        self._pending: deque[tuple] = deque()

    @property
    def inflight(self) -> tuple | None:
        """The ``(purpose, command)`` currently transferring, or ``None``."""
        return self._inflight

    def request(self, purpose: str, command: dict) -> tuple | None:
        """Register a request. Returns it to dispatch now, or ``None`` if it was
        queued behind an in-flight transfer."""
        entry = (purpose, command)
        if self._inflight is None:
            self._inflight = entry
            return entry
        self._pending.append(entry)
        return None

    def complete(self) -> tuple | None:
        """Mark the in-flight transfer finished. Returns the next queued
        ``(purpose, command)`` to dispatch, or ``None`` if nothing is waiting."""
        if self._pending:
            self._inflight = self._pending.popleft()
            return self._inflight
        self._inflight = None
        return None

    def drop_pending(self) -> None:
        """Discard queued (not-yet-started) requests, leaving the in-flight one.
        The in-flight transfer still has bytes draining over the socket, so it
        must be allowed to finish (its result may be swallowed) rather than
        dropped mid-stream - the wire protocol has no way to cancel it."""
        self._pending.clear()


# --- measurement rate -------------------------------------------------------
class RateMeter:
    """Sliding-window estimate of readings/second from receipt timestamps."""

    def __init__(self, window: int = 30):
        self.times: deque[float] = deque(maxlen=max(2, window))

    def tick(self, now: float) -> None:
        self.times.append(now)

    def rate(self) -> float:
        if len(self.times) < 2:
            return 0.0
        span = self.times[-1] - self.times[0]
        if span <= 0:
            return 0.0
        return (len(self.times) - 1) / span


# --- central dashboard state (all message handling lives here) --------------
class DashboardState:
    """Applies decoded wire messages to in-memory state + the local mirror.

    This is the testable heart of the client: no Textual, no socket. The app
    feeds it decoded dicts from ``wire.iter_messages`` and reads back the
    resulting state to paint widgets. ``handle_message`` returns a small
    ``(kind, payload)`` event so the UI knows what changed.
    """

    def __init__(self, retention_seconds: float = 172800.0,
                 mirror: LocalMirror | None = None, clock=None):
        self.history = chartview.TimeSeriesHistory(retention_seconds)
        self.mirror = mirror
        self.clock = clock or time.monotonic
        self.rate_meter = RateMeter()

        self.product = None
        self.serial = None
        self.firmware = None
        self.daemon_state = None
        self.state_detail = ""
        self.pi_csv = None
        # ``last_index`` is the *contiguous* high-water mark: every index up to
        # and including it has been seen. ``_seen_above`` holds indices seen out
        # of order that sit above a gap (e.g. live readings that arrived before a
        # late backfill chunk filled the rows behind them). Dedup checks both, so
        # ingestion is idempotent by index regardless of arrival order, and the
        # backfill ``since_index`` (== last_index) still asks only for the gap.
        self.last_index = 0
        self._seen_above: set[int] = set()
        # The CSV these live readings belong to. Per-file indices restart at 1
        # each time the daemon opens a new CSV (resume/restart/clean), so the
        # index watermark is only meaningful within one file. When a reading
        # announces a different CSV we start a fresh watermark for it.
        self.active_csv = None
        self.files: list[dict] = []
        self.raw_log: list[dict] = []
        self.last_error = None
        self._seen_hello = False

    # -- individual message handlers ----------------------------------------
    def handle_message(self, message: dict):
        msg_type = message.get("type")
        if msg_type == wire.MSG_HELLO:
            return self._on_hello(message)
        if msg_type == wire.MSG_READING:
            return self._on_reading(message)
        if msg_type == wire.MSG_STATE:
            self.daemon_state = message.get("state")
            self.state_detail = message.get("detail", "")
            return ("state", message)
        if msg_type == wire.MSG_RAW:
            self.raw_log.append(message)
            return ("raw", message)
        if msg_type == wire.MSG_FILES:
            self.files = list(message.get("files") or [])
            return ("files", message)
        if msg_type == wire.MSG_FILE_DATA:
            return ("file_data", message)
        if msg_type == wire.MSG_ERROR:
            self.last_error = message.get("message")
            return ("error", message)
        return ("unknown", message)

    def _on_hello(self, message: dict):
        previous_csv = self.pi_csv
        self.product = message.get("product")
        self.serial = message.get("serial")
        self.firmware = message.get("firmware")
        self.daemon_state = message.get("state")
        self.pi_csv = message.get("csv")
        hello_index = index_int(message.get("index", 0))

        backfill = None
        if not self._seen_hello:
            # First ever connection: adopt the daemon's current index as our
            # baseline so we stream forward from "now" without re-ingesting.
            self.last_index = hello_index
            self._seen_above.clear()
        elif previous_csv and self.pi_csv == previous_csv and self.last_index > 0:
            # Reconnected to the same Pi CSV: ask for the rows we missed. The
            # contiguous watermark is exactly the last row we can prove we have.
            backfill = backfill_request(self.pi_csv, self.last_index)
        else:
            # Reconnected but the Pi rolled to a new CSV: start fresh from it.
            self.last_index = 0
            self._seen_above.clear()
        self.active_csv = self.pi_csv
        self._seen_hello = True
        return ("hello", {"message": message, "backfill": backfill})

    def _on_reading(self, message: dict):
        added = self.ingest_reading(message)
        if added:
            self.rate_meter.tick(self.clock())
        return ("reading", message)

    # -- ingestion (shared by live readings and backfilled file rows) -------
    def ingest_reading(self, reading: dict) -> bool:
        """Add a reading unless its index has already been seen.

        Idempotent by index regardless of arrival order: a live reading that
        jumped ahead of a gap and the later backfill rows behind it are each
        accepted exactly once. Accepted rows are appended in arrival order to
        the history buffer and the local mirror - a late backfill therefore
        lands after the newer live rows, i.e. the mirror CSV can contain
        out-of-order indices (documented; the row set is always complete and
        never duplicated).
        """
        # A live reading carries the CSV it came from. If the daemon rolled to a
        # new file (resume/restart/clean), its indices restart at 1, so reset the
        # watermark to that new file before de-duplicating - otherwise the whole
        # restarted stream is discarded as "already seen" and the chart freezes.
        # Backfilled file rows have no "csv" key and never trigger a reset; they
        # are always for the current active file.
        csv_name = reading.get("csv")
        if csv_name and csv_name != self.active_csv:
            self.active_csv = csv_name
            self.pi_csv = csv_name
            self.last_index = 0
            self._seen_above.clear()

        idx = index_int(reading.get("index", 0))
        if idx <= self.last_index or idx in self._seen_above:
            return False
        self.history.add(reading)
        if self.mirror is not None:
            self.mirror.append(reading)
        self._seen_above.add(idx)
        # Advance the contiguous watermark across any gap this row just filled.
        while (self.last_index + 1) in self._seen_above:
            self.last_index += 1
            self._seen_above.discard(self.last_index)
        return True

    def ingest_file_rows(self, columns, rows) -> int:
        """Ingest backfilled CSV rows; returns how many were actually new."""
        added = 0
        for reading in wire.rows_to_readings(columns, rows):
            if self.ingest_reading(reading):
                added += 1
        return added

    # -- convenience for the UI ---------------------------------------------
    def rate(self) -> float:
        return self.rate_meter.rate()

    def since_index(self) -> int:
        return self.last_index


# --- background socket client (thread; never touches Textual directly) ------
class NetworkClient:
    """Std-lib socket client on a daemon thread with reconnect + backoff.

    Callbacks (``on_message`` / ``on_status``) are invoked from the network
    thread; the app wraps them with ``App.call_from_thread`` so all UI mutation
    happens on the event loop. Socket I/O never blocks the UI.
    """

    def __init__(self, host, port, on_message, on_status,
                 backoff_start: float = 1.0, backoff_max: float = 30.0):
        self.host = host
        self.port = port
        self.on_message = on_message
        self.on_status = on_status
        self.backoff_start = backoff_start
        self.backoff_max = backoff_max

        self._sock: socket.socket | None = None
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._connected_once = False

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="sps30-net",
                                        daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._close()

    def _close(self) -> None:
        with self._lock:
            if self._sock is not None:
                try:
                    self._sock.close()
                except OSError:
                    pass
                self._sock = None

    def send(self, command: dict) -> bool:
        """Encode + send a command. Returns False if not currently connected."""
        data = wire.encode(command)
        with self._lock:
            if self._sock is None:
                return False
            try:
                self._sock.sendall(data)
                return True
            except OSError:
                return False

    def _run(self) -> None:
        backoff = self.backoff_start
        while not self._stop.is_set():
            try:
                sock = socket.create_connection((self.host, self.port), timeout=10)
            except OSError as exc:
                self.on_status("reconnecting", f"cannot reach {self.host}: {exc}")
                if self._stop.wait(backoff):
                    break
                backoff = min(self.backoff_max, backoff * 2)
                continue

            with self._lock:
                self._sock = sock
            backoff = self.backoff_start
            reconnected = self._connected_once
            self._connected_once = True
            self.on_status(
                "connected",
                "reconnected" if reconnected else f"connected to {self.host}:{self.port}",
            )
            try:
                self._recv_loop(sock)
            except OSError as exc:
                self.on_status("disconnected", str(exc))
            finally:
                self._close()

            if self._stop.is_set():
                break
            self.on_status("reconnecting", "connection lost; retrying")
            if self._stop.wait(backoff):
                break
            backoff = min(self.backoff_max, backoff * 2)

    def _recv_loop(self, sock: socket.socket) -> None:
        buffer = bytearray()
        sock.settimeout(1.0)
        while not self._stop.is_set():
            try:
                chunk = sock.recv(4096)
            except socket.timeout:
                continue
            if not chunk:
                raise OSError("connection closed by peer")
            buffer.extend(chunk)
            for message in wire.iter_messages(buffer):
                self.on_message(message)


def example_frames() -> list[tuple[str, str]]:
    """A couple of example raw SHDLC frames for the console help text."""
    examples = []
    if sps30 is not None:
        try:
            examples.append(("read measured values",
                             sps30.build_frame(sps30.CMD_READ).hex()))
            examples.append(("device info / product type",
                             sps30.build_frame(sps30.CMD_INFO, bytes([0x00])).hex()))
        except Exception:  # pragma: no cover
            pass
    if not examples:
        examples = [("read measured values", "7e0000030002fd7e")]
    return examples


# ===========================================================================
# Textual UI (thin adapters over the logic above)
# ===========================================================================
def _build_app_class():
    """Construct the Textual App class lazily so the module imports without a TTY.

    Importing Textual is cheap, but keeping the widget class behind a factory
    keeps the pure-logic section usable in environments where Textual is absent
    and makes the import order explicit.
    """
    from textual.app import App, ComposeResult
    from textual.binding import Binding
    from textual.containers import Horizontal, Vertical, VerticalScroll
    from textual.widgets import (
        Button, Footer, Header, Input, Label, ListItem, ListView, RichLog, Static,
    )
    from textual_plotext import PlotextPlot

    class Sps30Dashboard(App):
        CSS = """
        Screen { layout: vertical; }
        #banner { height: 1; content-align: center middle; background: $primary; }
        #banner.ok { background: $success; }
        #banner.warn { background: $warning; color: black; }
        #banner.err { background: $error; }
        #body { height: 1fr; }
        #left { width: 42; border-right: solid $panel; }
        #right { width: 1fr; }
        #status { height: auto; border: round $panel; padding: 0 1; }
        #controls { height: auto; }
        #controls Button { width: 100%; margin: 0 0 1 0; }
        #restart { background: $error; }
        #files { height: 1fr; border: round $panel; }
        #chart { height: 2fr; border: round $panel; }
        #rawlog { height: 1fr; border: round $panel; }
        #rawinput { height: 3; }
        #windowinput { height: 3; }
        """

        # Stable colour per PM series (plotext colour names) so the mapping never
        # shuffles as series come and go. chartview supplies a distinct marker per
        # series so coincident (equal) lines interleave instead of overdrawing.
        SERIES_COLORS = {
            "pm1_0": (238, 232, 170),  # pale yellow
            "pm2_5": "green+",
            "pm4_0": "red",
            "pm10": "cyan",
        }

        BINDINGS = [
            Binding("p", "pause", "Pause"),
            Binding("r", "resume", "Resume"),
            Binding("c", "clean", "Clean"),
            Binding("x", "restart", "Restart (recover)"),
            Binding("e", "clear_session", "Clear"),
            Binding("l", "list_files", "Files"),
            Binding("t", "toggle_pin", "Pin/Move"),
            Binding("f", "toggle_xauto", "X-freeze"),
            Binding("y", "toggle_yauto", "Y-auto"),
            Binding("plus", "zoom_in", "Zoom in"),
            Binding("minus", "zoom_out", "Zoom out"),
            Binding("equals_sign", "zoom_in", "Zoom in", show=False),
            Binding("comma", "pan_left", "Pan <"),
            Binding("full_stop", "pan_right", "Pan >"),
            Binding("q", "quit", "Quit"),
        ]

        def __init__(self, host, port, data_dir, window_seconds, retention_seconds):
            super().__init__()
            self.host = host
            self.port = port
            self.data_dir = Path(data_dir).expanduser()
            self.connect_time = wire.now_eastern()

            self.mirror = LocalMirror(self._unique_mirror_path())
            self.state = DashboardState(retention_seconds=retention_seconds,
                                        mirror=self.mirror)
            # The view over the (single, growing) live history. Importing an older
            # file merges its rows into the SAME history so the imported series
            # keeps being extended by live readings - there is no separate
            # "imported" buffer or mode any more.
            self.view = chartview.ChartView(window_seconds=window_seconds)
            self.assembler = FileAssembler()
            self.client: NetworkClient | None = None

            # Serializes CMD_GET_FILE so a backfill and an import never share the
            # single FileAssembler/purpose slot (the protocol has no request id).
            self.file_requests = FileRequestQueue()
            # Historical-CSV bookkeeping for backfill-on-grow + import.
            self.files_meta: list[dict] = []   # from the last CMD_LIST_FILES
            self.local_files: list[dict] = []  # this box's own archived/mirror CSVs
            self.loaded_files: set[str] = set()  # names already merged into history
            self._grow_target: float | None = None  # epoch we still need older data back to
            self._xfer_last = 0.0  # monotonic of the last file-transfer progress
            # Set when a clear happens mid-transfer: the outstanding transfer is
            # allowed to drain over the socket but its result is discarded.
            self._swallow_inflight = False
            # Cache of local-file row counts keyed by (name, size, mtime) so the
            # file-list scan does not re-count every CSV on every render.
            self._scan_cache: dict = {}
            # Last data y-extent from a render, used to freeze y when autoscale
            # is turned off.
            self._last_data_y: tuple[float, float] | None = None

        # -- layout ---------------------------------------------------------
        def compose(self) -> ComposeResult:
            yield Header(show_clock=True)
            yield Static("connecting...", id="banner")
            with Horizontal(id="body"):
                with Vertical(id="left"):
                    yield Static(id="status")
                    with Vertical(id="controls"):
                        yield Button("Pause (p)", id="pause")
                        yield Button("Resume (r)", id="resume")
                        yield Button("Clean (c)", id="clean")
                        yield Button("Restart - recover (x)", id="restart",
                                     variant="error")
                        yield Button("Pin/Move (t)", id="pin")
                        yield Button("Clear session (e)", id="clear")
                        yield Button("List files (l)", id="list")
                    yield Label("Files (local + Pi) - Enter to import:")
                    yield ListView(id="files")
                with Vertical(id="right"):
                    yield PlotextPlot(id="chart")
                    yield Input(
                        placeholder="cmd: 12h | y 5 20 | y auto | x 14:00 15:30 | "
                                    "x auto | clear   (Enter)",
                        id="windowinput")
                    yield RichLog(id="rawlog", highlight=False, markup=False,
                                  wrap=True)
                    yield Input(placeholder="raw hex frame e.g. 7e0000030002fd7e",
                                id="rawinput")
            yield Footer()

        def on_mount(self) -> None:
            self.title = "SPS30 Dashboard"
            self.sub_title = f"{self.host}:{self.port}"
            log = self.query_one("#rawlog", RichLog)
            log.write("Raw SHDLC console. Type a hex frame + Enter -> CMD_RAW.")
            for name, frame in example_frames():
                log.write(f"  example ({name}): {frame}")
            log.write("Restart (x) is the recovery action after a bad frame.")
            log.write("Chart: +/- zoom, ,/. pan, t pin/move, f freeze x, y y-auto, "
                      "e clear+archive session. Box above sets the window (12h) or "
                      "a command (y 5 20 | y auto | x 14:00 15:30 | x auto | clear).")
            log.write("Files list shows this box's archives (local) and Pi files; "
                      "Enter one to import it into the live view.")
            self._render_status()
            # Start networking after the UI exists so callbacks can paint.
            self.client = NetworkClient(
                self.host, self.port,
                on_message=self._net_on_message,
                on_status=self._net_on_status,
            )
            self.client.start()
            self.set_interval(1.0, self._render_chart)
            self.set_interval(1.0, self._render_status)
            self.set_interval(5.0, self._check_transfer_stall)

        def on_unmount(self) -> None:
            if self.client is not None:
                self.client.stop()
            self.mirror.close()

        # -- network thread -> UI thread bridges ----------------------------
        def _net_on_message(self, message: dict) -> None:
            try:
                self.call_from_thread(self._handle_message, message)
            except Exception:
                pass  # app shutting down; drop the message

        def _net_on_status(self, status: str, detail: str) -> None:
            try:
                self.call_from_thread(self._handle_status, status, detail)
            except Exception:
                pass

        def _handle_status(self, status: str, detail: str) -> None:
            banner = self.query_one("#banner", Static)
            banner.remove_class("ok", "warn", "err")
            if status == "connected":
                banner.add_class("ok")
                banner.update(f"connected - {detail}")
            elif status == "reconnecting":
                banner.add_class("warn")
                banner.update(f"reconnecting... {detail}")
            else:
                banner.add_class("err")
                banner.update(f"disconnected - {detail} (auto-reconnecting)")

        def _handle_message(self, message: dict) -> None:
            kind, payload = self.state.handle_message(message)
            if kind == "hello":
                backfill = payload.get("backfill")
                if backfill and self.client is not None:
                    self._request_file(
                        "backfill", backfill,
                        f"-> backfill {backfill.get('name')}")
            elif kind == "raw":
                log = self.query_one("#rawlog", RichLog)
                log.write(f"RAW req : {payload.get('request', '')}")
                log.write(f"RAW resp: {payload.get('response', '')}")
            elif kind == "files":
                self.files_meta = list(self.state.files)
                self._render_files()
                # A pending grow was waiting on the file list; resume it now.
                if self._grow_target is not None:
                    target = self._grow_target
                    self._grow_target = None
                    self._backfill_history_to(target)
            elif kind == "file_data":
                self._handle_file_data(payload)
            elif kind == "error":
                log = self.query_one("#rawlog", RichLog)
                log.write(f"ERROR: {payload.get('message', '')}")

        def _request_file(self, purpose: str, command: dict,
                          note: str = "") -> None:
            """Send a CMD_GET_FILE now, or queue it behind an in-flight transfer."""
            to_send = self.file_requests.request(purpose, command)
            if to_send is not None:
                _purpose, cmd = to_send
                self.assembler.reset(cmd.get("name"))
                self._xfer_last = time.monotonic()
                self._send(cmd, note)
            else:
                log = self.query_one("#rawlog", RichLog)
                log.write(
                    f"queued {command.get('name')} (a file transfer is in progress)")

        def _handle_file_data(self, message: dict) -> None:
            inflight = self.file_requests.inflight
            if inflight is None:
                return  # stray chunk with no transfer in flight; ignore
            self._xfer_last = time.monotonic()  # progress; keep the watchdog quiet
            done = self.assembler.add_chunk(message)
            if not done:
                return
            result = self.assembler.result()
            purpose = inflight[0]
            log = self.query_one("#rawlog", RichLog)
            if self._swallow_inflight:
                # A clear happened while this transfer was streaming; drain it but
                # discard the result so stale data can't repopulate the buffer.
                self._swallow_inflight = False
                log.write(f"discarded cancelled transfer {result.get('name')}")
                nxt = self.file_requests.complete()
                if nxt is not None:
                    _purpose, cmd = nxt
                    self.assembler.reset(cmd.get("name"))
                    self._xfer_last = time.monotonic()
                    self._send(cmd, f"-> {cmd.get('name')}")
                return
            if purpose == "backfill":
                added = self.state.ingest_file_rows(result["columns"], result["rows"])
                log.write(f"backfilled {added} missed row(s) from {result['name']}")
            else:  # "import" or "history": merge older rows into the live history
                name = result.get("name")
                # Expand retention to cover these rows BEFORE merging, otherwise
                # the per-add trim would discard anything older than the current
                # retention horizon (the data the user just asked to load).
                self._ensure_retention_for_rows(result["columns"], result["rows"])
                added = self._merge_history_rows(
                    name, result["columns"], result["rows"])
                if name:
                    self.loaded_files.add(name)
                log.write(
                    f"{purpose}: merged {added} row(s) from {name} into live view")
                if purpose == "import":
                    # Widen the view so the just-imported older data is visible,
                    # and keep the live present in frame.
                    self._reveal_imported()
                self._render_chart()
            # Dispatch the next queued transfer, if any.
            nxt = self.file_requests.complete()
            if nxt is not None:
                _purpose, cmd = nxt
                self.assembler.reset(cmd.get("name"))
                self._xfer_last = time.monotonic()
                self._send(cmd, f"-> {cmd.get('name')}")

        def _check_transfer_stall(self) -> None:
            """Recover if a file transfer stalls (peer vanished mid-stream, or a
            lost final chunk against an un-upgraded daemon). Without this the
            in-flight slot stays set forever and every later backfill/import is
            queued behind it and never runs."""
            if self.file_requests.inflight is None:
                return
            if time.monotonic() - self._xfer_last <= 30.0:
                return  # still making progress (or recently started)
            log = self.query_one("#rawlog", RichLog)
            log.write("file transfer stalled >30s; resetting so later "
                      "requests are not blocked")
            self.assembler.reset()
            nxt = self.file_requests.complete()
            if nxt is not None:
                _purpose, cmd = nxt
                self.assembler.reset(cmd.get("name"))
                self._xfer_last = time.monotonic()
                self._send(cmd, f"-> {cmd.get('name')}")

        # -- history import / backfill-on-grow ------------------------------
        def _now_epoch(self) -> float:
            """The live present as an epoch. Never behind the newest reading, so
            clock skew between this box and the Pi cannot clip the latest point."""
            latest = self.state.history.latest_epoch()
            wall = time.time()
            return wall if latest is None else max(wall, latest)

        def _ensure_retention_for_rows(self, columns, rows) -> None:
            """Grow the history retention horizon so it covers the oldest row in a
            file payload; called before merging imported/backfilled rows so they
            are not trimmed away the instant they are added."""
            try:
                ts_idx = columns.index("timestamp")
            except (ValueError, AttributeError):
                ts_idx = 1
            oldest = None
            for row in rows or []:
                if len(row) > ts_idx:
                    epoch = _epoch_of_iso(row[ts_idx])
                    if epoch is not None and (oldest is None or epoch < oldest):
                        oldest = epoch
            if oldest is not None:
                self._ensure_retention_covers(oldest)

        def _merge_history_rows(self, name, columns, rows) -> int:
            """Merge older CSV rows into the live history, tagged with their source
            file so per-file (restarting) indices never collide."""
            readings = []
            for reading in wire.rows_to_readings(columns, rows):
                if name:
                    reading = dict(reading)
                    reading["csv"] = name
                readings.append(reading)
            return self.state.history.merge(readings)

        def _reveal_imported(self) -> None:
            """After an explicit import, pin the view back to the earliest data so
            the imported history is on-screen and grows with live readings."""
            earliest = self.state.history.earliest_epoch()
            if earliest is None:
                return
            now = self._now_epoch()
            span = max(60.0, now - earliest)
            self.view.set_window(span)
            self._ensure_retention_covers(earliest)
            self.view.pin_start = earliest
            self.view.set_mode("pinned", now)

        def _needed_start(self) -> float:
            """The earliest epoch the current view wants to show."""
            start, _end = self.view.visible_range(self._now_epoch())
            return start

        def _backfill_history_to(self, needed_start: float) -> None:
            """Ensure history reaches back to ``needed_start`` by fetching whichever
            Pi CSV files cover the gap. Needs the file list; requests it first and
            resumes here when MSG_FILES arrives."""
            earliest = self.state.history.earliest_epoch()
            if earliest is not None and earliest <= needed_start:
                return  # already covered
            if not self.files_meta:
                self._grow_target = needed_start
                self._send({"cmd": wire.CMD_LIST_FILES}, "-> list files (for history)")
                return
            wanted = self._files_covering(needed_start)
            requested = 0
            for name in wanted:
                if name in self.loaded_files:
                    continue
                self._request_file(
                    "history", {"cmd": wire.CMD_GET_FILE, "name": name},
                    f"-> loading history from {name}")
                requested += 1
            if requested == 0:
                log = self.query_one("#rawlog", RichLog)
                log.write("history: no earlier data available to load")

        def _files_covering(self, needed_start: float) -> list[str]:
            """Names of CSV files whose coverage overlaps [needed_start, now],
            newest first. A file covers [its start, the next file's start)."""
            metas = []
            for f in self.files_meta:
                start = _epoch_of_iso(f.get("start"))
                if start is not None:
                    metas.append((start, f.get("name")))
            metas.sort()  # oldest first, by start epoch
            names: list[str] = []
            for i, (start, name) in enumerate(metas):
                end = metas[i + 1][0] if i + 1 < len(metas) else float("inf")
                if end >= needed_start and name:
                    names.append(name)
            names.reverse()  # newest first
            return names

        # -- rendering ------------------------------------------------------
        def _plot_max_points(self, plot) -> int:
            """Cap plotted samples to ~2x the chart's cell width so long/pinned
            spans never hand plotext tens of thousands of points."""
            try:
                width = int(plot.size.width) or 0
            except Exception:
                width = 0
            return max(200, width * 2)

        def _render_chart(self) -> None:
            try:
                plot = self.query_one("#chart", PlotextPlot)
            except Exception:
                return
            now = self._now_epoch()
            # A long-lived pin (or frozen range) must keep its fixed left edge in
            # memory as `now` advances, or retention would trim the very data it
            # preserves.
            if self.view.mode == "pinned" and self.view.pin_start is not None:
                self._ensure_retention_covers(self.view.pin_start)
            if self.view.x_frozen():
                self._ensure_retention_covers(self.view.x_fixed[0])
            data = self.view.compute(self.state.history, now,
                                     max_points=self._plot_max_points(plot))
            # Remember the visible data y-extent so 'freeze y' can pin at it.
            if data["data_ymin"] is not None:
                self._last_data_y = (data["data_ymin"], data["data_ymax"])
            plt = plot.plt
            try:
                plt.clear_figure()
                plt.title(self._chart_title(data))
                plt.xlabel("time (local)")
                # Pin the x-axis to the exact visible range so it matches the tick
                # labels and shows the intended window even where data is sparse.
                plt.xlim(data["start"], data["end"])
                plt.xticks(data["xticks"], data["xlabels"])
                if not data["y_auto"] and data["y_min"] is not None:
                    plt.ylim(data["y_min"], data["y_max"])
                x = data["x"]
                if x:
                    for field in PM_FIELDS:
                        plt.plot(x, data["series"][field], label=PM_LABELS[field],
                                 marker=data["markers"].get(field),
                                 color=self.SERIES_COLORS.get(field))
                plot.refresh()
            except Exception:
                pass  # never let a plotting hiccup crash the UI

        def _chart_title(self, data: dict) -> str:
            """One-line chart title reflecting the current x/y view state."""
            if data["x_frozen"]:
                a = datetime.fromtimestamp(data["start"], wire.EASTERN).strftime("%H:%M:%S")
                b = datetime.fromtimestamp(data["end"], wire.EASTERN).strftime("%H:%M:%S")
                xpart = f"FROZEN {a}-{b}"
            elif data["mode"] == "pinned":
                since = datetime.fromtimestamp(
                    data["start"], wire.EASTERN).strftime("%H:%M:%S")
                xpart = f"PINNED since {since} ({_human_span(data['span_seconds'])})"
            else:
                xpart = f"last {_human_span(data['span_seconds'])} (moving)"
            ypart = "" if data["y_auto"] else (
                f"  y[{data['y_min']:.2f},{data['y_max']:.2f}]")
            return f"Live PM (ug/m3) - {xpart}{ypart}"

        def _render_status(self) -> None:
            try:
                status = self.query_one("#status", Static)
            except Exception:
                return
            s = self.state
            lines = [
                f"[b]state[/b]     : {s.daemon_state or '-'} {('('+s.state_detail+')') if s.state_detail else ''}",
                f"[b]product[/b]   : {s.product or '-'}",
                f"[b]serial[/b]    : {s.serial or '-'}",
                f"[b]firmware[/b]  : {s.firmware or '-'}",
                f"[b]Pi CSV[/b]    : {s.pi_csv or '-'}",
                f"[b]rate[/b]      : {s.rate():.2f} /s",
                f"[b]last idx[/b]  : {wire.format_index(s.last_index)}",
                f"[b]x-axis[/b]    : {self._x_axis_label()}",
                f"[b]y-axis[/b]    : {self._y_axis_label()}",
                f"[b]buffered[/b]  : {len(s.history)} pts",
                f"[b]mirror[/b]    : {self.mirror.path.name}",
            ]
            status.update("\n".join(lines))

        def _x_axis_label(self) -> str:
            if self.view.x_frozen():
                return "frozen"
            return f"{_human_span(self.view.window_seconds)} ({self.view.mode})"

        def _y_axis_label(self) -> str:
            if self.view.y_auto:
                return "auto"
            return f"[{self.view.y_min:.2f}, {self.view.y_max:.2f}]"

        def _render_files(self) -> None:
            try:
                view = self.query_one("#files", ListView)
            except Exception:
                return
            self.local_files = self._scan_local_files()
            view.clear()
            for entry in self.local_files:
                item = ListItem(Label(
                    f"[local] {entry['name']}  ({entry['rows']} rows)"))
                item.data_name = entry["name"]
                item.data_path = entry["path"]
                view.append(item)
            for entry in self.state.files:
                name = entry.get("name", "?")
                rows = entry.get("rows", "?")
                item = ListItem(Label(f"[pi] {name}  ({rows} rows)"))
                item.data_name = name
                item.data_path = None
                view.append(item)

        # -- actions (key bindings + buttons share these) -------------------
        def _send(self, command: dict, note: str = "") -> None:
            ok = self.client.send(command) if self.client else False
            log = self.query_one("#rawlog", RichLog)
            if ok:
                if note:
                    log.write(note)
            else:
                log.write("not connected - command not sent")

        def action_pause(self) -> None:
            self._send({"cmd": wire.CMD_PAUSE}, "-> pause")

        def action_resume(self) -> None:
            self._send({"cmd": wire.CMD_RESUME}, "-> resume")

        def action_clean(self) -> None:
            self._send({"cmd": wire.CMD_CLEAN}, "-> clean")

        def action_restart(self) -> None:
            self._send({"cmd": wire.CMD_RESTART}, "-> restart (recovery)")

        def action_list_files(self) -> None:
            # Local archives are known immediately; Pi files arrive via MSG_FILES.
            self.local_files = self._scan_local_files()
            self._render_files()
            self._send({"cmd": wire.CMD_LIST_FILES}, "-> list files")

        def action_toggle_pin(self) -> None:
            """Flip between a moving window and a pinned (growing) view. Entering
            pinned keeps the current left edge; returning to it restores the whole
            curve from the pin time to now."""
            mode = self.view.toggle_mode(self._now_epoch())
            self.query_one("#rawlog", RichLog).write(f"-> view mode: {mode}")
            self._after_view_change()

        # -- view (window / zoom / pan / axis) helpers ----------------------
        _MIN_SPAN_SECONDS = 2.0  # never zoom to a sub-second empty window

        def _ensure_retention_covers(self, epoch: float) -> None:
            """Grow retention so ``epoch`` (a left edge or oldest row) stays in
            memory as the live present advances."""
            need = self._now_epoch() - epoch + 3600.0
            if need > self.state.history.retention_seconds:
                self.state.history.set_retention(need)

        def _after_view_change(self) -> None:
            """Shared tail for any view change: keep enough retention for the new
            visible span, backfill older data if the left edge reaches before what
            is in memory, then repaint."""
            start = self._needed_start()
            self._ensure_retention_covers(start)
            self._backfill_history_to(start)
            self._render_chart()

        def _apply_window(self, seconds: float) -> None:
            """Set a moving-window span: follow live again and switch to moving so
            the duration actually takes effect (in pinned mode the window is
            ignored), then fetch older data as needed."""
            self.view.clear_x_range()
            self.view.set_window(seconds)
            self.view.set_mode("moving", self._now_epoch())
            self._after_view_change()

        def action_zoom_in(self) -> None:
            self.view.zoom(1 / 1.5, self._now_epoch())
            if self.view.window_seconds < self._MIN_SPAN_SECONDS:
                self.view.set_window(self._MIN_SPAN_SECONDS)
            self._after_view_change()

        def action_zoom_out(self) -> None:
            self.view.zoom(1.5, self._now_epoch())
            self._after_view_change()

        def action_pan_left(self) -> None:
            self._pan(-0.25)

        def action_pan_right(self) -> None:
            self._pan(0.25)

        def _pan(self, fraction: float) -> None:
            now = self._now_epoch()
            was_live = not self.view.x_frozen()
            start, end = self.view.visible_range(now)
            self.view.pan((end - start) * fraction, now)
            if was_live and self.view.x_frozen():
                self.query_one("#rawlog", RichLog).write(
                    "panned -> x-axis frozen (press f or 'x auto' to follow live)")
            self._after_view_change()

        def action_toggle_xauto(self) -> None:
            now = self._now_epoch()
            self.view.x_autoscale(self.view.x_frozen(), now)
            state = "frozen" if self.view.x_frozen() else "follow-live"
            self.query_one("#rawlog", RichLog).write(f"-> x-axis: {state}")
            self._after_view_change()

        def action_toggle_yauto(self) -> None:
            log = self.query_one("#rawlog", RichLog)
            if self.view.y_auto:
                dmin, dmax = (self._last_data_y or (None, None))
                if dmin is None:
                    log.write("y-axis: no data to freeze at yet")
                    return
                # pad a hair so points sit inside the frame
                pad = max(0.01, (dmax - dmin) * 0.05)
                self.view.freeze_y(dmin - pad, dmax + pad)
                log.write(f"-> y-axis frozen [{dmin - pad:.2f},{dmax + pad:.2f}]")
            else:
                self.view.y_autoscale(True)
                log.write("-> y-axis autoscale")
            self._render_chart()

        # -- clear session + local archive/import ---------------------------
        def action_clear_session(self) -> None:
            """Archive the current session to a local file and wipe the live
            buffer for a fresh session, without dropping the daemon connection."""
            archived = self.mirror.path
            self._rotate_mirror()
            self.state.history.clear()
            # Drop queued transfers, and mark any in-flight one to be swallowed as
            # it drains, so a backfill/import begun before the clear cannot
            # repopulate the freshly wiped buffer. We must NOT reset the queue
            # mid-stream: the daemon keeps sending the in-flight file's chunks (no
            # cancel in the protocol), and a fresh queue would misattribute them
            # to the next transfer.
            if self.file_requests.inflight is not None:
                self._swallow_inflight = True
            self.file_requests.drop_pending()
            self.loaded_files.clear()
            self._grow_target = None
            now = self._now_epoch()
            self.view.clear_x_range()
            self.view.pin_start = now if self.view.mode == "pinned" else None
            self.query_one("#rawlog", RichLog).write(
                f"cleared live buffer; session archived to {archived.name} "
                f"(import it from the files list)")
            self._render_chart()

        def _rotate_mirror(self) -> None:
            """Finalize the current local mirror file and start a fresh one so new
            readings are recorded separately from the archived session."""
            self.mirror.close()
            self.mirror = LocalMirror(self._unique_mirror_path())
            self.state.mirror = self.mirror

        def _unique_mirror_path(self):
            """A mirror path that does not collide with an existing file, so a
            clear within the same second as the last one never appends into an
            archived session's CSV."""
            base = wire.csv_filename(wire.now_eastern())  # ...HHMMSS.csv
            path = self.data_dir / base
            if not path.exists():
                return path
            stem = base[:-4]
            n = 2
            while (self.data_dir / f"{stem}-{n}.csv").exists():
                n += 1
            return self.data_dir / f"{stem}-{n}.csv"

        def _scan_local_files(self) -> list[dict]:
            """This box's own archived/mirror CSVs (newest first), excluding the
            one currently being written. Row counts are cached by (size, mtime)
            so re-listing does not re-count unchanged files."""
            out = []
            current = self.mirror.path.name
            for p in sorted(self.data_dir.glob("SPS30-*.csv"), reverse=True):
                if p.name == current:
                    continue
                try:
                    st = p.stat()
                    key = (p.name, st.st_size, st.st_mtime)
                    rows = self._scan_cache.get(key)
                    if rows is None:
                        with open(p, "rb") as fh:
                            rows = max(0, sum(1 for _ in fh) - 1)
                        self._scan_cache[key] = rows
                except OSError:
                    continue
                out.append({"name": p.name, "rows": rows, "path": str(p)})
            return out

        def _import_local(self, path: str, name: str) -> None:
            """Read a local archive CSV and merge it into the live view. Local
            archives can span daemon restarts (per-file indices repeat), so rows
            are keyed by timestamp, not index, to stay unique and idempotent."""
            log = self.query_one("#rawlog", RichLog)
            try:
                with open(path, newline="") as fh:
                    reader = csv.reader(fh)
                    columns = next(reader, [])
                    rows = [r for r in reader if r]
            except OSError as exc:
                log.write(f"import failed: {exc}")
                return
            self._ensure_retention_for_rows(columns, rows)
            # Namespace local-archive keys away from Pi imports so a same-named
            # file on the Pi can never collide with a local archive (different
            # index schemes), and so a local import never marks a Pi file loaded.
            key_csv = f"local:{name}"
            readings = []
            for reading in wire.rows_to_readings(columns, rows):
                epoch = _epoch_of_iso(reading.get("timestamp"))
                if epoch is None:
                    continue
                reading = dict(reading)
                reading["csv"] = key_csv
                reading["index"] = int(epoch)  # unique per second within the file
                readings.append(reading)
            added = self.state.history.merge(readings)
            self.loaded_files.add(key_csv)
            log.write(f"imported {added} local row(s) from {name} into live view")
            self._reveal_imported()
            self._render_chart()

        # -- widget events --------------------------------------------------
        def on_button_pressed(self, event) -> None:
            handlers = {
                "pause": self.action_pause,
                "resume": self.action_resume,
                "clean": self.action_clean,
                "restart": self.action_restart,
                "pin": self.action_toggle_pin,
                "clear": self.action_clear_session,
                "list": self.action_list_files,
            }
            handler = handlers.get(event.button.id)
            if handler:
                handler()

        def on_list_view_selected(self, event) -> None:
            name = getattr(event.item, "data_name", None)
            if not name:
                return
            local_path = getattr(event.item, "data_path", None)
            if local_path:  # a local archive on this box
                self._import_local(local_path, name)
            else:           # a file on the Pi
                self._request_file(
                    "import", {"cmd": wire.CMD_GET_FILE, "name": name},
                    f"-> importing {name} into live view")

        def _parse_time_token(self, tok: str, now: float) -> float:
            """A command x-range endpoint: 'now', a clock 'HH:MM[:SS]' (today,
            Eastern), or a signed duration offset like '-2h' / '+30m'."""
            tok = tok.strip().lower()
            if tok in ("", "now"):
                return now
            if tok[0] in "+-":
                sign = -1.0 if tok[0] == "-" else 1.0
                return now + sign * chartview.parse_duration(tok[1:])
            parts = tok.split(":")
            if len(parts) in (2, 3) and all(p.isdigit() for p in parts):
                h, m = int(parts[0]), int(parts[1])
                s = int(parts[2]) if len(parts) == 3 else 0
                base = datetime.fromtimestamp(now, wire.EASTERN)
                return base.replace(hour=h, minute=m, second=s,
                                    microsecond=0).timestamp()
            raise ValueError(f"bad time {tok!r}")

        def _run_command(self, text: str, log) -> bool:
            """Parse the command box. Returns True if handled (clear the box),
            False on empty or invalid input (leave the text for correction).
            Grammar: '<duration>' | 'y auto' | 'y <min> <max>' | 'x auto' |
            'x <start> <end>' | 'clear'."""
            if not text:
                return False
            parts = text.split()
            head = parts[0].lower()
            try:
                if head == "clear":
                    self.action_clear_session()
                    return True
                if head == "y":
                    if len(parts) == 2 and parts[1].lower() == "auto":
                        self.view.y_autoscale(True)
                        log.write("-> y-axis autoscale")
                        self._render_chart()
                        return True
                    if len(parts) == 3:
                        self.view.set_y_range(float(parts[1]), float(parts[2]))
                        log.write(f"-> y-range [{parts[1]},{parts[2]}]")
                        self._render_chart()
                        return True
                    log.write("usage: y auto | y <min> <max>")
                    return False
                if head == "x":
                    now = self._now_epoch()
                    if len(parts) == 2 and parts[1].lower() == "auto":
                        self.view.x_autoscale(True, now)
                        log.write("-> x-axis follow-live")
                        self._after_view_change()
                        return True
                    if len(parts) == 3:
                        a = self._parse_time_token(parts[1], now)
                        b = self._parse_time_token(parts[2], now)
                        self.view.set_x_range(a, b)
                        log.write("-> x-range set (frozen)")
                        self._after_view_change()
                        return True
                    log.write("usage: x auto | x <start> <end>")
                    return False
                # otherwise a bare duration sets the moving window
                seconds = chartview.parse_duration(text)
                log.write(f"-> window {_human_span(seconds)}")
                self._apply_window(seconds)
                return True
            except ValueError as exc:
                log.write(f"invalid command: {exc}")
                return False

        def on_input_submitted(self, event) -> None:
            log = self.query_one("#rawlog", RichLog)
            if event.input.id == "windowinput":
                if self._run_command(event.value.strip(), log):
                    event.input.value = ""
                return
            # otherwise the raw SHDLC console
            try:
                frame = normalize_hex(event.value)
            except ValueError as exc:
                log.write(f"invalid hex rejected: {exc}")
                return
            self._send({"cmd": wire.CMD_RAW, "hex": frame}, f"-> raw {frame}")
            event.input.value = ""

    return Sps30Dashboard


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Textual TUI client for the SPS30 dashboard daemon.")
    parser.add_argument("--host", default=wire.DEFAULT_CLIENT_HOST,
                        help="daemon host (default: %(default)s)")
    parser.add_argument("--tcp-port", type=int, default=wire.DEFAULT_PORT,
                        help="daemon TCP port (default: %(default)s)")
    parser.add_argument("--data-dir", default=str(Path.home() / "sps30-gui-data"),
                        help="directory for the local CSV mirror "
                             "(default: %(default)s)")
    parser.add_argument("--window", default="2m",
                        help="initial visible time window, e.g. 12h/30m/1800s "
                             "(default: %(default)s)")
    parser.add_argument("--retention", default="48h",
                        help="how much history to keep in memory, e.g. 48h "
                             "(default: %(default)s)")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    try:
        window_seconds = chartview.parse_duration(args.window)
    except ValueError as exc:
        print(f"bad --window {args.window!r}: {exc}", file=sys.stderr)
        return 2
    try:
        retention_seconds = chartview.parse_duration(args.retention)
    except ValueError as exc:
        print(f"bad --retention {args.retention!r}: {exc}", file=sys.stderr)
        return 2
    # Retention must cover at least the initial window.
    retention_seconds = max(retention_seconds, window_seconds + 3600.0)
    app_class = _build_app_class()
    app = app_class(host=args.host, port=args.tcp_port, data_dir=args.data_dir,
                    window_seconds=window_seconds, retention_seconds=retention_seconds)
    app.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
