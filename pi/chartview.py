"""Pure-logic view model for the SPS30 live chart.

This module owns everything about *what* to draw without knowing *how* to draw
it: parsing a human window duration, storing full-resolution multi-series
readings keyed on their source, and computing the visible slice plus the local
(Eastern) time-axis labels. It deliberately imports only the standard library
and ``wire`` (the shared daemon/GUI contract) so it stays trivially testable and
never drags in Textual, plotext, or a socket.

The GUI (``sps30_gui.py``) consumes ``ChartView.compute`` and does the actual
plotext rendering; the daemon supplies older CSV rows for backfill. Keeping the
computation here means it can be unit-tested exhaustively in
``tests/test_chartview.py`` with no hardware and no terminal.
"""
from __future__ import annotations

import bisect
import re
from datetime import datetime

import wire

# The four cumulative PM mass concentrations, re-exported for convenience so
# callers do not have to reach into ``wire.FIELDS`` and slice.
PM_FIELDS = ["pm1_0", "pm2_5", "pm4_0", "pm10"]


# --- duration parsing -------------------------------------------------------
# Every accepted unit spelling mapped to its length in seconds. Kept flat and
# lower-case; the parser lower-cases its input before lookup.
_UNIT_SECONDS = {
    "s": 1.0, "sec": 1.0, "secs": 1.0, "second": 1.0, "seconds": 1.0,
    "m": 60.0, "min": 60.0, "mins": 60.0, "minute": 60.0, "minutes": 60.0,
    "h": 3600.0, "hr": 3600.0, "hrs": 3600.0, "hour": 3600.0, "hours": 3600.0,
    "d": 86400.0, "day": 86400.0, "days": 86400.0,
}

# One "<number><unit>" token, e.g. "1h", "30m", "2.5h". Whitespace is stripped
# out before matching so "12 hours" collapses to "12hours".
_TOKEN_RE = re.compile(r"(\d+(?:\.\d+)?)([a-z]+)")


def parse_duration(text: str) -> float:
    """Human duration -> seconds (float, > 0).

    Accepts a bare number as seconds ("1800"); unit suffixes s/sec/secs/
    second(s), m/min(s)/minute(s), h/hr(s)/hour(s), d/day(s); combinations
    ("1h30m", "2h15m30s"); optional spaces and word units ("12 hours"). Case
    insensitive. Raises ValueError on empty/unparseable/non-positive input.
    """
    if text is None:
        raise ValueError("duration is required")
    stripped = str(text).strip().lower()
    if not stripped:
        raise ValueError("empty duration")

    # A bare number (no unit) is interpreted as seconds.
    try:
        seconds = float(stripped)
    except ValueError:
        seconds = None
    if seconds is not None:
        if seconds <= 0:
            raise ValueError(f"non-positive duration: {text!r}")
        return seconds

    # Otherwise it must be one or more "<number><unit>" tokens back to back.
    compact = re.sub(r"\s+", "", stripped)
    total = 0.0
    pos = 0
    matched = False
    for match in _TOKEN_RE.finditer(compact):
        if match.start() != pos:
            # A gap means an unparseable chunk sits between tokens.
            raise ValueError(f"unparseable duration: {text!r}")
        unit = match.group(2)
        if unit not in _UNIT_SECONDS:
            raise ValueError(f"unknown duration unit {unit!r} in {text!r}")
        total += float(match.group(1)) * _UNIT_SECONDS[unit]
        pos = match.end()
        matched = True
    if not matched or pos != len(compact):
        raise ValueError(f"unparseable duration: {text!r}")
    if total <= 0:
        raise ValueError(f"non-positive duration: {text!r}")
    return total


# --- reading helpers --------------------------------------------------------
def _index_of(value) -> int:
    """Parse a wire index (``'0000007'`` or ``7``) into a plain int, forgiving
    of blanks or junk (which map to 0)."""
    if isinstance(value, bool):  # bool is an int subclass; treat as its int
        return int(value)
    if isinstance(value, int):
        return value
    text = str(value).strip()
    if not text:
        return 0
    try:
        return int(text)
    except ValueError:
        try:
            return int(float(text))
        except ValueError:
            return 0


def _epoch_of(reading: dict) -> float | None:
    """UTC epoch seconds from a reading's ISO timestamp, or None when the
    timestamp is missing/blank/unparseable so the caller can skip it."""
    ts = reading.get("timestamp")
    if ts is None:
        return None
    ts = str(ts).strip()
    if not ts:
        return None
    try:
        return datetime.fromisoformat(ts).timestamp()
    except (ValueError, TypeError):
        return None


def _values_of(reading: dict) -> dict:
    """Extract the four PM values as floats, missing/invalid -> 0.0."""
    values = {}
    for field in PM_FIELDS:
        try:
            values[field] = float(reading.get(field) or 0.0)
        except (TypeError, ValueError):
            values[field] = 0.0
    return values


# --- multi-series store -----------------------------------------------------
class TimeSeriesHistory:
    """Full-resolution multi-series store keyed on (csv_name, index), ordered by
    time.

    Epochs are float seconds (UTC epoch derived from each reading's ISO
    timestamp). De-duplication is by ``(csv_name or '', index)`` so live
    readings - whose index restarts at the top of every new CSV - never collide
    across files: the same index under two different CSV names is kept twice.
    Points older than ``latest - retention_seconds`` are trimmed so memory stays
    bounded while still holding many hours of history.
    """

    def __init__(self, retention_seconds: float = 172800.0):  # 48h default
        self.retention_seconds = float(retention_seconds)
        # Parallel sorted lists: ``_epochs[i]`` is the time of ``_points[i]``.
        self._epochs: list[float] = []
        self._points: list[dict] = []
        # Keys already stored, for O(1) de-dup.
        self._seen: set = set()

    def _key(self, reading: dict):
        csv = reading.get("csv") or reading.get("name") or ""
        return (str(csv), _index_of(reading.get("index", 0)))

    def add(self, reading: dict) -> bool:
        """Insert one live reading. Returns True if it was new and stored,
        False if it duplicated an existing (csv, index) or lacked a usable
        timestamp."""
        key = self._key(reading)
        if key in self._seen:
            return False
        epoch = _epoch_of(reading)
        if epoch is None:
            # No placeable time - skip rather than crash or guess.
            return False
        point = {"key": key, "epoch": epoch, "values": _values_of(reading)}
        # bisect_right keeps ties in insertion order, so equal-epoch points and
        # later-merged older rows both land in a stable time order.
        pos = bisect.bisect_right(self._epochs, epoch)
        self._epochs.insert(pos, epoch)
        self._points.insert(pos, point)
        self._seen.add(key)
        self._trim()
        return True

    def merge(self, readings) -> int:
        """Fold in a batch of older/backfill rows. Returns the number newly
        added (duplicates and untimed rows are skipped)."""
        added = 0
        for reading in readings or []:
            if self.add(reading):
                added += 1
        return added

    def set_retention(self, seconds: float) -> None:
        """Change the retention horizon and trim immediately to it."""
        self.retention_seconds = float(seconds)
        self._trim()

    def clear(self) -> None:
        """Empty every internal structure so the store is len 0 again and
        ``earliest_epoch``/``latest_epoch`` return None. The de-dup set is also
        wiped so previously seen (csv, index) keys can be re-added."""
        self._epochs.clear()
        self._points.clear()
        self._seen.clear()

    def _trim(self) -> None:
        """Drop points strictly older than ``latest - retention_seconds``."""
        if not self._epochs:
            return
        cutoff = self._epochs[-1] - self.retention_seconds
        idx = bisect.bisect_left(self._epochs, cutoff)
        if idx > 0:
            for i in range(idx):
                self._seen.discard(self._points[i]["key"])
            del self._epochs[:idx]
            del self._points[:idx]

    def earliest_epoch(self) -> float | None:
        return self._epochs[0] if self._epochs else None

    def latest_epoch(self) -> float | None:
        return self._epochs[-1] if self._epochs else None

    def __len__(self) -> int:
        return len(self._epochs)

    def window(self, start_epoch: float, end_epoch: float):
        """(epochs, {field: values}) for points with start <= epoch <= end, in
        time order. A missing field reads back as 0.0."""
        lo = bisect.bisect_left(self._epochs, start_epoch)
        hi = bisect.bisect_right(self._epochs, end_epoch)
        epochs = self._epochs[lo:hi]
        series = {
            field: [self._points[i]["values"][field] for i in range(lo, hi)]
            for field in PM_FIELDS
        }
        return epochs, series

    @classmethod
    def from_rows(cls, columns, rows, csv_name=None, **kw) -> "TimeSeriesHistory":
        """Build a history from a CSV-style ``(columns, rows)`` payload. When
        ``csv_name`` is given every row is tagged with it so the file's rows
        de-dup as one series. Extra kwargs pass through to ``__init__``."""
        hist = cls(**kw)
        prepared = []
        for reading in wire.rows_to_readings(columns, rows):
            if csv_name is not None:
                reading = dict(reading)
                reading["csv"] = csv_name
            prepared.append(reading)
        hist.merge(prepared)
        return hist


# --- view state + computation -----------------------------------------------
# Distinct small markers per series. The four PM series are frequently
# bit-identical in clean air (pm2_5 == pm4_0 == pm10); giving each its own
# marker lets the coincident lines interleave visibly instead of overdrawing.
# All four are small sub-cell / single-glyph markers (no full-block "hd"/"sd")
# so none visually dominates, and all four are distinct so no two coincident
# series ever share a glyph.
_SERIES_MARKERS = {
    "pm1_0": "braille",
    "pm2_5": "dot",       # plotext "•"
    "pm4_0": "·",         # middle dot
    "pm10": "∘",          # ring operator
}

# Roughly this many evenly spaced ticks on the time axis (within the 5-7 the
# contract asks for).
_TICK_COUNT = 6

# Span thresholds (seconds) that pick the x-label format.
_SPAN_SECONDS_10MIN = 600.0
_SPAN_SECONDS_24H = 86400.0


class ChartView:
    """View state plus the computation of what to draw. Holds no plot - its
    ``compute`` returns plain data for the GUI to feed to plotext.

    Two modes:
      * ``moving`` - right edge tracks ``now``, left edge is ``now - window``.
      * ``pinned`` - left edge fixed at ``pin_start``, right edge grows with
        ``now``; the window size does not clip the left, so toggling back to
        pinned restores the whole curve from the pin time onward.
    """

    def __init__(self, window_seconds: float = 120.0, marker: str = "braille"):
        self.window_seconds = float(window_seconds)
        self.marker = marker
        self.mode = "moving"
        # Left edge for pinned mode; None until pinned is first entered.
        self.pin_start: float | None = None
        # Frozen absolute (xmin, xmax) epochs; None means follow the live
        # moving/pinned logic. When set it overrides mode for ``visible_range``.
        self.x_fixed: tuple[float, float] | None = None
        # Y-axis control. ``y_auto`` True lets the GUI fit y to the data; False
        # pins the y range to (y_min, y_max). y_min/y_max are None until frozen.
        self.y_auto: bool = True
        self.y_min: float | None = None
        self.y_max: float | None = None

    def set_window(self, seconds: float) -> None:
        """Set the moving-window span in seconds (must be > 0)."""
        seconds = float(seconds)
        if seconds <= 0:
            raise ValueError(f"window must be positive, got {seconds}")
        self.window_seconds = seconds

    def set_mode(self, mode: str, now_epoch: float) -> None:
        """Switch to ``moving`` or ``pinned``. Entering pinned with no pin yet
        pins the left edge at ``now - window`` (the current moving left edge),
        so the view does not jump. A pin already set is preserved."""
        if mode not in ("moving", "pinned"):
            raise ValueError(f"unknown mode {mode!r}")
        if mode == "pinned" and self.pin_start is None:
            self.pin_start = now_epoch - self.window_seconds
        self.mode = mode

    def toggle_mode(self, now_epoch: float) -> str:
        """Flip between moving and pinned; returns the new mode."""
        new_mode = "pinned" if self.mode == "moving" else "moving"
        self.set_mode(new_mode, now_epoch)
        return new_mode

    def visible_range(self, now_epoch: float):
        """The (start, end) epoch pair to display. A frozen ``x_fixed`` range
        wins verbatim; otherwise moving -> (now-window, now) and
        pinned -> (pin_start, now)."""
        if self.x_fixed is not None:
            return self.x_fixed
        if self.mode == "pinned":
            start = self.pin_start
            if start is None:
                start = now_epoch - self.window_seconds
            return start, now_epoch
        return now_epoch - self.window_seconds, now_epoch

    # --- x-axis freeze / pan / zoom -----------------------------------------
    def x_frozen(self) -> bool:
        """True when an absolute x range is pinned (``x_fixed`` set)."""
        return self.x_fixed is not None

    def set_x_range(self, x_min: float, x_max: float) -> None:
        """Freeze the x axis to an absolute (x_min, x_max) epoch pair. Requires
        x_min < x_max, else ValueError."""
        if not x_min < x_max:
            raise ValueError(f"x_min must be < x_max, got {x_min} >= {x_max}")
        self.x_fixed = (float(x_min), float(x_max))

    def clear_x_range(self) -> None:
        """Drop any frozen x range and return to the live moving/pinned logic."""
        self.x_fixed = None

    def freeze_x(self, now_epoch: float) -> None:
        """Capture the current visible range into ``x_fixed`` so the x axis stops
        following the live present."""
        start, end = self.visible_range(now_epoch)
        self.x_fixed = (float(start), float(end))

    def x_autoscale(self, on: bool, now_epoch: float) -> None:
        """on True -> resume following live (clear the frozen range);
        on False -> freeze the current visible range in place."""
        if on:
            self.clear_x_range()
        else:
            self.freeze_x(now_epoch)

    def pan(self, delta_seconds: float, now_epoch: float) -> None:
        """Shift the visible x window by ``delta_seconds`` (positive = later).

        Frozen: shift both edges. Pinned: move ``pin_start`` (initialised from
        the current visible start if unset). Moving: freeze the current visible
        range shifted by delta, so panning a live view stops it tracking now."""
        delta = float(delta_seconds)
        if self.x_fixed is not None:
            lo, hi = self.x_fixed
            self.x_fixed = (lo + delta, hi + delta)
        elif self.mode == "pinned":
            if self.pin_start is None:
                self.pin_start = self.visible_range(now_epoch)[0]
            self.pin_start += delta
        else:
            start, end = self.visible_range(now_epoch)
            self.x_fixed = (start + delta, end + delta)

    def shift_pin(self, delta_seconds: float, now_epoch: float) -> None:
        """Move the pin left edge by ``delta_seconds``, entering pinned mode
        first (which initialises ``pin_start``) if not already pinned."""
        if self.mode != "pinned":
            self.set_mode("pinned", now_epoch)
        self.pin_start += float(delta_seconds)

    def zoom(self, factor: float, now_epoch: float) -> None:
        """Scale the visible x span by ``factor`` (> 0, else ValueError) about the
        visible centre: factor < 1 zooms in (narrower), > 1 zooms out.

        Frozen: scale ``x_fixed`` around its centre. Pinned: pull ``pin_start``
        toward/away from now by the factor (initialised from the visible start if
        unset). Moving: scale ``window_seconds``."""
        if factor <= 0:
            raise ValueError(f"zoom factor must be positive, got {factor}")
        factor = float(factor)
        if self.x_fixed is not None:
            lo, hi = self.x_fixed
            center = (lo + hi) / 2.0
            half = (hi - lo) / 2.0 * factor
            self.x_fixed = (center - half, center + half)
        elif self.mode == "pinned":
            pin = self.pin_start
            if pin is None:
                pin = self.visible_range(now_epoch)[0]
            self.pin_start = now_epoch - (now_epoch - pin) * factor
        else:
            self.set_window(self.window_seconds * factor)

    # --- y-axis freeze ------------------------------------------------------
    def set_y_range(self, y_min: float, y_max: float) -> None:
        """Freeze the y axis to (y_min, y_max). Requires y_min < y_max, else
        ValueError. Turns off y autoscale."""
        if not y_min < y_max:
            raise ValueError(f"y_min must be < y_max, got {y_min} >= {y_max}")
        self.y_auto = False
        self.y_min = float(y_min)
        self.y_max = float(y_max)

    def y_autoscale(self, on: bool, data_min=None, data_max=None) -> None:
        """on True -> resume fitting y to the data (``y_auto`` True). on False ->
        stop autoscaling; if no manual range is set yet and a data range is given,
        freeze at that current data extent."""
        if on:
            self.y_auto = True
        else:
            self.y_auto = False
            if self.y_min is None and self.y_max is None \
                    and data_min is not None and data_max is not None:
                self.y_min = float(data_min)
                self.y_max = float(data_max)

    def freeze_y(self, data_min: float, data_max: float) -> None:
        """Pin the y axis to (data_min, data_max), turning off autoscale."""
        self.y_auto = False
        self.y_min = float(data_min)
        self.y_max = float(data_max)

    def _label_format(self, span_seconds: float) -> str:
        """Time-label format string chosen by visible span."""
        if span_seconds < _SPAN_SECONDS_10MIN:
            return "%H:%M:%S"
        if span_seconds < _SPAN_SECONDS_24H:
            return "%H:%M"
        return "%m-%d %H:%M"

    def _ticks(self, start: float, end: float):
        """~5-7 evenly spaced tick epochs across [start, end]."""
        span = end - start
        if span <= 0:
            return [start]
        return [start + i * span / (_TICK_COUNT - 1) for i in range(_TICK_COUNT)]

    @staticmethod
    def _decimate(epochs, series, max_points):
        """Thin a visible slice to <= ``max_points`` samples by stride, always
        keeping the first and last point so the live right edge stays exact.

        At ~1 reading/s a 48h store holds ~170k points and ``compute`` runs on
        the 1 Hz render loop; a multi-hour pinned span would otherwise ship far
        more points than any terminal can draw. Stride decimation (every k-th,
        k = ceil(n / max_points)) is O(n) over the already-small visible slice.
        """
        n = len(epochs)
        if not max_points or n <= max_points:
            return epochs, series
        if max_points < 2:
            # Degenerate cap: keep just the live right edge.
            return epochs[-1:], {f: vals[-1:] for f, vals in series.items()}
        # Stride sized so the kept indices plus the forced last point never
        # exceed max_points: k = ceil((n-1) / (max_points-1)).
        k = -(-(n - 1) // (max_points - 1))
        keep = list(range(0, n, k))
        if keep[-1] != n - 1:
            keep.append(n - 1)  # never drop the live right edge
        thin_epochs = [epochs[i] for i in keep]
        thin_series = {f: [vals[i] for i in keep] for f, vals in series.items()}
        return thin_epochs, thin_series

    def compute(self, history: "TimeSeriesHistory", now_epoch: float,
                max_points: int | None = None) -> dict:
        """Compute the drawable state for ``now_epoch`` against ``history``.

        Returns a dict with keys ``x`` (epochs), ``series`` ({field: values}),
        ``xticks`` (epochs), ``xlabels`` (Eastern local-time strings), ``start``,
        ``end``, ``mode``, ``window_seconds``, ``markers`` ({field: marker}) and
        ``span_seconds``. The label format adapts to the span (<10min HH:MM:SS,
        <24h HH:MM, else MM-DD HH:MM) in Eastern time.

        Axis-control keys: ``y_auto`` (bool), ``y_min``/``y_max`` (frozen manual
        y range or None), ``data_ymin``/``data_ymax`` (the min/max across all four
        PM series over the full visible slice, computed before decimation, or None
        when the slice is empty) and ``x_frozen`` (bool - whether an absolute x
        range is pinned). ``start``/``end`` already reflect a frozen x range.

        When ``max_points`` is set and the visible slice exceeds it, ``x`` and
        every series are decimated to <= ``max_points`` samples (first and last
        always kept). ``xticks``/``xlabels`` come from the range, not the
        samples, so they are unaffected by decimation.
        """
        start, end = self.visible_range(now_epoch)
        span_seconds = end - start
        epochs, series = history.window(start, end)

        # Y extent across all four PM series over the FULL visible slice, taken
        # BEFORE decimation so freezing y captures the true (un-thinned) extent.
        data_ymin = None
        data_ymax = None
        for field in PM_FIELDS:
            vals = series[field]
            if vals:
                lo = min(vals)
                hi = max(vals)
                data_ymin = lo if data_ymin is None else min(data_ymin, lo)
                data_ymax = hi if data_ymax is None else max(data_ymax, hi)

        epochs, series = self._decimate(epochs, series, max_points)

        xticks = self._ticks(start, end)
        fmt = self._label_format(span_seconds)
        xlabels = [
            datetime.fromtimestamp(tick, wire.EASTERN).strftime(fmt)
            for tick in xticks
        ]

        return {
            "x": epochs,
            "series": series,
            "xticks": xticks,
            "xlabels": xlabels,
            "start": start,
            "end": end,
            "mode": self.mode,
            "window_seconds": self.window_seconds,
            "markers": dict(_SERIES_MARKERS),
            "span_seconds": span_seconds,
            "y_auto": self.y_auto,
            "y_min": self.y_min,
            "y_max": self.y_max,
            "data_ymin": data_ymin,
            "data_ymax": data_ymax,
            "x_frozen": self.x_frozen(),
        }
