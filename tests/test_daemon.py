"""Hardware-free tests for the SPS30 acquisition daemon.

The FakeSerial from ``conftest`` replays real captured SPS30 responses (see
``tests/vectors.py``), so the full driver stack runs with zero hardware while we
drive the daemon's FSM one ``step()`` at a time. Intervals are made no-ops so
there are no real sleeps and every test is fast and deterministic.
"""
import csv
import socket
import time
from datetime import datetime

import pytest

import sps30
import wire
from sps30_daemon import (
    CLIENT_QUEUE_MAX,
    ClientHandler,
    Daemon,
    read_file_rows,
    scan_files,
)
from tests import vectors
from tests.conftest import FakeSerial

# A complete response map: probe (info/serial/version), start, read, stop.
FULL_RESPONSES = dict(
    [
        vectors.PRODUCT_TYPE,
        vectors.SERIAL_NUMBER,
        vectors.VERSION,
        vectors.START,
        vectors.READ,
        vectors.STOP,
    ]
)

# Everything needed to detect + start, but no READ answer -> reads time out.
NO_READ_RESPONSES = dict(
    [
        vectors.PRODUCT_TYPE,
        vectors.SERIAL_NUMBER,
        vectors.VERSION,
        vectors.START,
        vectors.STOP,
    ]
)

NOOP_SLEEP = lambda _s: None


def make_daemon(monkeypatch, tmp_path, responses=FULL_RESPONSES, **kw):
    """Build a Daemon whose sensor factory yields a FakeSerial-backed SPS30."""
    monkeypatch.setattr(
        sps30.serial, "Serial", lambda *a, **k: FakeSerial(responses)
    )
    daemon = Daemon(
        port="fake",
        tcp_port=0,
        host="127.0.0.1",
        csv_dir=tmp_path,
        interval=0.0,
        poll_interval=0.0,
        sensor_factory=sps30.SPS30,
        sleep=NOOP_SLEEP,
        **kw,
    )
    return daemon


# ---------------------------------------------------------------------------
# CSV / wire formatting
# ---------------------------------------------------------------------------
def test_format_index_zero_padded_width_7():
    assert wire.format_index(1) == "0000001"
    assert wire.format_index(1234567) == "1234567"
    assert len(wire.format_index(5)) == wire.INDEX_WIDTH == 7


def test_csv_filename_pattern():
    dt = datetime(2026, 7, 17, 9, 30, 5, tzinfo=wire.EASTERN)
    assert wire.csv_filename(dt) == "SPS30-20260717_093005.csv"


def test_measurement_row_columns_and_eastern_timestamp():
    m = sps30.Measurement(*range(10))
    when = datetime(2026, 1, 15, 12, 0, 0, tzinfo=wire.EASTERN)  # winter -> EST
    row = wire.measurement_row(7, when, m)
    assert list(row.keys()) == wire.CSV_COLUMNS
    assert row["index"] == "0000007"
    # ISO-8601 Eastern, seconds precision, with the -05:00 EST offset.
    assert row["timestamp"] == "2026-01-15T12:00:00-05:00"
    assert row["pm1_0"] == 0 and row["typical_size"] == 9


# ---------------------------------------------------------------------------
# FSM transitions
# ---------------------------------------------------------------------------
def test_checking_to_measuring_transition(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    assert daemon._state == wire.STATE_CHECKING

    daemon.step()  # probe + start -> measuring

    assert daemon._state == wire.STATE_MEASURING
    assert daemon._info["serial"] == vectors.EXPECTED_SERIAL
    assert daemon._info["product"] == vectors.EXPECTED_PRODUCT
    assert daemon._info["firmware"] == vectors.EXPECTED_FIRMWARE
    # A fresh CSV exists with just the header.
    files = list(tmp_path.glob("SPS30-*.csv"))
    assert len(files) == 1
    assert files[0].read_text().splitlines() == [",".join(wire.CSV_COLUMNS)]


def test_begin_measuring_stops_before_start(monkeypatch, tmp_path):
    # A prior unclean shutdown (or systemd restart while running) can leave the
    # sensor measuring, so start_measurement would return 0x43. The daemon must
    # send a defensive Stop before Start so startup is deterministic.
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.step()  # detect + begin measuring

    written = daemon._sensor._ser.written
    stop = bytes.fromhex(vectors.STOP[0])
    start = bytes.fromhex(vectors.START[0])
    assert stop in written and start in written
    assert written.index(stop) < written.index(start)


def test_reading_is_written_and_flushed(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.step()  # -> measuring
    daemon.step()  # one read -> one row written

    assert daemon._index == 1
    csv_file = next(tmp_path.glob("SPS30-*.csv"))
    lines = csv_file.read_text().splitlines()
    assert lines[0] == ",".join(wire.CSV_COLUMNS)
    assert len(lines) == 2  # header + one data row (already flushed to disk)
    data = lines[1].split(",")
    assert data[0] == "0000001"
    # Columns: index, timestamp, pm1_0, pm2_5, ...  -> data[2] is pm1_0.
    assert float(data[2]) == pytest.approx(
        vectors.EXPECTED_MEASUREMENT["pm1_0"], abs=1e-3
    )
    assert float(data[3]) == pytest.approx(
        vectors.EXPECTED_MEASUREMENT["pm2_5"], abs=1e-3
    )


def test_measurement_row_event_column():
    m = sps30.Measurement(*range(10))
    when = datetime(2026, 1, 15, 12, 0, 0, tzinfo=wire.EASTERN)
    assert wire.measurement_row(1, when, m)["event"] == ""  # blank by default
    flagged = wire.measurement_row(1, when, m, "startup")
    assert flagged["event"] == "startup"
    assert list(flagged.keys()) == wire.CSV_COLUMNS  # event is a real column


def test_startup_spike_is_flagged_then_clears(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.interval = 5.0  # settle window = round(SETTLE_SECONDS/5) = 4 rows
    daemon.step()  # -> measuring: flags the first 4 readings "startup"
    for _ in range(6):
        daemon.step()  # 6 readings

    rows = [
        line.split(",")
        for line in next(tmp_path.glob("SPS30-*.csv")).read_text().splitlines()[1:]
    ]
    events = [r[-1] for r in rows]  # "event" is the last column
    assert events[:4] == ["startup"] * 4
    assert all(e == "" for e in events[4:])  # settled -> blank


def test_auto_recover_returns_to_checking_after_repeated_read_failures(monkeypatch, tmp_path):
    from sps30_daemon import READ_FAILURES_BEFORE_LOSS

    daemon = make_daemon(monkeypatch, tmp_path, responses=NO_READ_RESPONSES)
    daemon.step()  # detect + start -> measuring
    assert daemon._state == wire.STATE_MEASURING

    # A sensor that has genuinely gone away stays silent, so the loss is still
    # detected - just not on the strength of a single missed response.
    for _ in range(READ_FAILURES_BEFORE_LOSS):
        daemon.step()

    assert daemon._state == wire.STATE_CHECKING
    assert daemon._sensor is None


def test_one_missed_read_does_not_end_the_session(monkeypatch, tmp_path):
    """Regression: days of history vanished from the plot.

    A single timed-out read used to tear the session down, which closed the CSV
    and opened a new one on recovery. The client backfills the *current* file,
    so every glitch silently truncated its history back to that moment - three
    times in one day on a marginal supply.
    """
    from sps30_daemon import READ_FAILURES_BEFORE_LOSS

    daemon = make_daemon(monkeypatch, tmp_path, responses=NO_READ_RESPONSES)
    daemon.step()
    csv_before = daemon._csv_name

    for _ in range(READ_FAILURES_BEFORE_LOSS - 1):
        daemon.step()

    assert daemon._state == wire.STATE_MEASURING, "gave up too early"
    assert daemon._csv_name == csv_before, "the recording must not be replaced"


def test_recovery_continues_the_same_recording(monkeypatch, tmp_path):
    """After a dropout the readings belong in the file they were already going
    to: same sensor, same settings, same intent - one recording with a gap."""
    from sps30_daemon import READ_FAILURES_BEFORE_LOSS

    daemon = make_daemon(monkeypatch, tmp_path, responses=FULL_RESPONSES)
    daemon.step()
    csv_before = daemon._csv_name
    assert csv_before

    # Force a loss, then let it find the sensor again.
    daemon._handle_sensor_loss("simulated dropout")
    assert daemon._state == wire.STATE_CHECKING
    daemon.step()  # re-detect

    assert daemon._state == wire.STATE_MEASURING
    assert daemon._csv_name == csv_before, (
        f"recovery started a new file ({daemon._csv_name}) instead of continuing {csv_before}"
    )
    assert len(list(tmp_path.glob("SPS30-*.csv"))) == 1


def test_a_user_restart_does_start_a_new_recording(monkeypatch, tmp_path):
    """The continuation is only for dropouts - an explicit restart still means
    a fresh session, which is what "all" and the session views key off."""
    daemon = make_daemon(monkeypatch, tmp_path, responses=FULL_RESPONSES)
    daemon.step()
    daemon.step()  # record a reading, advancing the index
    first = daemon._csv_name
    assert daemon._index > 0

    daemon.enqueue_command(wire.CMD_RESTART, {}, None)
    daemon._drain_commands()

    assert daemon._csv_name != first, "restart must open a new recording"
    assert daemon._index == 0, "a new session restarts the index"
    assert len(list(tmp_path.glob("SPS30-*.csv"))) == 2


def test_sessions_started_in_the_same_second_do_not_overwrite(monkeypatch, tmp_path):
    """CSV names are second-resolution; a rapid restart must not truncate the
    previous recording."""
    daemon = make_daemon(monkeypatch, tmp_path, responses=FULL_RESPONSES)
    daemon.step()
    first = daemon._csv_name
    daemon._open_new_csv()
    assert daemon._csv_name != first
    assert len(list(tmp_path.glob("SPS30-*.csv"))) == 2


# ---------------------------------------------------------------------------
# Command queue
# ---------------------------------------------------------------------------
def test_pause_and_resume_via_command_queue(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.step()  # -> measuring
    daemon.step()  # write one row -> index advances to 1
    assert daemon._index == 1

    daemon.enqueue_command(wire.CMD_PAUSE, {})
    daemon.step()
    assert daemon._state == wire.STATE_PAUSED
    assert daemon._csv_file is None  # CSV closed while paused

    daemon.enqueue_command(wire.CMD_RESUME, {})
    daemon.step()  # resume rolls a fresh CSV, then reads one row
    assert daemon._state == wire.STATE_MEASURING
    assert daemon._csv_file is not None
    # The CSV was rolled (index reset), so this step wrote row #1 again - had it
    # merely appended to the old file the index would now be 2.
    assert daemon._index == 1
    csv_file = next(tmp_path.glob("SPS30-*.csv"))
    assert len(csv_file.read_text().splitlines()) == 2  # header + one row


def test_fsync_every_is_configurable_and_clamped(monkeypatch, tmp_path):
    from sps30_daemon import FSYNC_EVERY, build_parser

    # Explicit value is honored; values < 1 are clamped up to 1.
    d = make_daemon(monkeypatch, tmp_path, fsync_every=5)
    assert d.fsync_every == 5
    d0 = make_daemon(monkeypatch, tmp_path, fsync_every=0)
    assert d0.fsync_every == 1

    # CLI exposes --fsync-every and defaults to the module constant.
    args = build_parser().parse_args([])
    assert args.fsync_every == FSYNC_EVERY
    args = build_parser().parse_args(["--fsync-every", "7"])
    assert args.fsync_every == 7


def test_fsync_runs_on_configured_interval(monkeypatch, tmp_path):
    import sps30_daemon

    calls = []
    monkeypatch.setattr(sps30_daemon.os, "fsync", lambda fd: calls.append(fd))
    daemon = make_daemon(monkeypatch, tmp_path, fsync_every=2)
    daemon.step()  # -> measuring (opens CSV; no data-row fsync yet)
    before = len(calls)
    daemon.step()  # row 1 -> flush only, no fsync
    assert len(calls) == before
    daemon.step()  # row 2 -> reaches the interval -> fsync
    assert len(calls) == before + 1


def test_restart_forces_measuring_state(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.step()  # -> measuring
    daemon.enqueue_command(wire.CMD_RESTART, {})
    daemon.step()
    assert daemon._state == wire.STATE_MEASURING
    assert daemon._sensor is not None


def test_raw_command_returns_response_to_client(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.step()  # -> measuring

    class FakeClient:
        def __init__(self):
            self.sent = []

        def send(self, msg, lossless=False):
            self.sent.append(msg)

    client = FakeClient()
    # Ask for the product-type frame; the FakeSerial answers with its response.
    daemon.enqueue_command(wire.CMD_RAW, {"hex": vectors.PRODUCT_TYPE[0]}, client)
    daemon.step()

    assert len(client.sent) == 1
    msg = client.sent[0]
    assert msg["type"] == wire.MSG_RAW
    assert msg["request"] == vectors.PRODUCT_TYPE[0]
    assert msg["response"] == vectors.PRODUCT_TYPE[1]


# ---------------------------------------------------------------------------
# File serving helpers (CMD_GET_FILE / CMD_LIST_FILES)
# ---------------------------------------------------------------------------
def _write_csv(path, n_rows):
    lines = [",".join(wire.CSV_COLUMNS)]
    for i in range(1, n_rows + 1):
        vals = [wire.format_index(i), "2026-01-15T12:00:0%d-05:00" % (i % 10)]
        vals += ["%.2f" % (i + j) for j in range(len(wire.FIELDS))]
        lines.append(",".join(vals))
    path.write_text("\n".join(lines) + "\n")


def test_get_file_since_index_selects_only_newer_rows(tmp_path):
    path = tmp_path / "SPS30-20260115_120000.csv"
    _write_csv(path, 5)

    columns, rows = read_file_rows(path, since_index=2)

    assert columns == wire.CSV_COLUMNS
    # Only indices strictly greater than 2 -> 3, 4, 5.
    assert [r[0] for r in rows] == ["0000003", "0000004", "0000005"]


def test_get_file_without_since_returns_all_rows(tmp_path):
    path = tmp_path / "SPS30-20260115_120000.csv"
    _write_csv(path, 4)
    columns, rows = read_file_rows(path, since_index=None)
    assert len(rows) == 4


def test_scan_files_reports_rows_and_bytes(tmp_path):
    _write_csv(tmp_path / "SPS30-20260115_120000.csv", 3)
    _write_csv(tmp_path / "SPS30-20260116_120000.csv", 7)
    files = scan_files(tmp_path)
    assert [f["name"] for f in files] == [
        "SPS30-20260116_120000.csv",
        "SPS30-20260115_120000.csv",
    ]
    by_name = {f["name"]: f for f in files}
    assert by_name["SPS30-20260115_120000.csv"]["rows"] == 3
    assert by_name["SPS30-20260116_120000.csv"]["rows"] == 7
    assert all(f["bytes"] > 0 for f in files)
    # start timestamp comes from the first data row (cheap first-line parse)
    assert by_name["SPS30-20260115_120000.csv"]["start"].endswith("-05:00")


def test_scan_files_counts_row_without_trailing_newline(tmp_path):
    # The cheap buffered newline count must still include a final row that has
    # no trailing newline, and a header-only / empty file must report 0 rows.
    path = tmp_path / "SPS30-20260115_120000.csv"
    _write_csv(path, 4)
    text = path.read_text().rstrip("\n")  # drop the trailing newline
    path.write_text(text)
    files = scan_files(tmp_path)
    assert files[0]["rows"] == 4

    header_only = tmp_path / "SPS30-20260116_120000.csv"
    header_only.write_text(",".join(wire.CSV_COLUMNS) + "\n")
    empty = tmp_path / "SPS30-20260117_120000.csv"
    empty.write_text("")
    by_name = {f["name"]: f for f in scan_files(tmp_path)}
    assert by_name["SPS30-20260116_120000.csv"]["rows"] == 0
    assert by_name["SPS30-20260117_120000.csv"]["rows"] == 0


# ---------------------------------------------------------------------------
# End-to-end smoke: real TCP server, a client receives HELLO + READING
# ---------------------------------------------------------------------------
def test_tcp_client_receives_hello_and_reading(monkeypatch, tmp_path):
    # Use tiny real sleeps here so the acquisition thread actually cycles.
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon._sleep = lambda _s: time.sleep(0.005)
    daemon.start()
    try:
        sock = socket.create_connection(("127.0.0.1", daemon.tcp_port), timeout=5)
        sock.settimeout(5)
        buffer = bytearray()
        seen = {}
        deadline = time.time() + 5
        while time.time() < deadline and not (
            wire.MSG_HELLO in seen and wire.MSG_READING in seen
        ):
            data = sock.recv(4096)
            if not data:
                break
            buffer.extend(data)
            for msg in wire.iter_messages(buffer):
                seen[msg["type"]] = msg
        sock.close()
    finally:
        daemon.stop()

    assert wire.MSG_HELLO in seen
    assert wire.MSG_READING in seen
    reading = seen[wire.MSG_READING]
    assert reading["index"] == "0000001" or int(reading["index"]) >= 1
    for field in wire.FIELDS:
        assert field in reading


# --- per-client fan-out queue: lossy vs lossless -----------------------------
def _bare_handler():
    """A ClientHandler with a dummy socket; threads are created but never
    started, so we can exercise the queue policy directly."""
    return ClientHandler(sock=object(), addr=("test", 0), daemon=object())


def test_live_broadcasts_are_lossy_past_the_cap():
    ch = _bare_handler()
    for i in range(CLIENT_QUEUE_MAX + 50):
        ch.send({"type": wire.MSG_READING, "i": i})  # lossy (default)
    # Never grows past the cap; the oldest lossy messages are dropped.
    assert len(ch._outq) == CLIENT_QUEUE_MAX
    # The survivors are the newest ones (oldest dropped).
    kept = [m["i"] for m, _lossless in ch._outq]
    assert kept[-1] == CLIENT_QUEUE_MAX + 49
    assert min(kept) == 50


def test_file_transfer_chunks_are_never_dropped():
    ch = _bare_handler()
    # Saturate with lossy live readings, then enqueue lossless file chunks. The
    # chunks preserve themselves by evicting lossy messages, keeping the total
    # bounded rather than dropping a chunk.
    for i in range(CLIENT_QUEUE_MAX):
        ch.send({"type": wire.MSG_READING, "i": i})
    for j in range(20):
        ch.send({"type": wire.MSG_FILE_DATA, "j": j}, lossless=True)
    kept = [m for m, lossless in ch._outq if lossless]
    assert len(kept) == 20                     # no chunk dropped
    assert len(ch._outq) == CLIENT_QUEUE_MAX    # total stayed bounded (lossy evicted)

    # Even a flood of further lossy sends cannot evict the chunks.
    for i in range(CLIENT_QUEUE_MAX):
        ch.send({"type": wire.MSG_READING, "i": 10_000 + i})
    kept = [m for m, lossless in ch._outq if lossless]
    assert len(kept) == 20
    assert all(m["type"] == wire.MSG_FILE_DATA for m in kept)


def test_all_lossless_backlog_grows_past_cap_rather_than_dropping():
    ch = _bare_handler()
    # When nothing droppable remains, the queue exceeds the soft cap instead of
    # corrupting a transfer.
    for j in range(CLIENT_QUEUE_MAX + 20):
        ch.send({"type": wire.MSG_FILE_DATA, "j": j}, lossless=True)
    assert len(ch._outq) == CLIENT_QUEUE_MAX + 20


# ---------------------------------------------------------------------------
# Acquisition profiles / low-power duty cycling
# ---------------------------------------------------------------------------
DUTY_RESPONSES = dict(
    [
        vectors.PRODUCT_TYPE,
        vectors.SERIAL_NUMBER,
        vectors.VERSION,
        vectors.START,
        vectors.READ,
        vectors.STOP,
        vectors.SLEEP,
        vectors.WAKE,
    ]
)


def test_normalize_profile_defaults_and_clamps():
    assert wire.normalize_profile(None) == wire.DEFAULT_PROFILE
    assert wire.normalize_profile({"mode": "nonsense"})["mode"] == wire.MODE_CONTINUOUS
    # Sensirion say never warm up for less than 8s, whatever the caller asks.
    assert wire.normalize_profile({"warmup_s": 1.0})["warmup_s"] == wire.WARMUP_MIN
    # Negative / non-numeric fields fall back rather than taking acquisition down.
    assert wire.normalize_profile({"interval": -5})["interval"] == 1.0
    assert wire.normalize_profile({"interval": "abc"})["interval"] == 1.0
    # A cycle must be long enough to contain the work it describes.
    tight = wire.normalize_profile(
        {"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 5, "warmup_s": 30, "average_s": 30}
    )
    assert tight["cycle_s"] == 60.0


def test_firmware_gate_for_sleep():
    assert wire.firmware_supports_sleep("2.1")
    assert wire.firmware_supports_sleep("2.0")
    assert not wire.firmware_supports_sleep("1.9")
    assert not wire.firmware_supports_sleep("")
    assert not wire.firmware_supports_sleep(None)


def test_wake_sends_ff_pulse_before_the_wake_frame(fake_serial_factory):
    """In Sleep-Mode the UART is off; a bare 0xFF must precede the Wake frame."""
    sensor, fake = fake_serial_factory(DUTY_RESPONSES)
    sensor.wake()
    assert b"\xff" in fake.written, "no 0xFF wake pulse was sent"
    pulse = fake.written.index(b"\xff")
    frame = fake.written.index(bytes.fromhex(vectors.WAKE[0]))
    assert pulse < frame, "the 0xFF pulse must come before the Wake command"


def test_wake_retries_then_gives_up(fake_serial_factory):
    # No WAKE response mapped -> every attempt times out.
    sensor, fake = fake_serial_factory({})
    with pytest.raises(sps30.SPS30Error, match="wake failed after 2 attempts"):
        sensor.wake(attempts=2)
    assert fake.written.count(b"\xff") == 2


def test_duty_cycle_averages_and_sleeps_between_measurements(monkeypatch, tmp_path):
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        profile={
            "mode": wire.MODE_DUTY_CYCLE,
            "cycle_s": 900.0, "warmup_s": 30.0, "average_s": 30.0,
        },
    )
    daemon.step()  # checking -> measuring
    assert daemon._state == wire.STATE_MEASURING
    daemon.step()  # one full duty cycle

    sent = [w.hex() for w in daemon._sensor._ser.written]
    assert vectors.SLEEP[0] in sent, "sensor was never put to sleep"
    assert vectors.STOP[0] in sent, "measurement was never stopped"
    # Exactly one averaged point recorded for the cycle, not one per read.
    rows = list(tmp_path.glob("SPS30-*.csv"))
    assert len(rows) == 1
    body = rows[0].read_text().strip().splitlines()
    assert len(body) == 2, f"expected header + one averaged row, got {len(body)}"
    assert daemon._state == wire.STATE_SLEEPING


def test_duty_cycle_wakes_before_the_next_cycle(monkeypatch, tmp_path):
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 60.0,
                 "warmup_s": 8.0, "average_s": 8.0},
    )
    daemon.step()
    daemon.step()
    assert daemon._asleep
    daemon._state = wire.STATE_MEASURING  # next cycle comes round
    daemon._sensor._ser.written.clear()
    daemon.step()
    sent = [w.hex() for w in daemon._sensor._ser.written]
    assert vectors.WAKE[0] in sent, "a sleeping sensor must be woken first"


def test_set_profile_is_refused_on_old_firmware(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path, responses=DUTY_RESPONSES)
    daemon.step()
    daemon._info["firmware"] = "1.9"
    client = _RecordingClient()
    daemon.enqueue_command(
        wire.CMD_SET_PROFILE, {"profile": {"mode": wire.MODE_DUTY_CYCLE}}, client
    )
    daemon._drain_commands()
    assert daemon.profile["mode"] == wire.MODE_CONTINUOUS
    assert any("firmware 2.0" in m.get("message", "") for m in client.sent)


def test_set_profile_switches_mode_and_announces(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path, responses=DUTY_RESPONSES)
    daemon.step()
    client = _RecordingClient()
    daemon.add_client(client)
    daemon.enqueue_command(
        wire.CMD_SET_PROFILE,
        {"profile": {"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 300.0}}, client,
    )
    daemon._drain_commands()
    assert daemon.profile["mode"] == wire.MODE_DUTY_CYCLE
    assert daemon.profile["cycle_s"] == 300.0
    assert any(m.get("type") == wire.MSG_PROFILE for m in client.sent)


def test_estimated_current_matches_the_app_note(monkeypatch, tmp_path):
    """Sensirion's worked example: 60s measuring per 15min -> ~3.5 mA average."""
    daemon = make_daemon(monkeypatch, tmp_path, responses=DUTY_RESPONSES)
    assert daemon.estimated_average_ma() == pytest.approx(55.0)
    daemon.profile = wire.normalize_profile(
        {"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 900.0,
         "warmup_s": 30.0, "average_s": 30.0}
    )
    assert daemon.estimated_average_ma() == pytest.approx(3.7, abs=0.2)


def test_scheduled_clean_fires_after_a_week_of_run_time(monkeypatch, tmp_path):
    """The sensor's own auto-clean counter resets on power-down, so duty cycling
    would never trigger it - the daemon has to drive it."""
    from sps30_daemon import CLEAN_AFTER_SECONDS

    daemon = make_daemon(
        monkeypatch, tmp_path, responses=dict(DUTY_RESPONSES, **dict([vectors.CLEAN])),
        profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 60.0,
                 "warmup_s": 8.0, "average_s": 8.0},
    )
    daemon.step()
    daemon._run_seconds = CLEAN_AFTER_SECONDS + 1
    daemon.step()
    sent = [w.hex() for w in daemon._sensor._ser.written]
    assert vectors.CLEAN[0] in sent, "no fan clean was triggered"
    assert daemon._run_seconds == 0.0, "run-time accrual should reset after a clean"


def test_run_time_accrual_persists_across_restarts(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon._run_seconds = 12345.0
    daemon._save_state()
    revived = make_daemon(monkeypatch, tmp_path)
    assert revived._run_seconds == 12345.0


def test_shutdown_parks_the_sensor_and_closes_the_csv(monkeypatch, tmp_path):
    called = []
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        shutdown_hook=lambda: called.append("poweroff"),
    )
    daemon.step()
    daemon.enqueue_command(wire.CMD_SHUTDOWN, {}, None)
    daemon._drain_commands()
    assert called == ["poweroff"]
    assert daemon._state == wire.STATE_SHUTDOWN
    assert daemon._stop.is_set()
    assert daemon._csv_file is None, "CSV must be closed before power is cut"


def test_hello_carries_profile_and_power(monkeypatch, tmp_path):
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        power_reader=lambda: {"source": "mains", "method": "none"},
    )
    daemon.step()
    hello = daemon.hello_message()
    assert hello["profile"]["mode"] == wire.MODE_CONTINUOUS
    assert hello["power"]["source"] == "mains"
    assert hello["supports_sleep"] is True


class _RecordingClient:
    """Stands in for a ClientHandler, capturing what the daemon sends it."""

    _next_id = 100

    def __init__(self, name="test-client"):
        self.sent = []
        _RecordingClient._next_id += 1
        self.client_id = _RecordingClient._next_id
        self.name = name

    def send(self, message, lossless=False):
        self.sent.append(message)


def test_every_wire_command_is_dispatchable(monkeypatch, tmp_path):
    """Guards the gap between `wire.CMD_*` and ClientHandler._dispatch.

    Adding a command to wire.py and handling it on the acquisition thread is not
    enough - the client handler validates against its own allowlist first, and a
    command missing from it is rejected as "unknown cmd" before it ever reaches
    the daemon. That is exactly how set_profile shipped broken to hardware once.
    """
    daemon = make_daemon(monkeypatch, tmp_path)
    commands = [
        getattr(wire, name) for name in dir(wire)
        if name.startswith("CMD_") and isinstance(getattr(wire, name), str)
    ]
    assert commands, "no CMD_* constants found in wire"

    for cmd in commands:
        handler = ClientHandler.__new__(ClientHandler)
        handler.daemon = daemon
        handler.client_id = 1
        handler.name = "dispatch-test"
        sent = []
        handler.send = lambda m, lossless=False: sent.append(m)
        handler._dispatch({"cmd": cmd, "name": "nope.csv"})
        unknown = [m for m in sent if "unknown cmd" in str(m.get("message", ""))]
        assert not unknown, f"{cmd!r} is not routed by ClientHandler._dispatch"


def test_duty_cycle_survives_a_sensor_that_is_already_measuring(monkeypatch, tmp_path):
    """Regression: the first cycle after switching profiles hit 0x43.

    _cmd_set_profile calls _begin_measuring, which starts measurement; the cycle
    that follows then called start_measurement again and the real sensor answered
    "command not allowed in current state". FakeSerial replays canned responses
    regardless of mode, so only `enforce_state=True` reproduces it.
    """
    monkeypatch.setattr(
        sps30.serial, "Serial",
        lambda *a, **k: FakeSerial(DUTY_RESPONSES, enforce_state=True),
    )
    daemon = Daemon(
        port="fake", tcp_port=0, host="127.0.0.1", csv_dir=tmp_path,
        poll_interval=0.0, sensor_factory=sps30.SPS30, sleep=NOOP_SLEEP,
        profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 60.0,
                 "warmup_s": 8.0, "average_s": 8.0},
    )
    daemon.step()  # checking -> measuring (sensor is now measuring)
    assert daemon._state == wire.STATE_MEASURING
    daemon.step()  # first duty cycle, with the sensor already started
    assert daemon._state == wire.STATE_SLEEPING, (
        f"first cycle should reach sleep, got {daemon._state}: {daemon._detail}"
    )
    assert daemon._asleep


def test_profile_change_pushes_the_new_current_estimate(monkeypatch, tmp_path):
    """The modelled draw is the reason to switch modes, so it must not lag.

    Power telemetry is otherwise only polled once a minute; without an explicit
    push the options screen kept showing 55 mA after selecting low power.
    """
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        power_reader=lambda: {"source": "mains", "method": "none"},
    )
    daemon.step()
    client = _RecordingClient()
    daemon.add_client(client)
    daemon.enqueue_command(
        wire.CMD_SET_PROFILE,
        {"profile": {"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 900.0,
                     "warmup_s": 30.0, "average_s": 30.0}},
        client,
    )
    daemon._drain_commands()
    powers = [m for m in client.sent if m.get("type") == wire.MSG_POWER]
    assert powers, "no power update was pushed with the profile change"
    assert powers[-1]["power"]["average_ma"] == pytest.approx(3.7, abs=0.2)


def test_switching_to_continuous_mid_cycle_does_not_strand_the_sensor(monkeypatch, tmp_path):
    """Regression: found on hardware. A duty cycle spans minutes, so a profile
    change lands *inside* one. The in-flight cycle used to carry on regardless,
    park the sensor in Sleep-Mode and leave the FSM in `sleeping` - a state only
    a duty cycle ever leaves, so continuous mode then streamed nothing at all.
    """
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 900.0,
                 "warmup_s": 30.0, "average_s": 30.0},
    )
    daemon.step()  # -> measuring

    # Switch to continuous during the cycle's warm-up sleep.
    switched = {"done": False}
    original = daemon._sleep

    def sleep_then_switch(seconds):
        if not switched["done"] and daemon._state == wire.STATE_WARMUP:
            switched["done"] = True
            daemon.enqueue_command(
                wire.CMD_SET_PROFILE, {"profile": {"mode": wire.MODE_CONTINUOUS}}, None
            )
        original(seconds)

    daemon._sleep = sleep_then_switch
    daemon.step()

    assert switched["done"], "the test never injected the profile change"
    assert not daemon.duty_cycling
    assert not daemon._asleep, "sensor must not be left asleep in continuous mode"
    assert daemon._state == wire.STATE_MEASURING, (
        f"stranded in {daemon._state}: {daemon._detail}"
    )

    # And it must actually stream again.
    daemon._sleep = original
    before = daemon._index
    daemon.step()
    assert daemon._index > before, "no readings after switching back to continuous"


def test_sleeping_state_recovers_if_the_profile_is_continuous(monkeypatch, tmp_path):
    """Belt and braces for the same dead end, whatever route reached it."""
    daemon = make_daemon(monkeypatch, tmp_path, responses=DUTY_RESPONSES)
    daemon.step()
    daemon._set_state(wire.STATE_SLEEPING, "stale duty-cycle state")
    daemon.step()
    assert daemon._state == wire.STATE_MEASURING


def test_detection_wakes_a_sensor_left_asleep(monkeypatch, tmp_path):
    """Regression: found on hardware, and it was a device-bricking one.

    Sleep-Mode disables the SPS30's UART, so a sensor left asleep by a previous
    duty-cycle run answers nothing on probe. Detection used to read that as "no
    sensor" and retry forever - meaning any daemon restart while in low-power
    mode lost the sensor permanently until someone power-cycled it.
    """
    class AsleepUntilWoken(FakeSerial):
        def __init__(self, responses):
            super().__init__(responses)
            self.awake = False

        def write(self, data):
            self.written.append(bytes(data))
            if bytes(data) == b"\xff":
                self.awake = True
                return 1
            if not self.awake:
                return len(data)  # silent: UART is off in Sleep-Mode
            if bytes(data) in self.responses:
                self._rx.extend(self.responses[bytes(data)])
            return len(data)

    monkeypatch.setattr(
        sps30.serial, "Serial", lambda *a, **k: AsleepUntilWoken(DUTY_RESPONSES)
    )
    daemon = Daemon(
        port="fake", tcp_port=0, host="127.0.0.1", csv_dir=tmp_path,
        poll_interval=0.0, sensor_factory=sps30.SPS30, sleep=NOOP_SLEEP,
    )
    daemon.step()
    assert daemon._state == wire.STATE_MEASURING, (
        f"a sleeping sensor was not detected: {daemon._detail}"
    )
    assert daemon._info["serial"] == vectors.EXPECTED_SERIAL
    assert not daemon._asleep


def test_duty_cycle_points_are_not_flagged_as_settling(monkeypatch, tmp_path):
    """Regression: averaged points never reached the plot.

    _begin_measuring flags SETTLE_SECONDS worth of *rows*. In continuous mode
    that is 20 rows = 20 seconds; in duty cycle it is 20 rows = 20 *cycles* = 5
    hours at the recommended 15-minute period, and the client hides flagged
    points by default - so low-power mode looked like it recorded nothing.
    """
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 60.0,
                 "warmup_s": 8.0, "average_s": 8.0},
    )
    daemon.step()  # -> measuring
    daemon.step()  # one cycle -> one averaged row

    csvs = list(tmp_path.glob("SPS30-*.csv"))
    assert len(csvs) == 1
    rows = list(csv.DictReader(csvs[0].read_text().splitlines()))
    assert rows, "no averaged row was written"
    assert rows[0]["event"] == "", (
        "averaged point was flagged %r and would be hidden" % (rows[0]["event"],)
    )


def test_continuous_still_flags_its_startup_spike(monkeypatch, tmp_path):
    """The duty-cycle exemption must not disarm continuous mode's spike flag."""
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.step()
    daemon.step()
    csvs = list(tmp_path.glob("SPS30-*.csv"))
    rows = list(csv.DictReader(csvs[0].read_text().splitlines()))
    assert rows[0]["event"] == "startup"


def test_duty_cycle_states_carry_a_phase_duration(monkeypatch, tmp_path):
    """The banner counts down locally, so each phase must publish its length."""
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 60.0,
                 "warmup_s": 8.0, "average_s": 8.0},
    )
    client = _RecordingClient()
    daemon.add_client(client)
    daemon.step()
    daemon.step()

    phases = {
        m["state"]: m.get("seconds")
        for m in client.sent if m.get("type") == wire.MSG_STATE
    }
    assert phases.get(wire.STATE_WARMUP) == 8.0
    assert phases.get(wire.STATE_SLEEPING) is not None
    assert phases[wire.STATE_SLEEPING] > 0


# ---------------------------------------------------------------------------
# Multi-user control arbitration
# ---------------------------------------------------------------------------
def _handler(daemon, name):
    """A ClientHandler with just the fields arbitration touches."""
    h = ClientHandler.__new__(ClientHandler)
    h.daemon = daemon
    h.name = name
    h.addr = ("test", 0)
    h.sent = []
    h.send = lambda m, lossless=False: h.sent.append(m)
    with ClientHandler._id_lock:
        ClientHandler._next_id += 1
        h.client_id = ClientHandler._next_id
    return h


def test_a_lone_user_never_meets_the_control_lock(monkeypatch, tmp_path):
    """Uncontested control is granted silently - one user must not have to know
    the mechanism exists."""
    daemon = make_daemon(monkeypatch, tmp_path)
    alice = _handler(daemon, "alice")
    assert daemon.may_control(alice)
    assert daemon.may_control(alice)  # and stays granted


def test_second_user_is_refused_while_the_first_holds_control(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    assert daemon.may_control(alice)
    assert not daemon.may_control(bob)
    assert daemon.control_holder_name() == "alice"


def test_viewing_commands_never_require_control(monkeypatch, tmp_path):
    """Watching must never take control away from whoever is driving."""
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    daemon.may_control(alice)

    bob._dispatch({"cmd": wire.CMD_LIST_FILES})
    assert not any(m.get("needs_control") for m in bob.sent)

    bob._dispatch({"cmd": wire.CMD_PAUSE})
    denied = [m for m in bob.sent if m.get("needs_control")]
    assert denied, "a mutating command from a non-holder must be refused"
    assert "alice" in denied[0]["message"]


def test_control_expires_so_nobody_is_stranded(monkeypatch, tmp_path):
    """A holder who walks away must not lock the sensor forever."""
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    assert daemon.may_control(alice)
    assert not daemon.may_control(bob)

    clock = [time.monotonic() + wire.CONTROL_TTL_SECONDS + 1]
    monkeypatch.setattr(time, "monotonic", lambda: clock[0])
    assert daemon.may_control(bob), "the lock should have expired"
    assert daemon.control_holder_name() == "bob"


def test_using_control_refreshes_the_lease(monkeypatch, tmp_path):
    """Someone actively working must not be timed out mid-task."""
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    now = [1000.0]
    monkeypatch.setattr(time, "monotonic", lambda: now[0])
    assert daemon.may_control(alice)
    for _ in range(5):
        now[0] += wire.CONTROL_TTL_SECONDS * 0.5
        assert daemon.may_control(alice)
    assert not daemon.may_control(bob)


def test_claim_takes_over_from_an_absent_holder(monkeypatch, tmp_path):
    """Taking over is allowed on purpose: a lock only its owner can free is a
    lock that strands people when they walk off."""
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    daemon.may_control(alice)
    bob._dispatch({"cmd": wire.CMD_CLAIM})
    assert daemon.control_holder_name() == "bob"
    assert daemon.may_control(bob)
    assert not daemon.may_control(alice)


def test_release_frees_control_for_others(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    daemon.may_control(alice)
    alice._dispatch({"cmd": wire.CMD_RELEASE})
    assert daemon.control_holder_name() == "nobody"
    assert daemon.may_control(bob)


def test_release_by_a_non_holder_does_nothing(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    daemon.may_control(alice)
    bob._dispatch({"cmd": wire.CMD_RELEASE})
    assert daemon.control_holder_name() == "alice"


def test_disconnecting_holder_frees_control(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    daemon.add_client(alice)
    daemon.may_control(alice)
    daemon.remove_client(alice)
    assert daemon.control_holder_name() == "nobody"
    assert daemon.may_control(bob)


def test_identify_names_the_client_in_the_roster(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    alice = _handler(daemon, "192.168.1.5")
    daemon.add_client(alice)
    alice._dispatch({"cmd": wire.CMD_IDENTIFY, "name": "Alice's phone"})
    roster = daemon.clients_message()
    assert roster["clients"] == [{"id": alice.client_id, "name": "Alice's phone"}]
    assert roster["max"] == wire.MAX_CLIENTS


def test_control_message_reports_the_holder_and_remaining_lease(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    alice = _handler(daemon, "alice")
    assert daemon.control_message()["holder"] is None
    daemon.may_control(alice)
    msg = daemon.control_message()
    assert msg["holder"] == alice.client_id
    assert msg["holder_name"] == "alice"
    assert 0 < msg["expires_in"] <= wire.CONTROL_TTL_SECONDS


# ---------------------------------------------------------------------------
# Session backup
# ---------------------------------------------------------------------------
def test_backup_streams_every_session_as_one_archive(monkeypatch, tmp_path):
    """Taking a copy must never need the control lock, and must arrive whole."""
    import base64, io, zipfile

    daemon = make_daemon(monkeypatch, tmp_path)
    for n in (1, 2, 3):
        (tmp_path / f"SPS30-2026073{n}_120000.csv").write_text(
            "index,timestamp,pm2_5,event\n" + "".join(f"{i},t{i},1.5,\n" for i in range(50))
        )

    client = _handler(daemon, "someone")
    client._dispatch({"cmd": wire.CMD_BACKUP})

    meta = [m for m in client.sent if m.get("type") == wire.MSG_BACKUP_META]
    assert meta, "no archive metadata was sent"
    assert meta[0]["sessions"] == 3
    assert meta[0]["name"].endswith(".zip")
    assert meta[0]["bytes"] > 0

    chunks = [m for m in client.sent if m.get("type") == wire.MSG_BACKUP_CHUNK]
    assert chunks, "no archive data was sent"
    assert chunks[-1]["done"], "the stream must end with an explicit done marker"
    # Sequence numbers have to be gapless or the client cannot detect truncation.
    assert [c["seq"] for c in chunks] == list(range(len(chunks)))

    blob = b"".join(base64.b64decode(c["data"]) for c in chunks if c["data"])
    assert len(blob) == meta[0]["bytes"], "declared size and delivered size disagree"

    with zipfile.ZipFile(io.BytesIO(blob)) as zf:
        assert sorted(zf.namelist()) == sorted(p.name for p in tmp_path.glob("SPS30-*.csv"))
        assert zf.read(zf.namelist()[0]).startswith(b"index,timestamp")


def test_backup_needs_no_control_claim(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    (tmp_path / "SPS30-20260731_120000.csv").write_text("index,timestamp\n1,t\n")
    holder = _handler(daemon, "alice")
    assert daemon.may_control(holder)  # someone else is driving

    reader = _handler(daemon, "bob")
    reader._dispatch({"cmd": wire.CMD_BACKUP})
    assert not any(m.get("needs_control") for m in reader.sent)
    assert any(m.get("type") == wire.MSG_BACKUP_META for m in reader.sent)


def test_backup_with_nothing_recorded_says_so(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    client = _handler(daemon, "someone")
    client._dispatch({"cmd": wire.CMD_BACKUP})
    assert any("no recorded sessions" in m.get("message", "") for m in client.sent)
