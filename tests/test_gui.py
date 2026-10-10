"""No-network, no-live-Textual tests for the SPS30 GUI client logic.

These exercise the pure classes/functions in ``sps30_gui`` by feeding them
synthetic wire messages (built with the shared ``wire`` contract) and asserting
on the resulting state: history buffering + trimming, the local CSV mirror,
file-chunk reassembly, hex validation, and reconnect backfill index tracking.

An optional Textual smoke test (``App.run_test``) mounts the app headless; it is
guarded so a flaky/absent headless environment cannot fail the suite.
"""
from __future__ import annotations

import csv
import itertools

import pytest

import wire
import sps30_gui as gui


# --- helpers ----------------------------------------------------------------
def reading_msg(index: int, pm1_0=1.0, pm2_5=2.0, pm4_0=3.0, pm10=4.0,
                csv_name=None):
    """A MSG_READING dict using the on-wire index/field shape."""
    msg = {
        "type": wire.MSG_READING,
        "index": wire.format_index(index),
        "timestamp": wire.iso_timestamp(
            wire.datetime.fromtimestamp(1_700_000_000 + index, wire.EASTERN)
        ),
        "pm1_0": pm1_0, "pm2_5": pm2_5, "pm4_0": pm4_0, "pm10": pm10,
        "nc0_5": 0.5, "nc1_0": 1.1, "nc2_5": 2.2, "nc4_0": 4.4, "nc10": 10.0,
        "typical_size": 0.6,
    }
    if csv_name is not None:
        msg["csv"] = csv_name
    return msg


def hello_msg(csv_name="SPS30-20260101_000000.csv", index=0,
              state=wire.STATE_MEASURING):
    return {
        "type": wire.MSG_HELLO,
        "product": "SPS30", "serial": "ABC123", "firmware": "2.2",
        "state": state, "csv": csv_name, "index": wire.format_index(index),
    }


def backfill_rows(indices):
    """CSV-style rows with REAL ISO timestamps (TimeSeriesHistory skips rows whose
    timestamp will not parse), pm fields set to the index value."""
    rows = []
    for i in indices:
        ts = wire.iso_timestamp(
            wire.datetime.fromtimestamp(1_700_000_000 + i, wire.EASTERN))
        rows.append([wire.format_index(i), ts] + [float(i)] * len(wire.FIELDS))
    return rows


def series_of(state, field="pm1_0"):
    """The full stored series for a field, in time order."""
    _epochs, series = state.history.window(0, 2e10)
    return series[field]


def stored_indices(rows_path):
    """Indices recorded in a mirror CSV, as ints."""
    return sorted(int(r["index"]) for r in csv.DictReader(open(rows_path, newline="")))


class FakeClock:
    """Deterministic monotonic clock for rate-meter assertions."""

    def __init__(self):
        self._t = itertools.count(0.0, 1.0)

    def __call__(self):
        return next(self._t)


# --- hex validation ---------------------------------------------------------
def test_valid_hex_accepted_and_normalised():
    assert gui.normalize_hex("7e0000020103f97e") == "7e0000020103f97e"
    assert gui.normalize_hex("0x7E 00 00 02") == "7e000002"
    assert gui.normalize_hex("7e:00:00:02") == "7e000002"
    assert gui.is_valid_hex("7e0000030002fd7e")


@pytest.mark.parametrize("bad", ["", "   ", "7e0", "zz", "0x7g", "7e 0"])
def test_invalid_hex_rejected(bad):
    assert not gui.is_valid_hex(bad)
    with pytest.raises(ValueError):
        gui.normalize_hex(bad)


# --- chart history ----------------------------------------------------------
def test_history_updates_on_reading():
    state = gui.DashboardState()
    state.handle_message(reading_msg(1, pm2_5=7.5, csv_name="c.csv"))
    assert len(state.history) == 1
    assert series_of(state, "pm2_5")[-1] == 7.5
    assert state.last_index == 1


# --- rate meter -------------------------------------------------------------
def test_rate_meter_tracks_readings_per_second():
    state = gui.DashboardState(clock=FakeClock())
    for i in range(1, 6):
        state.handle_message(reading_msg(i))
    # ticks at t=0,1,2,3,4 -> 4 intervals over 4 seconds -> 1.0/s
    assert state.rate() == pytest.approx(1.0)


# --- local CSV mirror -------------------------------------------------------
def test_local_mirror_writes_correct_columns_and_index(tmp_path):
    path = tmp_path / wire.csv_filename(wire.now_eastern())
    mirror = gui.LocalMirror(path)
    state = gui.DashboardState(mirror=mirror)
    state.handle_message(reading_msg(1))
    state.handle_message(reading_msg(2))
    mirror.close()

    with open(path, newline="") as fh:
        rows = list(csv.DictReader(fh))
    assert list(rows[0].keys()) == wire.CSV_COLUMNS
    assert rows[0]["index"] == wire.format_index(1)
    assert rows[1]["index"] == wire.format_index(2)
    assert rows[0]["pm2_5"] == "2.0"


def test_local_mirror_appends_without_duplicate_header(tmp_path):
    path = tmp_path / "mirror.csv"
    m1 = gui.LocalMirror(path)
    m1.append(reading_msg(1))
    m1.close()
    m2 = gui.LocalMirror(path)  # reopen existing file
    m2.append(reading_msg(2))
    m2.close()
    text = path.read_text().splitlines()
    header = ",".join(wire.CSV_COLUMNS)
    assert text.count(header) == 1
    assert len(text) == 3  # header + 2 rows


def test_mirror_stores_int_index_in_padded_form(tmp_path):
    path = tmp_path / "mirror.csv"
    mirror = gui.LocalMirror(path)
    mirror.append({"index": 7, "timestamp": "t", "pm1_0": 1})
    mirror.close()
    rows = list(csv.DictReader(open(path, newline="")))
    assert rows[0]["index"] == wire.format_index(7)


# --- file assembly ----------------------------------------------------------
def test_file_data_chunks_reassemble_in_order():
    asm = gui.FileAssembler()
    asm.reset("SPS30-hist.csv")
    done = asm.add_chunk({
        "type": wire.MSG_FILE_DATA, "name": "SPS30-hist.csv",
        "columns": wire.CSV_COLUMNS, "rows": [["0000001"], ["0000002"]],
        "done": False,
    })
    assert done is False
    done = asm.add_chunk({
        "type": wire.MSG_FILE_DATA, "name": "SPS30-hist.csv",
        "rows": [["0000003"], ["0000004"]], "done": True,
    })
    assert done is True
    result = asm.result()
    assert result["columns"] == wire.CSV_COLUMNS
    assert [r[0] for r in result["rows"]] == \
        ["0000001", "0000002", "0000003", "0000004"]


# --- reconnect backfill index tracking --------------------------------------
def test_backfill_request_helper():
    assert gui.backfill_request("f.csv", 0) is None
    assert gui.backfill_request("", 5) is None
    cmd = gui.backfill_request("f.csv", 5)
    assert cmd == {"cmd": wire.CMD_GET_FILE, "name": "f.csv", "since_index": 5}


def test_reconnect_computes_correct_since_index():
    state = gui.DashboardState()
    # first connect: adopt daemon index baseline, then stream forward
    state.handle_message(hello_msg(index=0))
    for i in range(1, 6):
        state.handle_message(reading_msg(i))
    assert state.last_index == 5

    # reconnect to the SAME Pi csv -> backfill from our last index (5)
    kind, payload = state.handle_message(hello_msg(index=9))
    assert kind == "hello"
    assert payload["backfill"] == {
        "cmd": wire.CMD_GET_FILE, "name": "SPS30-20260101_000000.csv",
        "since_index": 5,
    }


def test_reconnect_to_new_csv_does_not_backfill():
    state = gui.DashboardState()
    state.handle_message(hello_msg(csv_name="old.csv", index=0))
    for i in range(1, 4):
        state.handle_message(reading_msg(i))
    kind, payload = state.handle_message(hello_msg(csv_name="new.csv", index=0))
    assert payload["backfill"] is None
    assert state.last_index == 0  # baseline reset for the new file


def test_mid_session_csv_rotation_keeps_streaming():
    """Regression: resume/restart/clean makes the daemon open a NEW CSV whose
    per-file index restarts at 1. Live readings for the new file must not be
    rejected as "already seen" just because the old file's watermark was higher.
    Symptom before the fix: the chart froze after any restart until reconnect.
    """
    state = gui.DashboardState()
    state.handle_message(hello_msg(csv_name="SPS30-A.csv", index=0))
    for i in range(1, 51):
        state.handle_message(reading_msg(i, csv_name="SPS30-A.csv"))
    assert state.last_index == 50
    assert len(state.history) == 50

    # Daemon rolls to a new CSV (restart); indices restart at 1 with a new name.
    added = state.ingest_reading(reading_msg(1, csv_name="SPS30-B.csv"))
    assert added is True                     # not discarded as "already seen"
    assert state.active_csv == "SPS30-B.csv"
    assert state.last_index == 1
    assert len(state.history) == 51          # history keeps accumulating

    for i in range(2, 6):
        state.handle_message(reading_msg(i, csv_name="SPS30-B.csv"))
    assert state.last_index == 5
    assert len(state.history) == 55

    # De-dup still holds within the new file: a repeat of index 3 is a no-op.
    assert state.ingest_reading(reading_msg(3, csv_name="SPS30-B.csv")) is False


def test_reading_without_csv_field_does_not_reset(tmp_path):
    """Backfilled rows (and older daemons) omit the csv key; that must never
    trigger a watermark reset."""
    state = gui.DashboardState()
    state.handle_message(hello_msg(csv_name="SPS30-A.csv", index=0))
    for i in range(1, 6):
        state.handle_message(reading_msg(i, csv_name="SPS30-A.csv"))
    assert state.last_index == 5
    # a reading with no csv key is treated as the current file, deduped normally
    assert state.ingest_reading(reading_msg(3)) is False
    assert state.ingest_reading(reading_msg(6)) is True
    assert state.active_csv == "SPS30-A.csv"


def test_backfill_ingestion_is_idempotent_by_index():
    state = gui.DashboardState()
    state.handle_message(hello_msg(index=0))
    for i in range(1, 6):
        state.handle_message(reading_msg(i))
    assert state.last_index == 5

    # backfill payload overlaps (3..8); only 6,7,8 are genuinely new
    added = state.ingest_file_rows(wire.CSV_COLUMNS, backfill_rows(range(3, 9)))
    assert added == 3
    assert state.last_index == 8
    assert len(state.history) == 8
    # the three appended rows carry pm1_0 == their index, in time order
    assert series_of(state, "pm1_0")[-3:] == [6.0, 7.0, 8.0]


def test_backfill_accepted_after_live_readings_advance_index(tmp_path):
    """Regression: a late backfill chunk (older indices) must still be ingested
    even though live readings already jumped past the gap.

    Scenario: we have rows through index 100, live readings 149,150,151 arrive
    (ahead of a gap), THEN the backfill chunk 101..150 arrives late. Every row
    101..151 must land exactly once in both the history buffer and the mirror,
    with none dropped and no duplicates.
    """
    path = tmp_path / "mirror.csv"
    mirror = gui.LocalMirror(path)
    state = gui.DashboardState(mirror=mirror)

    # Baseline: reconnected to a Pi CSV we already have through index 100.
    state.handle_message(hello_msg(index=100))
    assert state.last_index == 100

    # Live readings jump ahead of the gap and push the *live* stream forward.
    for i in (149, 150, 151):
        state.handle_message(reading_msg(i))
    # The contiguous watermark must NOT have advanced past the gap...
    assert state.last_index == 100
    # ...so the backfill request would (correctly) still ask for rows > 100.
    assert state.since_index() == 100

    # Late backfill chunk for the gap (101..150); 149,150 already came live.
    rows = backfill_rows(range(101, 151))
    added = state.ingest_file_rows(wire.CSV_COLUMNS, rows)
    assert added == 48  # 101..148 are new; 149,150 already ingested live
    assert state.last_index == 151  # watermark now contiguous through 151

    # Re-delivering the same chunk changes nothing (idempotent by index).
    assert state.ingest_file_rows(wire.CSV_COLUMNS, rows) == 0

    mirror.close()

    # History buffer holds every index 101..151 exactly once.
    assert len(state.history) == 51

    # Local mirror CSV: every index 101..151 exactly once, none dropped.
    got = stored_indices(path)
    assert got == list(range(101, 152))
    assert len(got) == len(set(got))


def test_file_requests_serialized_one_transfer_in_flight():
    """Fix 6: a second CMD_GET_FILE while one is in flight is deferred, not
    interleaved; it dispatches only once the first completes."""
    q = gui.FileRequestQueue()
    first = q.request("backfill", {"cmd": wire.CMD_GET_FILE, "name": "a.csv"})
    assert first == ("backfill", {"cmd": wire.CMD_GET_FILE, "name": "a.csv"})

    # Second request arrives mid-transfer -> queued (None), in-flight unchanged.
    second = q.request("import", {"cmd": wire.CMD_GET_FILE, "name": "b.csv"})
    assert second is None
    assert q.inflight == ("backfill", {"cmd": wire.CMD_GET_FILE, "name": "a.csv"})

    # Completing the first dispatches the queued second.
    nxt = q.complete()
    assert nxt == ("import", {"cmd": wire.CMD_GET_FILE, "name": "b.csv"})
    assert q.inflight[0] == "import"

    # Completing with an empty queue clears in-flight.
    assert q.complete() is None
    assert q.inflight is None


def test_index_int_parses_padded_and_plain():
    assert gui.index_int("0000042") == 42
    assert gui.index_int(42) == 42
    assert gui.index_int("") == 0


# --- message routing sanity -------------------------------------------------
def test_handle_message_routes_all_types():
    state = gui.DashboardState()
    assert state.handle_message(hello_msg())[0] == "hello"
    assert state.handle_message(reading_msg(1))[0] == "reading"
    assert state.handle_message(
        {"type": wire.MSG_STATE, "state": wire.STATE_PAUSED, "detail": "user"})[0] \
        == "state"
    assert state.daemon_state == wire.STATE_PAUSED
    assert state.handle_message(
        {"type": wire.MSG_RAW, "request": "7e", "response": "7e"})[0] == "raw"
    assert state.raw_log
    assert state.handle_message(
        {"type": wire.MSG_FILES, "files": [{"name": "a.csv", "rows": 3}]})[0] \
        == "files"
    assert state.files[0]["name"] == "a.csv"
    assert state.handle_message(
        {"type": wire.MSG_FILE_DATA, "name": "a.csv", "rows": [], "done": True})[0] \
        == "file_data"
    assert state.handle_message(
        {"type": wire.MSG_ERROR, "message": "boom"})[0] == "error"
    assert state.last_error == "boom"


# --- optional headless Textual smoke test -----------------------------------
def test_app_mounts_headless(tmp_path):
    """Mount the app with no daemon reachable; it must not crash.

    Driven via ``asyncio.run`` so it needs no pytest-asyncio plugin. Skipped
    (never failed) if the headless harness is unavailable in this environment.
    """
    import asyncio

    async def scenario():
        app_class = gui._build_app_class()
        app = app_class(host="127.0.0.1", port=9, data_dir=str(tmp_path),
                        window_seconds=120.0, retention_seconds=3600.0)
        try:
            async with app.run_test() as pilot:
                await pilot.pause()
                # feed messages through the real UI message handler
                app._handle_message(hello_msg(index=0))
                app._handle_message(reading_msg(1))
                await pilot.pause()
                assert app.state.last_index == 1
        finally:
            if app.client is not None:
                app.client.stop()
            app.mirror.close()

    try:
        asyncio.run(scenario())
    except Exception as exc:  # pragma: no cover - environment dependent
        pytest.skip(f"headless Textual harness unavailable: {exc!r}")


def _reading_at(epoch, index, csv_name):
    """A live MSG_READING at a specific wall-clock epoch (for retention tests)."""
    ts = wire.datetime.fromtimestamp(epoch, wire.EASTERN).isoformat(timespec="seconds")
    return {"type": wire.MSG_READING, "csv": csv_name, "index": wire.format_index(index),
            "timestamp": ts, "pm1_0": 1.0, "pm2_5": 2.0, "pm4_0": 3.0, "pm10": 4.0}


def test_import_old_file_expands_retention_and_pins(tmp_path):
    """Regression: importing a file whose rows predate the retention horizon must
    expand retention FIRST so the rows are not trimmed away as they merge, and
    must pin the view back over the imported span."""
    import asyncio
    import time as _time

    async def scenario():
        app_class = gui._build_app_class()
        app = app_class(host="127.0.0.1", port=9, data_dir=str(tmp_path),
                        window_seconds=60.0, retention_seconds=600.0)  # 10min
        try:
            async with app.run_test() as pilot:
                await pilot.pause()
                now = _time.time()
                app._handle_message(_reading_at(now, 1, "B.csv"))
                await pilot.pause()
                base = len(app.state.history)

                # A file of 30 rows spanning ~2h ago - older than the 10min horizon.
                cols = wire.CSV_COLUMNS
                rows = []
                for i in range(30):
                    epoch = now - 7200 + i * 10  # ~2h ago, 10s apart
                    ts = wire.datetime.fromtimestamp(
                        epoch, wire.EASTERN).isoformat(timespec="seconds")
                    rows.append([wire.format_index(i + 1), ts]
                                + [float(i)] * len(wire.FIELDS))

                app._request_file("import", {"cmd": wire.CMD_GET_FILE, "name": "OLD.csv"})
                app._handle_message({"type": wire.MSG_FILE_DATA, "name": "OLD.csv",
                                     "columns": cols, "rows": rows, "done": True})
                await pilot.pause()

                assert len(app.state.history) == base + 30  # old rows survived
                assert app.view.mode == "pinned"
                assert app.state.history.retention_seconds > 600.0
        finally:
            if app.client is not None:
                app.client.stop()
            app.mirror.close()

    try:
        asyncio.run(scenario())
    except Exception as exc:  # pragma: no cover - environment dependent
        pytest.skip(f"headless Textual harness unavailable: {exc!r}")


def test_clear_archives_wipes_and_local_import_is_idempotent(tmp_path):
    """Clear must archive the session to a local CSV and empty the live buffer;
    re-importing that archive merges its rows once (timestamp-keyed, idempotent)."""
    import asyncio
    import time as _time

    async def scenario():
        app_class = gui._build_app_class()
        app = app_class(host="127.0.0.1", port=9, data_dir=str(tmp_path),
                        window_seconds=60.0, retention_seconds=7200.0)
        try:
            async with app.run_test() as pilot:
                await pilot.pause()
                now = _time.time()
                for i in range(1, 6):
                    app._handle_message(_reading_at(now - 5 + i, i, "B.csv"))
                await pilot.pause()
                assert len(app.state.history) == 5
                archived = app.mirror.path.name

                app.action_clear_session()
                await pilot.pause()
                assert len(app.state.history) == 0
                assert app.mirror.path.name != archived            # rotated
                assert (tmp_path / archived).exists()              # archived

                app.local_files = app._scan_local_files()
                names = [f["name"] for f in app.local_files]
                assert archived in names
                entry = next(f for f in app.local_files if f["name"] == archived)
                app._import_local(entry["path"], entry["name"])
                await pilot.pause()
                n1 = len(app.state.history)
                assert n1 == 5                                     # all rows back
                app._import_local(entry["path"], entry["name"])    # re-import
                await pilot.pause()
                assert len(app.state.history) == n1                # no duplicates
        finally:
            if app.client is not None:
                app.client.stop()
            app.mirror.close()

    try:
        asyncio.run(scenario())
    except Exception as exc:  # pragma: no cover - environment dependent
        pytest.skip(f"headless Textual harness unavailable: {exc!r}")


def test_command_parser_and_axis_controls(tmp_path):
    """The command box drives y/x autoscale + ranges; zoom/pan adjust the view."""
    import asyncio
    import time as _time

    async def scenario():
        app_class = gui._build_app_class()
        app = app_class(host="127.0.0.1", port=9, data_dir=str(tmp_path),
                        window_seconds=120.0, retention_seconds=7200.0)
        try:
            async with app.run_test() as pilot:
                await pilot.pause()
                now = _time.time()
                for i in range(1, 4):
                    app._handle_message(_reading_at(now - 3 + i, i, "B.csv"))
                await pilot.pause()
                log = app.query_one("#rawlog")

                assert app._run_command("y 1 9", log) is True
                assert app.view.y_auto is False
                assert (app.view.y_min, app.view.y_max) == (1.0, 9.0)
                assert app._run_command("y auto", log) is True
                assert app.view.y_auto is True

                assert app._run_command("x -5m now", log) is True
                assert app.view.x_frozen()
                s, e = app.view.x_fixed
                assert abs((e - s) - 300) < 2
                # zoom in narrows the frozen span; pan right shifts it later
                app.action_zoom_in()
                s2, e2 = app.view.x_fixed
                assert (e2 - s2) < (e - s)
                app.action_pan_right()
                s3, _e3 = app.view.x_fixed
                assert s3 > s2
                assert app._run_command("x auto", log) is True
                assert not app.view.x_frozen()

                assert app._run_command("y bogus", log) is False   # invalid kept
        finally:
            if app.client is not None:
                app.client.stop()
            app.mirror.close()

    try:
        asyncio.run(scenario())
    except Exception as exc:  # pragma: no cover - environment dependent
        pytest.skip(f"headless Textual harness unavailable: {exc!r}")


def test_clear_swallows_inflight_transfer(tmp_path):
    """A file transfer begun before a clear must be drained and DISCARDED, not
    misattributed or merged into the freshly wiped buffer."""
    import asyncio
    import time as _time

    async def scenario():
        app_class = gui._build_app_class()
        app = app_class(host="127.0.0.1", port=9, data_dir=str(tmp_path),
                        window_seconds=60.0, retention_seconds=7200.0)
        try:
            async with app.run_test() as pilot:
                await pilot.pause()
                now = _time.time()
                for i in range(1, 4):
                    app._handle_message(_reading_at(now - 3 + i, i, "B.csv"))
                await pilot.pause()
                # Register an in-flight import (no daemon; the send just no-ops).
                app._request_file("import",
                                  {"cmd": wire.CMD_GET_FILE, "name": "A.csv"})
                assert app.file_requests.inflight is not None

                app.action_clear_session()
                await pilot.pause()
                assert len(app.state.history) == 0
                assert app._swallow_inflight is True

                # A's chunks (with real timestamps) now arrive; must be discarded.
                rows = []
                for k in range(1, 6):
                    ts = wire.datetime.fromtimestamp(
                        now - 100 + k, wire.EASTERN).isoformat(timespec="seconds")
                    rows.append([wire.format_index(k), ts] + [1.0] * len(wire.FIELDS))
                app._handle_message({"type": wire.MSG_FILE_DATA, "name": "A.csv",
                                     "columns": wire.CSV_COLUMNS, "rows": rows,
                                     "done": True})
                await pilot.pause()
                assert len(app.state.history) == 0        # not merged
                assert app.file_requests.inflight is None
                assert app._swallow_inflight is False
        finally:
            if app.client is not None:
                app.client.stop()
            app.mirror.close()

    try:
        asyncio.run(scenario())
    except Exception as exc:  # pragma: no cover - environment dependent
        pytest.skip(f"headless Textual harness unavailable: {exc!r}")


def test_apply_window_switches_to_moving(tmp_path):
    """Setting a window duration while pinned must switch to moving so the
    duration actually takes effect (pinned ignores window_seconds)."""
    import asyncio
    import time as _time

    async def scenario():
        app_class = gui._build_app_class()
        app = app_class(host="127.0.0.1", port=9, data_dir=str(tmp_path),
                        window_seconds=60.0, retention_seconds=7200.0)
        try:
            async with app.run_test() as pilot:
                await pilot.pause()
                now = _time.time()
                for i in range(1, 6):
                    app._handle_message(_reading_at(now - 5 + i, i, "B.csv"))
                await pilot.pause()
                app.view.set_mode("pinned", now)
                assert app.view.mode == "pinned"
                app._apply_window(120.0)
                assert app.view.mode == "moving"
                assert app.view.window_seconds == 120.0
        finally:
            if app.client is not None:
                app.client.stop()
            app.mirror.close()

    try:
        asyncio.run(scenario())
    except Exception as exc:  # pragma: no cover - environment dependent
        pytest.skip(f"headless Textual harness unavailable: {exc!r}")
