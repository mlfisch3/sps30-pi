"""Shared contract between the SPS30 daemon and GUI client.

This module is the single source of truth for:
  * the CSV schema and file naming,
  * the TCP wire protocol (newline-delimited JSON, "JSONL"),
  * the message/command vocabulary.

Both ``sps30_daemon.py`` (server, on the Pi) and ``sps30_gui.py`` (client)
import from here so they can never disagree about the format. Keep it dependency
free (standard library only) so it runs identically on the Pi and the endpoint.
"""
from __future__ import annotations

import json
from dataclasses import asdict
from datetime import datetime
from zoneinfo import ZoneInfo

# --- time / files -----------------------------------------------------------
EASTERN = ZoneInfo("America/New_York")  # "EST" per the spec (handles EST/EDT)

# The ten SPS30 outputs, in fixed order, matching sps30.Measurement fields.
FIELDS = [
    "pm1_0", "pm2_5", "pm4_0", "pm10",
    "nc0_5", "nc1_0", "nc2_5", "nc4_0", "nc10",
    "typical_size",
]
# "event" is blank for normal readings; non-blank (e.g. "startup"/"resume"/
# "restart"/"clean") flags a reading taken while the measurement is disturbed and
# unreliable (sensor spinning up / just cleaned), so clients can exclude it.
CSV_COLUMNS = ["index", "timestamp"] + FIELDS + ["event"]

# Row index is zero-padded to a uniform width good for up to 9,999,999 rows.
INDEX_WIDTH = 7


def format_index(n: int) -> str:
    """Zero-padded measurement counter, e.g. 1 -> '0000001'."""
    return f"{n:0{INDEX_WIDTH}d}"


def now_eastern() -> datetime:
    return datetime.now(EASTERN)


def csv_filename(start: datetime) -> str:
    """Acquisition CSV name from its start time: SPS30-YYYYmmdd_HHMMSS.csv."""
    return start.astimezone(EASTERN).strftime("SPS30-%Y%m%d_%H%M%S.csv")


def iso_timestamp(when: datetime) -> str:
    return when.astimezone(EASTERN).isoformat(timespec="seconds")


def measurement_row(index: int, when: datetime, measurement, event: str = "") -> dict:
    """Build a dict keyed by CSV_COLUMNS from an sps30.Measurement.

    ``event`` flags a disturbed/unreliable reading (blank for normal ones).
    """
    row = {"index": format_index(index), "timestamp": iso_timestamp(when)}
    row.update(asdict(measurement))
    row["event"] = event
    return row


def row_to_reading(columns, row) -> dict:
    """Map one CSV-style ``row`` onto a reading dict keyed by ``columns``.

    Pairs each column name with its cell, skipping any column past the end of a
    short row. Shared by every consumer of ``(columns, rows)`` file payloads so
    the mapping lives in exactly one place.
    """
    return {c: row[i] for i, c in enumerate(columns or []) if i < len(row)}


def rows_to_readings(columns, rows):
    """Yield a reading dict per row for a CSV-style ``(columns, rows)`` payload."""
    for row in rows or []:
        yield row_to_reading(columns, row)


# --- TCP protocol -----------------------------------------------------------
DEFAULT_PORT = 8730
DEFAULT_HOST = "0.0.0.0"       # daemon binds here
DEFAULT_CLIENT_HOST = "127.0.0.1"  # gui connects here by default (SSH-on-Pi case)

# Server -> client message "type" values.
MSG_HELLO = "hello"        # {product, serial, firmware, state, csv, index}
MSG_READING = "reading"    # {csv, index, timestamp, <FIELDS...>, event}
MSG_STATE = "state"        # {state, detail}
MSG_RAW = "raw_response"   # {request, response}  (hex strings)
MSG_FILES = "file_list"    # {files: [{name, rows, bytes, start}]}
MSG_FILE_DATA = "file_data"  # {name, columns, rows: [[...], ...], done}
MSG_ERROR = "error"        # {message}
MSG_PROFILE = "profile"    # the active acquisition profile (see PROFILE_* below)
MSG_POWER = "power"        # {source, charging, percent, voltage_v, runtime_hours, method}
MSG_CONTROL = "control"    # {holder, holder_name, expires_in} - who may issue commands
MSG_BACKUP_META = "backup_meta"    # {name, bytes, sessions, rows}
MSG_BACKUP_CHUNK = "backup_chunk"  # {seq, data (base64), done}
MSG_CLIENTS = "clients"    # {clients: [{id, name}], max} - who is watching

# Client -> server command "cmd" values.
CMD_PAUSE = "pause"        # stop measuring (fan off), stay connected to sensor
CMD_RESUME = "resume"      # resume measuring
CMD_CLEAN = "clean"        # run the fan self-clean cycle, then resume
CMD_RESTART = "restart"    # force back to a known-good measuring+streaming state
CMD_RAW = "raw"            # {hex: "7e...7e"} send a raw frame, return response
CMD_LIST_FILES = "list_files"
CMD_GET_FILE = "get_file"  # {name, since_index?}
CMD_SET_PROFILE = "set_profile"  # {profile: {...}} switch acquisition profile
CMD_SHUTDOWN = "shutdown"  # park the sensor, close the CSV, power the host off
CMD_IDENTIFY = "identify"  # {name} announce a human-readable name for the roster
CMD_CLAIM = "claim"        # take the control lock (see CONTROL_TTL_SECONDS)
CMD_RELEASE = "release"    # give up the control lock
CMD_BACKUP = "backup"      # stream every recorded session as one zip archive

# Raw bytes per archive chunk before base64. Base64 inflates by a third, so this
# is roughly a 340 KB line - large enough that framing overhead is negligible,
# small enough to stay responsive and bounded in memory on both ends.
BACKUP_CHUNK_BYTES = 256 * 1024

# Commands that change what the sensor is doing, and so require the control lock.
# Everything not listed here (file reads, identify, claim/release) is always
# allowed, because watching must never require taking control away from someone.
MUTATING_COMMANDS = frozenset({
    CMD_PAUSE, CMD_RESUME, CMD_CLEAN, CMD_RESTART, CMD_RAW,
    CMD_SET_PROFILE, CMD_SHUTDOWN,
})

# How long a control claim survives without being used. Long enough that a user
# working through the options menu is not interrupted, short enough that someone
# who walks away with the lock does not strand everybody else.
CONTROL_TTL_SECONDS = 120.0

# Concurrent clients. Matched to what an ESP32-C6 can actually serve: lwIP
# defaults to 10 sockets, and reserving a couple for the listener and OTA leaves
# about this many. Capping the Pi at the same number keeps behaviour identical
# across both hosts rather than diverging when the hardware changes.
MAX_CLIENTS = 8

# Daemon acquisition states.
STATE_CHECKING = "checking"    # no sensor detected; polling for one
STATE_MEASURING = "measuring"  # streaming readings + writing CSV
STATE_PAUSED = "paused"        # sensor present, measurement stopped by user
STATE_CLEANING = "cleaning"    # fan self-clean in progress
STATE_SLEEPING = "sleeping"    # duty-cycle: sensor asleep between measurements
STATE_WARMUP = "warmup"        # duty-cycle: fan spun up, readings not yet trusted
STATE_SHUTDOWN = "shutting_down"  # host is powering off; safe to unplug shortly

# --- acquisition profiles ---------------------------------------------------
# MODE_CONTINUOUS: sensor measures non-stop and we record every `interval`
#   seconds. Raising the interval saves DATA, not power - the fan and laser stay
#   on and the sensor still draws ~55 mA.
# MODE_DUTY_CYCLE: Sensirion's low-power recommendation. Per cycle: wake, start
#   measurement, discard `warmup_s`, average `average_s` of readings into one
#   point, stop, sleep until the next `cycle_s`. Their reference figures are
#   60 s of measurement every 15 min -> ~3.5 mA average vs ~55 mA continuous.
MODE_CONTINUOUS = "continuous"
MODE_DUTY_CYCLE = "duty_cycle"

# Sensirion "Low-Power Operation of the SPS30" (app note v1, Aug 2020):
# 30 s minimum warm-up before the output is trustworthy, then average a further
# 30 s. Going below 8 s of warm-up is explicitly not recommended.
WARMUP_DEFAULT = 30.0
WARMUP_MIN = 8.0
AVERAGE_DEFAULT = 30.0
CYCLE_DEFAULT = 900.0  # 15 min, the interval their field data recommends

# Sleep/wake needs SPS30 firmware >= 2.0.
MIN_FIRMWARE_FOR_SLEEP = (2, 0)

DEFAULT_PROFILE = {
    "mode": MODE_CONTINUOUS,
    "interval": 1.0,     # continuous: seconds between recorded readings
    "cycle_s": CYCLE_DEFAULT,   # duty cycle: wake-to-wake period
    "warmup_s": WARMUP_DEFAULT,  # duty cycle: discarded fan spin-up/settling
    "average_s": AVERAGE_DEFAULT,  # duty cycle: averaged into one point
}


def normalize_profile(profile: dict | None) -> dict:
    """Coerce a client-supplied profile into a valid one, clamping bad values.

    Never raises: an out-of-range field falls back to its default rather than
    taking acquisition down, since this arrives straight off the wire.
    """
    src = profile or {}
    out = dict(DEFAULT_PROFILE)
    mode = src.get("mode")
    if mode in (MODE_CONTINUOUS, MODE_DUTY_CYCLE):
        out["mode"] = mode

    def positive(key: str) -> None:
        try:
            value = float(src[key])
        except (KeyError, TypeError, ValueError):
            return
        if value > 0:
            out[key] = value

    for key in ("interval", "cycle_s", "warmup_s", "average_s"):
        positive(key)
    out["warmup_s"] = max(WARMUP_MIN, out["warmup_s"])
    # A cycle has to be long enough to contain the work it describes, else the
    # sensor would never actually sleep.
    out["cycle_s"] = max(out["cycle_s"], out["warmup_s"] + out["average_s"])
    return out


def firmware_supports_sleep(firmware: str) -> bool:
    """True if [firmware] ("2.1") is >= the version that added Sleep/Wake."""
    try:
        parts = tuple(int(p) for p in str(firmware).split(".")[:2])
    except (TypeError, ValueError):
        return False
    return len(parts) == 2 and parts >= MIN_FIRMWARE_FOR_SLEEP


def encode(message: dict) -> bytes:
    """Serialize one message as a single JSONL frame (UTF-8, newline-terminated)."""
    return (json.dumps(message, separators=(",", ":")) + "\n").encode("utf-8")


def decode(line: bytes | str) -> dict:
    """Parse one JSONL frame."""
    if isinstance(line, bytes):
        line = line.decode("utf-8")
    return json.loads(line)


def iter_messages(buffer: bytearray):
    """Yield complete decoded messages from a growing byte buffer.

    Removes consumed bytes from ``buffer`` in place; leaves any partial trailing
    line for the next call. Use this on both ends to frame the TCP stream.
    """
    while True:
        nl = buffer.find(b"\n")
        if nl < 0:
            return
        line = bytes(buffer[:nl])
        del buffer[: nl + 1]
        if line.strip():
            yield decode(line)
