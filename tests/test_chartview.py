"""Unit tests for ``chartview`` - the pure-logic chart view model.

No hardware, no terminal, no socket: these exercise duration parsing, the
multi-series time store, and the view/compute state machine directly.
"""
import re
from datetime import datetime, timedelta

import pytest

import wire
import chartview
from chartview import ChartView, TimeSeriesHistory, PM_FIELDS, parse_duration


# --- helpers ----------------------------------------------------------------
BASE = datetime(2026, 7, 19, 1, 0, 0, tzinfo=wire.EASTERN)


def at(seconds: float) -> datetime:
    """A tz-aware Eastern datetime ``seconds`` after BASE."""
    return BASE + timedelta(seconds=seconds)


def reading(index, when, csv="SPS30-a.csv", pm1_0=1.0, pm2_5=2.0,
            pm4_0=2.0, pm10=2.0, timestamp=None):
    """Build one live-reading dict with an Eastern ISO timestamp."""
    ts = timestamp if timestamp is not None else when.isoformat(timespec="seconds")
    return {
        "csv": csv,
        "index": wire.format_index(index),
        "timestamp": ts,
        "pm1_0": pm1_0, "pm2_5": pm2_5, "pm4_0": pm4_0, "pm10": pm10,
    }


# --- parse_duration ---------------------------------------------------------
def test_parse_bare_number_is_seconds():
    assert parse_duration("1800") == 1800.0


def test_parse_seconds_suffix():
    assert parse_duration("1800s") == 1800.0
    assert parse_duration("45sec") == 45.0
    assert parse_duration("45secs") == 45.0
    assert parse_duration("45seconds") == 45.0


def test_parse_minutes():
    assert parse_duration("18m") == 1080.0
    assert parse_duration("18min") == 1080.0
    assert parse_duration("18minutes") == 1080.0


def test_parse_hours():
    assert parse_duration("12h") == 43200.0
    assert parse_duration("12hr") == 43200.0
    assert parse_duration("12hours") == 43200.0


def test_parse_days():
    assert parse_duration("2d") == 172800.0
    assert parse_duration("1day") == 86400.0


def test_parse_combo_hm():
    assert parse_duration("1h30m") == 5400.0


def test_parse_combo_hms():
    assert parse_duration("2h15m30s") == 8130.0


def test_parse_word_units_with_spaces():
    assert parse_duration("12 hours") == 43200.0
    assert parse_duration("1 h 30 m") == 5400.0


def test_parse_is_case_insensitive():
    assert parse_duration("1H30M") == 5400.0
    assert parse_duration("12 HOURS") == 43200.0


def test_parse_fractional():
    assert parse_duration("1.5h") == 5400.0


@pytest.mark.parametrize("bad", ["", "   ", "abc", "0", "-5", "h", "5x", "1h30x", "m30"])
def test_parse_invalid_raises(bad):
    with pytest.raises(ValueError):
        parse_duration(bad)


# --- TimeSeriesHistory: add / dedup -----------------------------------------
def test_add_returns_true_for_new_and_false_for_dup():
    hist = TimeSeriesHistory()
    assert hist.add(reading(1, at(0))) is True
    assert hist.add(reading(1, at(0))) is False  # same (csv, index)
    assert len(hist) == 1


def test_same_index_different_csv_both_kept():
    hist = TimeSeriesHistory()
    assert hist.add(reading(5, at(0), csv="SPS30-a.csv")) is True
    assert hist.add(reading(5, at(1), csv="SPS30-b.csv")) is True
    assert len(hist) == 2  # index restarts per csv; both survive


def test_index_restart_across_files_does_not_collide():
    # A fresh CSV restarts the index at low numbers; those must not clobber the
    # earlier file's rows sharing the same index.
    hist = TimeSeriesHistory()
    hist.add(reading(0, at(0), csv="old.csv"))
    hist.add(reading(1, at(1), csv="old.csv"))
    hist.add(reading(0, at(2), csv="new.csv"))
    hist.add(reading(1, at(3), csv="new.csv"))
    assert len(hist) == 4


# --- TimeSeriesHistory: merge / ordering ------------------------------------
def test_merge_returns_count_added():
    hist = TimeSeriesHistory()
    added = hist.merge([reading(1, at(10)), reading(2, at(20)), reading(1, at(10))])
    assert added == 2  # third is a duplicate of the first
    assert len(hist) == 2


def test_merge_older_rows_keeps_time_order():
    hist = TimeSeriesHistory()
    hist.add(reading(10, at(100), csv="live.csv"))
    hist.add(reading(11, at(101), csv="live.csv"))
    # Backfill older rows from a different file - they should sort in front.
    hist.merge([reading(0, at(0), csv="old.csv"), reading(1, at(1), csv="old.csv")])
    epochs, _ = hist.window(hist.earliest_epoch(), hist.latest_epoch())
    assert epochs == sorted(epochs)
    assert epochs[0] == at(0).timestamp()
    assert epochs[-1] == at(101).timestamp()


# --- TimeSeriesHistory: window ----------------------------------------------
def test_window_returns_only_in_range_in_time_order():
    hist = TimeSeriesHistory()
    for i, s in enumerate([0, 10, 20, 30, 40]):
        hist.add(reading(i, at(s), pm1_0=float(s)))
    epochs, series = hist.window(at(10).timestamp(), at(30).timestamp())
    assert epochs == [at(10).timestamp(), at(20).timestamp(), at(30).timestamp()]
    assert series["pm1_0"] == [10.0, 20.0, 30.0]
    assert epochs == sorted(epochs)


def test_window_missing_field_reads_as_zero():
    hist = TimeSeriesHistory()
    hist.add({"csv": "a", "index": "0000001",
              "timestamp": at(0).isoformat(timespec="seconds"), "pm1_0": 3.0})
    _, series = hist.window(at(-1).timestamp(), at(1).timestamp())
    assert series["pm2_5"] == [0.0]
    assert series["pm1_0"] == [3.0]


def test_window_empty_when_out_of_range():
    hist = TimeSeriesHistory()
    hist.add(reading(1, at(0)))
    epochs, series = hist.window(at(1000).timestamp(), at(2000).timestamp())
    assert epochs == []
    assert series["pm1_0"] == []


# --- TimeSeriesHistory: retention -------------------------------------------
def test_retention_trims_old_points():
    hist = TimeSeriesHistory(retention_seconds=500)
    hist.add(reading(1, at(0)))
    hist.add(reading(2, at(1000)))  # latest - 500 = 500; the at(0) point is older
    assert len(hist) == 1
    assert hist.earliest_epoch() == at(1000).timestamp()


def test_set_retention_trims_immediately():
    hist = TimeSeriesHistory(retention_seconds=100000)
    for i, s in enumerate([0, 100, 200, 300]):
        hist.add(reading(i, at(s)))
    assert len(hist) == 4
    hist.set_retention(150)  # keep only epoch >= 300 - 150 = 150
    remaining, _ = hist.window(hist.earliest_epoch(), hist.latest_epoch())
    assert remaining == [at(200).timestamp(), at(300).timestamp()]


# --- TimeSeriesHistory: earliest / latest / from_rows -----------------------
def test_earliest_latest_and_len_on_empty():
    hist = TimeSeriesHistory()
    assert hist.earliest_epoch() is None
    assert hist.latest_epoch() is None
    assert len(hist) == 0


def test_from_rows_round_trips():
    columns = wire.CSV_COLUMNS
    rows = []
    for i, s in enumerate([0, 5, 10]):
        row = [wire.format_index(i), at(s).isoformat(timespec="seconds")]
        row += [float(s), float(s) + 1, float(s) + 1, float(s) + 1]  # pm fields
        row += [0.0] * (len(columns) - len(row))  # nc fields + typical_size
        rows.append(row)
    hist = TimeSeriesHistory.from_rows(columns, rows, csv_name="SPS30-file.csv")
    assert len(hist) == 3
    epochs, series = hist.window(hist.earliest_epoch(), hist.latest_epoch())
    assert epochs == [at(0).timestamp(), at(5).timestamp(), at(10).timestamp()]
    assert series["pm1_0"] == [0.0, 5.0, 10.0]
    assert series["pm2_5"] == [1.0, 6.0, 11.0]


def test_blank_or_missing_timestamp_is_skipped_not_crashing():
    hist = TimeSeriesHistory()
    assert hist.add(reading(1, at(0), timestamp="")) is False
    blank = {"csv": "a", "index": "0000002", "timestamp": None, "pm1_0": 1.0}
    assert hist.add(blank) is False
    no_ts = {"csv": "a", "index": "0000003", "pm1_0": 1.0}
    assert hist.add(no_ts) is False
    assert len(hist) == 0
    # A good reading still lands fine afterwards.
    assert hist.add(reading(4, at(0))) is True
    assert len(hist) == 1


# --- ChartView: modes and ranges --------------------------------------------
def test_default_mode_is_moving():
    view = ChartView(window_seconds=120)
    assert view.mode == "moving"
    assert view.pin_start is None


def test_visible_range_moving():
    view = ChartView(window_seconds=120)
    start, end = view.visible_range(1000.0)
    assert (start, end) == (880.0, 1000.0)


def test_visible_range_pinned_not_clipped_by_window():
    view = ChartView(window_seconds=120)
    view.set_mode("pinned", now_epoch=1000.0)
    assert view.pin_start == 880.0  # pinned at now - window when unset
    # Advance well past the window; the left edge stays put (no clipping).
    start, end = view.visible_range(5000.0)
    assert start == 880.0
    assert end == 5000.0


def test_toggle_mode_returns_new_mode_and_sets_pin():
    view = ChartView(window_seconds=120)
    new_mode = view.toggle_mode(now_epoch=1000.0)
    assert new_mode == "pinned"
    assert view.mode == "pinned"
    assert view.pin_start == 880.0  # now - window
    # Toggling back to moving keeps the pin for a later return.
    assert view.toggle_mode(now_epoch=2000.0) == "moving"
    assert view.pin_start == 880.0
    # Returning to pinned restores the original pin, not now - window.
    assert view.toggle_mode(now_epoch=3000.0) == "pinned"
    assert view.pin_start == 880.0


def test_set_window_updates_and_rejects_nonpositive():
    view = ChartView(window_seconds=120)
    view.set_window(3600)
    assert view.window_seconds == 3600
    with pytest.raises(ValueError):
        view.set_window(0)


def test_set_mode_rejects_unknown():
    view = ChartView()
    with pytest.raises(ValueError):
        view.set_mode("frozen", now_epoch=0.0)


# --- ChartView: compute -----------------------------------------------------
def _filled_history():
    hist = TimeSeriesHistory()
    for i in range(30):
        hist.add(reading(i, at(i * 10), pm1_0=float(i)))
    return hist


def test_compute_returns_all_documented_keys():
    view = ChartView(window_seconds=300)
    hist = _filled_history()
    result = view.compute(hist, now_epoch=at(290).timestamp())
    expected = {"x", "series", "xticks", "xlabels", "start", "end",
                "mode", "window_seconds", "markers", "span_seconds",
                "y_auto", "y_min", "y_max", "data_ymin", "data_ymax",
                "x_frozen"}
    assert set(result) == expected


def test_compute_series_align_with_x():
    view = ChartView(window_seconds=300)
    hist = _filled_history()
    result = view.compute(hist, now_epoch=at(290).timestamp())
    n = len(result["x"])
    assert n > 0
    for field in PM_FIELDS:
        assert len(result["series"][field]) == n


def test_compute_tick_count_is_five_to_seven():
    view = ChartView(window_seconds=300)
    hist = _filled_history()
    result = view.compute(hist, now_epoch=at(290).timestamp())
    assert 5 <= len(result["xticks"]) <= 7
    assert len(result["xlabels"]) == len(result["xticks"])
    assert result["xticks"] == sorted(result["xticks"])


def test_compute_short_span_labels_are_hhmmss():
    # 300s span < 10min -> HH:MM:SS
    view = ChartView(window_seconds=300)
    hist = _filled_history()
    now = at(290)
    result = view.compute(hist, now_epoch=now.timestamp())
    assert result["span_seconds"] == 300.0
    for label in result["xlabels"]:
        assert re.fullmatch(r"\d{2}:\d{2}:\d{2}", label), label
    # The final tick is exactly ``now`` in Eastern local time.
    assert result["xlabels"][-1] == now.strftime("%H:%M:%S")


def test_compute_multihour_span_labels_are_hhmm():
    # 6h span: >= 10min and < 24h -> HH:MM (no seconds)
    view = ChartView(window_seconds=6 * 3600)
    hist = TimeSeriesHistory()
    for i in range(10):
        hist.add(reading(i, at(i * 1800)))  # every 30 min
    now = at(6 * 3600)
    result = view.compute(hist, now_epoch=now.timestamp())
    assert result["span_seconds"] == 6 * 3600
    for label in result["xlabels"]:
        assert re.fullmatch(r"\d{2}:\d{2}", label), label
    assert result["xlabels"][-1] == now.strftime("%H:%M")


def test_compute_multiday_span_labels_include_date():
    # > 24h -> MM-DD HH:MM
    view = ChartView(window_seconds=48 * 3600)
    hist = TimeSeriesHistory()
    hist.add(reading(0, at(0)))
    now = at(48 * 3600)
    result = view.compute(hist, now_epoch=now.timestamp())
    for label in result["xlabels"]:
        assert re.fullmatch(r"\d{2}-\d{2} \d{2}:\d{2}", label), label


def test_compute_markers_distinct_per_series():
    view = ChartView()
    hist = _filled_history()
    result = view.compute(hist, now_epoch=at(290).timestamp())
    markers = result["markers"]
    assert set(markers) == set(PM_FIELDS)
    assert len(set(markers.values())) == len(PM_FIELDS)  # all four distinct


def test_compute_pinned_shows_full_curve_from_pin():
    view = ChartView(window_seconds=60)
    hist = _filled_history()  # points at 0,10,...,290s
    view.set_mode("pinned", now_epoch=at(0).timestamp())  # pin near the start
    result = view.compute(hist, now_epoch=at(290).timestamp())
    # Not clipped to the 60s window: many points visible, back to the pin.
    assert len(result["x"]) > 10
    assert result["mode"] == "pinned"


# --- performance-shaped: large series, bisect window, decimation ------------
def _big_history(n=5000, step=1.0):
    """A large series (default 5000 points at 1s spacing) for scale tests."""
    hist = TimeSeriesHistory()
    for i in range(n):
        hist.add(reading(i, at(i * step), pm1_0=float(i)))
    return hist


def test_window_bisect_on_large_series_returns_correct_slice():
    hist = _big_history(5000)
    assert len(hist) == 5000
    epochs, series = hist.window(at(1000).timestamp(), at(1999).timestamp())
    # Exactly the 1000 in-range points, in time order, with aligned values.
    assert len(epochs) == 1000
    assert epochs[0] == at(1000).timestamp()
    assert epochs[-1] == at(1999).timestamp()
    assert epochs == sorted(epochs)
    assert series["pm1_0"][0] == 1000.0
    assert series["pm1_0"][-1] == 1999.0
    assert len(series["pm1_0"]) == 1000


def test_dedup_is_set_based_over_many_points():
    hist = _big_history(2000)
    # Re-adding every existing (csv, index) is rejected without growth.
    re_added = hist.merge([reading(i, at(i)) for i in range(2000)])
    assert re_added == 0
    assert len(hist) == 2000
    # A genuinely new key under a different csv is still accepted.
    assert hist.add(reading(0, at(0), csv="other.csv")) is True
    assert len(hist) == 2001


def test_compute_max_points_caps_length_and_keeps_first_last():
    view = ChartView(window_seconds=5000)
    hist = _big_history(5000)
    now = at(4999)
    full = view.compute(hist, now_epoch=now.timestamp())
    assert len(full["x"]) == 5000  # no cap without max_points

    capped = view.compute(hist, now_epoch=now.timestamp(), max_points=500)
    assert 0 < len(capped["x"]) <= 500
    for field in PM_FIELDS:
        assert len(capped["series"][field]) == len(capped["x"])
    # First and last visible samples are preserved exactly (live right edge).
    assert capped["x"][0] == full["x"][0]
    assert capped["x"][-1] == full["x"][-1]
    assert capped["series"]["pm1_0"][0] == full["series"]["pm1_0"][0]
    assert capped["series"]["pm1_0"][-1] == full["series"]["pm1_0"][-1]
    # Decimation does not disturb the tick axis.
    assert 5 <= len(capped["xticks"]) <= 7


def test_compute_max_points_noop_when_slice_small():
    view = ChartView(window_seconds=300)
    hist = _filled_history()
    result = view.compute(hist, now_epoch=at(290).timestamp(), max_points=1000)
    # Fewer points than the cap -> returned unchanged.
    assert len(result["x"]) <= 30
    assert result["x"] == sorted(result["x"])


# --- TimeSeriesHistory: clear -----------------------------------------------
def test_clear_empties_the_store():
    hist = TimeSeriesHistory()
    for i, s in enumerate([0, 10, 20]):
        hist.add(reading(i, at(s)))
    assert len(hist) == 3
    hist.clear()
    assert len(hist) == 0
    assert hist.earliest_epoch() is None
    assert hist.latest_epoch() is None
    epochs, series = hist.window(at(-100).timestamp(), at(100).timestamp())
    assert epochs == []
    assert series["pm1_0"] == []


def test_clear_resets_dedup_so_same_keys_re_add():
    hist = TimeSeriesHistory()
    assert hist.add(reading(1, at(0))) is True
    assert hist.add(reading(1, at(0))) is False  # duplicate rejected
    hist.clear()
    # After clear the same (csv, index) is accepted again.
    assert hist.add(reading(1, at(0))) is True
    assert len(hist) == 1


# --- ChartView: absolute x range (freeze) -----------------------------------
def test_set_x_range_validation():
    view = ChartView()
    with pytest.raises(ValueError):
        view.set_x_range(100.0, 100.0)  # not strictly increasing
    with pytest.raises(ValueError):
        view.set_x_range(200.0, 100.0)
    view.set_x_range(100.0, 300.0)
    assert view.x_fixed == (100.0, 300.0)
    assert view.x_frozen() is True


def test_x_range_defaults_not_frozen():
    view = ChartView()
    assert view.x_fixed is None
    assert view.x_frozen() is False


def test_visible_range_returns_fixed_ignoring_moving_and_pinned():
    view = ChartView(window_seconds=120)
    view.set_x_range(100.0, 300.0)
    # Moving now would give (now-120, now); pinned would give (pin, now). The
    # frozen range wins verbatim regardless of mode or now.
    assert view.visible_range(9999.0) == (100.0, 300.0)
    view.set_mode("pinned", now_epoch=5000.0)
    assert view.visible_range(9999.0) == (100.0, 300.0)


def test_clear_x_range_returns_to_live():
    view = ChartView(window_seconds=120)
    view.set_x_range(100.0, 300.0)
    view.clear_x_range()
    assert view.x_fixed is None
    assert view.x_frozen() is False
    assert view.visible_range(1000.0) == (880.0, 1000.0)


def test_freeze_x_captures_current_moving_range():
    view = ChartView(window_seconds=120)
    view.freeze_x(now_epoch=1000.0)
    assert view.x_fixed == (880.0, 1000.0)
    # Once frozen the range no longer follows now.
    assert view.visible_range(5000.0) == (880.0, 1000.0)


def test_freeze_x_captures_current_pinned_range():
    view = ChartView(window_seconds=120)
    view.set_mode("pinned", now_epoch=1000.0)  # pin_start = 880
    view.freeze_x(now_epoch=2000.0)
    assert view.x_fixed == (880.0, 2000.0)


def test_x_autoscale_off_freezes_and_on_clears():
    view = ChartView(window_seconds=120)
    view.x_autoscale(False, now_epoch=1000.0)
    assert view.x_fixed == (880.0, 1000.0)
    view.x_autoscale(True, now_epoch=1000.0)
    assert view.x_fixed is None


# --- ChartView: pan ---------------------------------------------------------
def test_pan_fixed_shifts_both_edges():
    view = ChartView()
    view.set_x_range(100.0, 300.0)
    view.pan(50.0, now_epoch=9999.0)
    assert view.x_fixed == (150.0, 350.0)
    view.pan(-100.0, now_epoch=9999.0)
    assert view.x_fixed == (50.0, 250.0)


def test_pan_pinned_moves_pin_start():
    view = ChartView(window_seconds=120)
    view.set_mode("pinned", now_epoch=1000.0)  # pin_start = 880
    view.pan(60.0, now_epoch=1000.0)
    assert view.pin_start == 940.0
    assert view.x_fixed is None  # stays in pinned mode, not frozen


def test_pan_pinned_inits_pin_start_when_none():
    view = ChartView(window_seconds=120)
    view.mode = "pinned"  # forced pinned with pin_start still None
    view.pan(10.0, now_epoch=1000.0)
    # Initialised from visible_range start (now - window = 880) then shifted.
    assert view.pin_start == 890.0


def test_pan_moving_freezes_and_shifts():
    view = ChartView(window_seconds=120)
    view.pan(30.0, now_epoch=1000.0)
    # Moving view (880, 1000) frozen and shifted by +30.
    assert view.x_frozen() is True
    assert view.x_fixed == (910.0, 1030.0)


# --- ChartView: shift_pin ---------------------------------------------------
def test_shift_pin_forces_pinned_and_moves_pin():
    view = ChartView(window_seconds=120)
    assert view.mode == "moving"
    view.shift_pin(-40.0, now_epoch=1000.0)
    assert view.mode == "pinned"
    # set_mode initialised pin_start to now - window = 880, then shifted by -40.
    assert view.pin_start == 840.0


def test_shift_pin_when_already_pinned():
    view = ChartView(window_seconds=120)
    view.set_mode("pinned", now_epoch=1000.0)  # pin_start = 880
    view.shift_pin(20.0, now_epoch=2000.0)
    assert view.mode == "pinned"
    assert view.pin_start == 900.0  # 880 + 20, pin not reset by staying pinned


# --- ChartView: zoom --------------------------------------------------------
def test_zoom_rejects_nonpositive_factor():
    view = ChartView()
    with pytest.raises(ValueError):
        view.zoom(0.0, now_epoch=1000.0)
    with pytest.raises(ValueError):
        view.zoom(-2.0, now_epoch=1000.0)


def test_zoom_fixed_scales_around_center():
    view = ChartView()
    view.set_x_range(100.0, 300.0)  # center 200, span 200
    view.zoom(0.5, now_epoch=9999.0)  # zoom in -> span 100 around 200
    assert view.x_fixed == (150.0, 250.0)
    span = view.x_fixed[1] - view.x_fixed[0]
    assert span == 100.0
    # Center is preserved.
    assert (view.x_fixed[0] + view.x_fixed[1]) / 2.0 == 200.0


def test_zoom_out_fixed_widens_span():
    view = ChartView()
    view.set_x_range(100.0, 300.0)  # span 200
    view.zoom(2.0, now_epoch=9999.0)  # zoom out -> span 400 around 200
    assert view.x_fixed == (0.0, 400.0)
    assert view.x_fixed[1] - view.x_fixed[0] == 400.0


def test_zoom_moving_scales_window():
    view = ChartView(window_seconds=120)
    view.zoom(0.5, now_epoch=1000.0)
    assert view.window_seconds == 60.0
    view.zoom(4.0, now_epoch=1000.0)
    assert view.window_seconds == 240.0


def test_zoom_pinned_scales_span_around_now():
    view = ChartView(window_seconds=120)
    view.set_mode("pinned", now_epoch=1000.0)  # pin_start = 880, span 120
    view.zoom(0.5, now_epoch=1000.0)  # zoom in about now -> span 60
    # pin_start = now - (now - pin) * factor = 1000 - 120 * 0.5 = 940
    assert view.pin_start == 940.0
    span = view.visible_range(1000.0)[1] - view.visible_range(1000.0)[0]
    assert span == 60.0


def test_zoom_pinned_inits_pin_start_when_none():
    view = ChartView(window_seconds=120)
    view.mode = "pinned"  # pin_start still None
    view.zoom(2.0, now_epoch=1000.0)
    # pin initialised from visible start 880, then 1000 - (1000-880)*2 = 760
    assert view.pin_start == 760.0


# --- ChartView: y-axis freeze -----------------------------------------------
def test_set_y_range_validation_and_disables_auto():
    view = ChartView()
    with pytest.raises(ValueError):
        view.set_y_range(5.0, 5.0)
    with pytest.raises(ValueError):
        view.set_y_range(9.0, 1.0)
    view.set_y_range(1.0, 9.0)
    assert view.y_auto is False
    assert view.y_min == 1.0
    assert view.y_max == 9.0


def test_y_defaults():
    view = ChartView()
    assert view.y_auto is True
    assert view.y_min is None
    assert view.y_max is None


def test_y_autoscale_off_with_data_range_freezes():
    view = ChartView()
    view.y_autoscale(False, data_min=2.0, data_max=8.0)
    assert view.y_auto is False
    assert view.y_min == 2.0
    assert view.y_max == 8.0


def test_y_autoscale_off_without_range_leaves_bounds_none():
    view = ChartView()
    view.y_autoscale(False)
    assert view.y_auto is False
    assert view.y_min is None
    assert view.y_max is None


def test_y_autoscale_off_does_not_overwrite_existing_range():
    view = ChartView()
    view.set_y_range(1.0, 9.0)
    view.y_autoscale(False, data_min=100.0, data_max=200.0)
    # Existing manual range is preserved (only None bounds get filled).
    assert view.y_min == 1.0
    assert view.y_max == 9.0


def test_y_autoscale_on_clears_freeze_intent():
    view = ChartView()
    view.set_y_range(1.0, 9.0)
    view.y_autoscale(True)
    assert view.y_auto is True


def test_freeze_y_sets_bounds_and_disables_auto():
    view = ChartView()
    view.freeze_y(3.0, 12.0)
    assert view.y_auto is False
    assert view.y_min == 3.0
    assert view.y_max == 12.0


# --- ChartView.compute: new axis keys ---------------------------------------
def test_compute_new_axis_keys_present_and_default():
    view = ChartView(window_seconds=300)
    hist = _filled_history()
    result = view.compute(hist, now_epoch=at(290).timestamp())
    assert result["y_auto"] is True
    assert result["y_min"] is None
    assert result["y_max"] is None
    assert result["x_frozen"] is False


def test_compute_x_frozen_true_after_set_x_range():
    view = ChartView(window_seconds=120)
    hist = _filled_history()
    view.set_x_range(at(0).timestamp(), at(290).timestamp())
    result = view.compute(hist, now_epoch=at(9999).timestamp())
    assert result["x_frozen"] is True
    # start/end reflect the frozen range (via visible_range).
    assert result["start"] == at(0).timestamp()
    assert result["end"] == at(290).timestamp()


def test_compute_data_ymin_ymax_across_all_series():
    view = ChartView(window_seconds=300)
    hist = TimeSeriesHistory()
    # pm1_0 ramps 0..40; the other three sit at a constant 2.0.
    for i, s in enumerate([0, 10, 20, 30, 40]):
        hist.add(reading(i, at(s), pm1_0=float(s), pm2_5=2.0, pm4_0=2.0, pm10=2.0))
    result = view.compute(hist, now_epoch=at(40).timestamp())
    # Min across all four series is 0.0 (pm1_0 at s=0), max is 40.0 (pm1_0 at 40).
    assert result["data_ymin"] == 0.0
    assert result["data_ymax"] == 40.0


def test_compute_data_ymin_equals_ymax_when_series_all_equal():
    view = ChartView(window_seconds=300)
    hist = TimeSeriesHistory()
    # All four PM series bit-identical at 2.0 (clean-air case).
    for i, s in enumerate([0, 10, 20]):
        hist.add(reading(i, at(s), pm1_0=2.0, pm2_5=2.0, pm4_0=2.0, pm10=2.0))
    result = view.compute(hist, now_epoch=at(20).timestamp())
    assert result["data_ymin"] == 2.0
    assert result["data_ymax"] == 2.0


def test_compute_data_extent_none_when_slice_empty():
    view = ChartView(window_seconds=60)
    hist = _filled_history()
    # A frozen x range far from any data -> empty slice.
    view.set_x_range(at(100000).timestamp(), at(100060).timestamp())
    result = view.compute(hist, now_epoch=at(290).timestamp())
    assert result["x"] == []
    assert result["data_ymin"] is None
    assert result["data_ymax"] is None


def test_compute_data_extent_computed_before_decimation():
    # The true extent must come from the full slice, not the thinned samples.
    view = ChartView(window_seconds=5000)
    hist = TimeSeriesHistory()
    n = 2000
    for i in range(n):
        hist.add(reading(i, at(i), pm1_0=float(i)))
    now = at(n - 1)
    capped = view.compute(hist, now_epoch=now.timestamp(), max_points=50)
    assert len(capped["x"]) <= 50  # decimated
    # Extent still reflects the full 0..1999 pm1_0 ramp, and 2.0 floor of others.
    assert capped["data_ymin"] == 0.0
    assert capped["data_ymax"] == float(n - 1)
