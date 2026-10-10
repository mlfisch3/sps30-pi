# Live-chart redesign - design & interface contract

> **Historical work order, 2026-07-23. Completed.**
> This was the brief for the chart redesign on the `dashboard-features` branch,
> written before that work was done. It is kept because the *reasoning* is still
> the reasoning behind today's chart, and because one fact in it must never be
> lost (see "Non-goal" below).
> It is **not** a description of the current chart. The Kotlin `ChartView` and
> `PmChart` are ports of what this brief produced and have moved a long way since:
> averaging intervals, an Auto control, a logarithmic axis, the inspection
> crosshair, and the axis rules are all later.
> Current chart requirements: `PRD.md` `A-20` to `A-29`.
>
> **The one thing to carry forward from this document:** PM2.5, PM4.0 and PM10 are
> cumulative measures and are legitimately **equal** in clean air. Never try to
> separate them. It looks like a bug and is not.

Orchestration doc for the SPS30 dashboard chart/view upgrade. All work happened
on branch `dashboard-features` (worktree `SENSIRION-dashboard-features`), based on
the `dashboard` branch which already contains the resume/restart streaming fix.

## Goals (from user feedback)
1. X-axis shows **local (Eastern) clock time**, not a bare sample index.
2. Set the visible history by **duration** (`12h`, `18m`, `1800s`, `1h30m`) - no
   repeated `+` presses.
3. Retain full-resolution data in memory (hours) so shrinking then growing the
   window **restores** the earlier curve rather than losing it.
4. **Backfill on grow:** when the window grows past what is in memory, quietly
   pull older rows from the Pi's CSV files to fill it.
5. **Import earlier data into the live view** and keep appending live readings to
   the same series (not a separate frozen "imported" view).
6. **Pin vs moving-window toggle:**
   - *moving*: right edge tracks the live present (`now`), left edge = `now - window`.
   - *pinned*: left edge fixed at the pin time, right edge = live present (grows);
     window size does not clip the left. Toggling back to pinned restores the full
     curve from the pin time to now.
7. **Smaller point markers** so fine structure is visible over long histories.
8. Keep the current framework (Textual + `textual-plotext` + `plotext`).

## Non-goal / fact to preserve
PM2.5, PM4.0, PM10 are *cumulative* and are legitimately **equal** in clean air
(confirmed bit-identical in real CSVs). Do NOT try to separate equal values.
Mitigate overdraw only by giving each series a distinct small marker so coincident
lines interleave visibly. Never fabricate separation.

## Module split (avoid collisions)
- `chartview.py` - NEW pure-logic module (no Textual, no socket). Owned by the
  chartview coding agent. Fully unit-tested in `tests/test_chartview.py`.
- `sps30_gui.py` - integration (rendering, key bindings, duration input widget,
  pin/moving toggle, backfill-on-grow, live import). Owned by the orchestrator to
  keep the single large file conflict-free.
- `sps30_daemon.py` - may gain multi-file / older-row fetch support if needed for
  backfill-on-grow across historical CSVs (orchestrator).

## `chartview.py` public API (the contract)

```python
def parse_duration(text: str) -> float:
    """Human duration -> seconds (float, > 0).
    Accepts: bare number = seconds ("1800"); unit suffixes s/sec/secs/second(s),
    m/min(s)/minute(s), h/hr(s)/hour(s), d/day(s); combos ("1h30m", "2h15m30s");
    optional spaces and word units ("12 hours"). Case-insensitive.
    Raises ValueError on empty/unparseable/non-positive input."""

PM_FIELDS = ["pm1_0", "pm2_5", "pm4_0", "pm10"]  # re-exported for convenience

class TimeSeriesHistory:
    """Full-resolution multi-series store keyed on (csv_name, index), ordered by
    time. Epochs are float seconds (UTC epoch from the reading's ISO timestamp).
    De-dup is by (csv_name or '', index); live readings restart index per csv."""
    def __init__(self, retention_seconds: float = 172800.0): ...   # 48h default
    def add(self, reading: dict) -> bool: ...        # one live reading; True if new
    def merge(self, readings) -> int: ...            # older/backfill rows; #added
    def set_retention(self, seconds: float) -> None: ...
    def earliest_epoch(self) -> float | None: ...
    def latest_epoch(self) -> float | None: ...
    def __len__(self) -> int: ...
    def window(self, start_epoch: float, end_epoch: float) -> tuple[list[float], dict[str, list[float]]]:
        """(epochs, {field: values}) for points with start <= epoch <= end,
        in time order. Missing field -> 0.0."""
    @classmethod
    def from_rows(cls, columns, rows, **kw) -> "TimeSeriesHistory": ...

class ChartView:
    """View state + computation of what to draw. No plotting here - returns data."""
    def __init__(self, window_seconds: float = 120.0, marker: str = "braille"): ...
    mode: str            # "moving" | "pinned"
    window_seconds: float
    def set_window(self, seconds: float) -> None: ...
    def set_mode(self, mode: str, now_epoch: float) -> None: ...
    def toggle_mode(self, now_epoch: float) -> str: ...   # returns new mode
    def visible_range(self, now_epoch: float) -> tuple[float, float]:
        """moving -> (now-window, now); pinned -> (pin_start, now)."""
    def compute(self, history: TimeSeriesHistory, now_epoch: float) -> dict:
        """Return {
           'x': [epoch...], 'series': {field: [vals...]},
           'xticks': [epoch...], 'xlabels': [local-time str...],
           'start': float, 'end': float, 'mode': str, 'window_seconds': float,
           'markers': {field: marker_str}, 'span_seconds': float }.
        Label format adapts to span: <10min HH:MM:SS, <24h HH:MM, else MM-DD HH:MM,
        Eastern tz. ~5-7 ticks. When pinned and pin_start unset, pin at now-window."""
```

## Code-review triage (orchestrator decisions)
- ACCEPTED (folded into chartview): `window()` must slice via the stdlib
  `bisect` module (O(log n + k), never a linear scan); `merge()` keeps the list
  sorted (bisect.insort) and de-dup for add/merge is a set/dict keyed on
  `(csv_name or '', index)`. `compute()` gains `max_points=None`; when set it
  decimates the returned `x`/`series` to <= max_points while always keeping the
  first and last visible point (xticks/xlabels come from the range, unaffected).
  Rationale: 48h x 1 Hz ~= 170k points and compute runs on the 1 Hz render loop;
  pinned mode can show multi-hour spans.
- DECLINED (for now): using plotext's native datetime axis instead of our
  adaptive tick/label logic (plotext auto-tick selection is weak for the
  <10min/<24h/else formats; re-evaluating mid-flight is churn). Keep bespoke.
- DECLINED: factoring the daemon/gui recv-loops into `wire.read_messages`
  (marginal; wire.py stays untouched by the feature work). No pandas/numpy
  (too heavy for a Pi-over-SSH TUI; bisect+decimation covers the perf need).

## Code-review triage round 2 (after the redesign landed)
- ACCEPTED: file-transfer chunks must not share the live drop-oldest fan-out
  queue (a dropped middle chunk corrupts a file; a dropped final `done` chunk
  wedges the client's transfer queue forever). Daemon `ClientHandler` now uses a
  deque + Condition with a per-message `lossless` flag: live readings/state stay
  drop-oldest; file-data/list/raw/error responses are lossless (queue exceeds the
  soft cap only while nothing droppable remains). Client also gained a 30s
  transfer-stall watchdog so a mid-transfer disconnect cannot wedge later
  backfills.
- ACCEPTED: a long-lived pin must keep its fixed left edge within retention as
  `now` advances (else `_trim` eats it). `_render_chart` bumps retention to cover
  `now - pin_start`.
- DEFERRED (documented, not blocking a working+validated build): (a) push
  `max_points` into `window()` so `compute` does not materialize the full visible
  slice before decimating; (b) add a `since_index`/`until_index` (or epoch) range
  to `CMD_GET_FILE` so growing a window pulls only the needed span instead of
  whole files; (c) min/max-per-bucket (envelope) decimation to preserve transient
  PM spikes over long spans. All are perf/fidelity refinements; the client runs
  on the endpoint (not the Pi), so the cost is milder than a Pi-side render would
  be. Revisit after round-1 UX is validated. Also deferred: collapse the small
  duplicated helpers (gui.index_int vs chartview._index_of, gui._epoch_of_iso vs
  chartview._epoch_of, gui PM_FIELDS vs chartview.PM_FIELDS).

## Code-review triage round 3 (clear / local-import / axis controls)
- ACCEPTED: clear-session must NOT reset the file-request queue mid-transfer (the
  daemon keeps sending the in-flight file's chunks; a fresh queue misattributes
  them to the next transfer). Clear now drops only *queued* requests and marks
  the in-flight one to drain-and-swallow (`_swallow_inflight`); its result is
  discarded on completion.
- ACCEPTED: setting a window duration now switches to moving mode (pinned ignores
  the window, so it looked inert); zoom has a 2s floor; panning a live view logs
  that it froze the x-axis.
- ACCEPTED: consolidated the "grow retention to cover an epoch" logic into one
  `_ensure_retention_covers` helper (was open-coded 4x); local-archive rows are
  keyed under a `local:` csv namespace so they can never collide with Pi imports.
- ACCEPTED (partial): local file-list row counts are cached by (name,size,mtime)
  so re-listing does not re-count unchanged CSVs.
- DEFERRED: running `_import_local`'s file read/parse off the Textual event loop
  (a bounded one-shot freeze on the endpoint - not the Pi - for very large
  archives). Revisit if it bites. Also still deferred: the round-2 perf items
  (window-level decimation, range-fetch protocol, envelope decimation) and the
  minor helper de-duplication (gui.index_int / _epoch_of_iso / PM_FIELDS).

### Notes for the implementer
- Reuse `wire` for constants (`wire.EASTERN`, `wire.FIELDS`, `wire.CSV_COLUMNS`,
  `wire.rows_to_readings`, `wire.iso_timestamp`). Keep dependency-light (stdlib).
- ISO timestamp -> epoch: `datetime.fromisoformat(ts).timestamp()`; be resilient
  to missing/blank timestamps (skip or synthesize via index order).
- Each series gets a distinct small marker to make coincident (equal) lines
  interleave; expose via `markers` in `compute`. Suggested: alternate
  "braille"/"dot" across the four series.
- Do not clip left edge by window in pinned mode.
