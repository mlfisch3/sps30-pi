package com.sensirion.sps30.data

import java.time.Instant
import kotlin.math.ceil
import java.time.format.DateTimeFormatter

enum class ChartMode { MOVING, PINNED }

/**
 * The user-facing view mode, derived from the raw state:
 *  - [LIVE]: right edge tracks now, left = now - window (a clean live window).
 *  - [LIVE_FROZEN]: still following-capable but the x range was frozen by a
 *    touch gesture; neither Live nor Pin reads as "selected".
 *  - [PIN]: left edge pinned, right grows with now.
 *  - [ALL]: left edge pinned at the session start, right grows with now - the
 *    whole current acquisition session, autoscaled to fill the plot width.
 */
enum class ViewMode { LIVE, LIVE_FROZEN, PIN, ALL }

/** Immutable drawable state produced by [ChartView.compute]. */
data class ChartData(
    val x: DoubleArray,
    val series: Map<String, DoubleArray>,
    val start: Double,
    val end: Double,
    val xTicks: DoubleArray,
    val xLabels: List<String>,
    /** Date shown under a tick where the day changes; blank on other ticks. */
    val xDates: List<String> = emptyList(),
    /** Short name of the zone the x axis is plotted in, e.g. "EDT". */
    val timeZoneLabel: String = "",
    val dataYMin: Double?,
    val dataYMax: Double?,
    val spanSeconds: Double,
    val sampleCount: Int,
    val mode: ChartMode,
    val viewMode: ViewMode,
    val windowSeconds: Double,
    val yAuto: Boolean,
    val yMin: Double?,
    val yMax: Double?,
    val xFrozen: Boolean,
    val hasReferencePin: Boolean,
    /** Averaging bucket applied to these points, or null when they are raw. */
    val bucketSeconds: Double? = null,
    /**
     * Time gap beyond which two consecutive points must not be joined by a line,
     * or null when the run is too short to judge.
     *
     * Nothing was recorded across such a gap, so a line drawn over it is an
     * invention - and a confident-looking one, since it interpolates smoothly
     * between two moments that may be days apart. Left to the renderer rather
     * than expressed by splitting the series here, because every consumer other
     * than the polyline (autoscale, the crosshair, export) wants the points
     * whole.
     */
    val gapSeconds: Double? = null,
    /** True when the bucket is being chosen automatically from the visible span. */
    val autoInterval: Boolean = false,
    /** The averaging auto last settled on, offered as its own button. */
    val autoChosenSeconds: Double? = null,
)

/**
 * View state plus the computation of what to draw - a full Kotlin port of
 * `chartview.ChartView`. Holds no drawing; [compute] returns plain data.
 *
 * Modes: [ChartMode.MOVING] (right edge tracks now, left = now - window) and
 * [ChartMode.PINNED] (left edge fixed at [pinStart], right grows with now). An
 * [xFixed] absolute range, when set, overrides mode. Y is either autoscaled or
 * pinned to (yMin, yMax).
 */
class ChartView(windowSeconds: Double = 300.0) {

    var windowSeconds: Double = windowSeconds
        private set
    var mode: ChartMode = ChartMode.MOVING
        private set
    var pinStart: Double? = null
        private set
    var xFixed: Pair<Double, Double>? = null
        private set
    // When set, the view shows the whole session [allStart, now] ("all" button),
    // overriding mode/xFixed/pinStart until any other navigation clears it.
    private var allStart: Double? = null
    var yAuto: Boolean = true
        private set
    var yMin: Double? = null
        private set
    var yMax: Double? = null
        private set

    // Recent pin origins (newest first) that the Pin button cycles through, plus
    // a separate user-set reference pin the long-press snaps to.
    var referencePinTime: Double? = null
        private set
    var pinCycleCount: Int = DEFAULT_PIN_CYCLE
        private set
    private val pinHistory = ArrayDeque<Double>()
    private var pinCycleIndex = 0

    /** Derived, user-facing mode. */
    fun viewMode(): ViewMode = when {
        allStart != null -> ViewMode.ALL
        mode == ChartMode.PINNED -> ViewMode.PIN
        xFixed != null -> ViewMode.LIVE_FROZEN
        else -> ViewMode.LIVE
    }

    fun setPinCycleCount(n: Int) {
        pinCycleCount = n.coerceAtLeast(1)
    }

    // --- window / mode ------------------------------------------------------
    fun setWindow(seconds: Double) {
        require(seconds > 0) { "window must be positive, got $seconds" }
        windowSeconds = seconds
    }

    fun setMode(mode: ChartMode, nowEpoch: Double) {
        allStart = null
        if (mode == ChartMode.PINNED && pinStart == null) {
            pinStart = nowEpoch - windowSeconds
        }
        this.mode = mode
    }

    fun toggleMode(nowEpoch: Double): ChartMode {
        setMode(if (mode == ChartMode.MOVING) ChartMode.PINNED else ChartMode.MOVING, nowEpoch)
        return mode
    }

    fun visibleRange(nowEpoch: Double): Pair<Double, Double> {
        allStart?.let { return it to nowEpoch }
        xFixed?.let { return it }
        if (mode == ChartMode.PINNED) {
            val start = pinStart ?: (nowEpoch - windowSeconds)
            return start to nowEpoch
        }
        return (nowEpoch - windowSeconds) to nowEpoch
    }

    // --- x-axis freeze / pan / zoom -----------------------------------------
    fun xFrozen(): Boolean = xFixed != null

    fun setXRange(xMin: Double, xMax: Double) {
        require(xMin < xMax) { "xMin must be < xMax, got $xMin >= $xMax" }
        allStart = null
        xFixed = xMin to xMax
    }

    fun clearXRange() { xFixed = null }

    fun freezeX(nowEpoch: Double) {
        val (start, end) = visibleRange(nowEpoch)
        allStart = null
        xFixed = start to end
    }

    fun xAutoscale(on: Boolean, nowEpoch: Double) {
        if (on) clearXRange() else freezeX(nowEpoch)
    }

    fun pan(deltaSeconds: Double, nowEpoch: Double) {
        if (allStart != null) freezeX(nowEpoch) // leave "all": freeze the session view, then pan it
        val fixed = xFixed
        when {
            fixed != null -> xFixed = (fixed.first + deltaSeconds) to (fixed.second + deltaSeconds)
            mode == ChartMode.PINNED -> {
                val start = pinStart ?: visibleRange(nowEpoch).first
                pinStart = start + deltaSeconds
            }
            else -> {
                val (start, end) = visibleRange(nowEpoch)
                xFixed = (start + deltaSeconds) to (end + deltaSeconds)
            }
        }
    }

    fun shiftPin(deltaSeconds: Double, nowEpoch: Double) {
        allStart = null
        if (mode != ChartMode.PINNED) setMode(ChartMode.PINNED, nowEpoch)
        pinStart = (pinStart ?: (nowEpoch - windowSeconds)) + deltaSeconds
    }

    fun zoom(factor: Double, nowEpoch: Double) {
        require(factor > 0) { "zoom factor must be positive, got $factor" }
        if (allStart != null) freezeX(nowEpoch) // leave "all": freeze the session view, then zoom it
        val fixed = xFixed
        when {
            fixed != null -> {
                val center = (fixed.first + fixed.second) / 2.0
                val half = (fixed.second - fixed.first) / 2.0 * factor
                xFixed = (center - half) to (center + half)
            }
            mode == ChartMode.PINNED -> {
                val pin = pinStart ?: visibleRange(nowEpoch).first
                pinStart = nowEpoch - (nowEpoch - pin) * factor
            }
            else -> setWindow(windowSeconds * factor)
        }
    }

    // --- y-axis -------------------------------------------------------------
    fun setYRange(min: Double, max: Double) {
        require(min < max) { "yMin must be < yMax, got $min >= $max" }
        yAuto = false
        yMin = min
        yMax = max
    }

    fun freezeY(dataMin: Double, dataMax: Double) {
        yAuto = false
        yMin = dataMin
        yMax = dataMax
    }

    fun yAutoscale(on: Boolean, dataMin: Double? = null, dataMax: Double? = null) {
        if (on) {
            yAuto = true
        } else {
            yAuto = false
            if (yMin == null && yMax == null && dataMin != null && dataMax != null) {
                yMin = dataMin
                yMax = dataMax
            }
        }
    }

    // --- Live / Pin buttons -------------------------------------------------

    /**
     * The "Live" button: return to a clean moving window whose size is the
     * currently displayed span, so what you were looking at stays put but starts
     * following now again. (In clean Live the span already equals [windowSeconds].)
     */
    fun goLive(nowEpoch: Double) {
        val (start, end) = visibleRange(nowEpoch)
        windowSeconds = (end - start).coerceAtLeast(MIN_WINDOW)
        allStart = null
        mode = ChartMode.MOVING
        xFixed = null
    }

    /**
     * The "all" button: show the whole current acquisition session -
     * [sessionStartEpoch] (the first stable reading after the last Restart) to now -
     * autoscaling x so all the data fills the plot width, right edge growing with now.
     * A dedicated [ViewMode.ALL]; any other navigation returns to normal modes.
     */
    fun applyAll(sessionStartEpoch: Double, nowEpoch: Double) {
        xFixed = null
        mode = ChartMode.MOVING
        allStart = minOf(sessionStartEpoch, nowEpoch)
        windowSeconds = (nowEpoch - allStart!!).coerceAtLeast(MIN_WINDOW)
    }

    /**
     * A window-size button. In Live/Live-frozen it enters a clean live window of
     * [seconds]. In Pin mode it re-pins the origin at now - [seconds] (keeping
     * the left edge pinned there while the right edge grows).
     */
    fun applyWindow(seconds: Double, nowEpoch: Double) {
        require(seconds > 0) { "window must be positive, got $seconds" }
        allStart = null
        windowSeconds = seconds
        if (viewMode() == ViewMode.PIN) {
            pinStart = nowEpoch - seconds
        } else {
            mode = ChartMode.MOVING
            xFixed = null
        }
    }

    /**
     * A short tap on "Pin". From Live/Live-frozen: enter Pin, pinning the origin
     * at the current left edge (recorded for cycling). In Pin mode: cycle to the
     * next of the [pinCycleCount] most-recent pin origins.
     */
    fun pinTap(nowEpoch: Double) {
        if (viewMode() == ViewMode.PIN) {
            cyclePin()
        } else {
            enterPinAt(visibleRange(nowEpoch).first)
        }
    }

    /**
     * A long press on "Pin". In Pin mode: snap the origin to the reference pin
     * (if one is set). In Live/Live-frozen: set the reference pin to the current
     * left edge WITHOUT switching to Pin mode.
     */
    fun pinLongPress(nowEpoch: Double) {
        if (viewMode() == ViewMode.PIN) {
            referencePinTime?.let { pinStart = it }
        } else {
            referencePinTime = visibleRange(nowEpoch).first
        }
    }

    private fun enterPinAt(pin: Double) {
        if (pinHistory.firstOrNull() != pin) pinHistory.addFirst(pin)
        while (pinHistory.size > MAX_PIN_HISTORY) pinHistory.removeLast()
        pinCycleIndex = 0
        pinStart = pin
        allStart = null
        xFixed = null
        mode = ChartMode.PINNED
    }

    /**
     * Commit a manually dragged pin so it re-enters the Pin cycle. After the user
     * drags the curve in Pin mode the resulting [pinStart] becomes the newest entry
     * in the cycle set, so tapping Pin brings it back (call on drag end). No-op
     * outside Pin mode. Idempotent when the current pin is already the newest entry.
     */
    fun commitManualPin() {
        if (viewMode() != ViewMode.PIN) return
        val p = pinStart ?: return
        if (pinHistory.firstOrNull() != p) pinHistory.addFirst(p)
        while (pinHistory.size > MAX_PIN_HISTORY) pinHistory.removeLast()
        pinCycleIndex = 0
    }

    private fun cyclePin() {
        val n = minOf(pinCycleCount, pinHistory.size)
        if (n <= 1) return
        pinCycleIndex = (pinCycleIndex + 1) % n
        pinStart = pinHistory[pinCycleIndex]
    }

    // --- plot interval / averaging ------------------------------------------

    /** Explicit averaging bucket in seconds; null or <= 1 means raw samples. */
    var plotIntervalSeconds: Double? = null
        private set

    /** When true the bucket is derived from the visible span on every compute. */
    var autoInterval: Boolean = false
        private set

    /**
     * The averaging auto last settled on, in whole seconds.
     *
     * Auto computes the value that actually suits the data rather than rounding
     * to a preset - constrained to the offered buttons it kept landing on one
     * step too little smoothing and the next step too much. The UI turns this
     * into a button of its own, so the chosen figure is both visible and
     * something the user can toggle back to after looking at raw.
     */
    var autoChosenSeconds: Double? = null
        private set

    fun setPlotInterval(seconds: Double?) {
        // Choosing an interval by hand is an override, so it ends auto mode -
        // but the value auto picked stays available as a button, so raw and
        // "what auto decided" remain one tap apart.
        autoInterval = false
        plotIntervalSeconds = seconds?.takeIf { it > 1.0 }
    }

    /** Let the view pick its own averaging from how much is on screen. */
    fun setAutoInterval() {
        autoInterval = true
        plotIntervalSeconds = null
    }

    /**
     * The bucket actually used for this frame.
     *
     * In auto mode it follows the visible span, which is what makes averaging
     * fall away by itself: zooming in shrinks the span, the target point count
     * stops being the binding constraint, and the ladder drops back to raw
     * samples without the user having to know averaging was ever happening.
     */
    private fun effectiveBucketSeconds(span: Double, maxPoints: Int?): Double? {
        if (!autoInterval) return plotIntervalSeconds
        val target = (maxPoints ?: 600).coerceAtLeast(60)
        val ideal = span / target
        // Averaging only earns its place once raw data would overrun the point
        // budget. Zoomed in far enough that every sample fits, raw is the honest
        // answer - otherwise auto would keep smoothing data that needs no help.
        if (ideal <= RAW_SAMPLE_PERIOD) {
            autoChosenSeconds = null
            return null
        }
        // Whole seconds: sub-second precision in an averaging window is noise in
        // the label and makes no visible difference to the curve.
        val chosen = ceil(ideal).coerceAtLeast(2.0)
        autoChosenSeconds = chosen
        return chosen
    }

    // --- compute ------------------------------------------------------------
    fun compute(
        history: TimeSeriesHistory,
        nowEpoch: Double,
        maxPoints: Int? = null,
        excludeFlagged: Boolean = false,
    ): ChartData {
        val (start, end) = visibleRange(nowEpoch)
        val span = end - start
        val bucket = effectiveBucketSeconds(span, maxPoints)
        // Decimation happens inside the extraction walk, so a multi-day window
        // costs one pass over primitives instead of materialising every point.
        // The y range still reflects the full window, not the emitted subset.
        val w = history.window(start, end, excludeFlagged, maxPoints, bucket)
        val ticks = ticks(start, end)
        val fmt = labelFormatter(span)
        val labels = ticks.map { label(it, fmt) }

        return ChartData(
            x = w.epochs,
            series = w.series,
            start = start,
            end = end,
            xTicks = ticks,
            xLabels = labels,
            xDates = dateMarks(ticks),
            timeZoneLabel = zoneAbbreviation(end),
            dataYMin = w.yMin,
            dataYMax = w.yMax,
            spanSeconds = span,
            sampleCount = w.totalCount,
            mode = mode,
            viewMode = viewMode(),
            windowSeconds = windowSeconds,
            yAuto = yAuto,
            yMin = yMin,
            yMax = yMax,
            xFrozen = xFrozen(),
            hasReferencePin = referencePinTime != null,
            bucketSeconds = bucket,
            autoInterval = autoInterval,
            autoChosenSeconds = autoChosenSeconds,
            gapSeconds = gapThreshold(w.epochs, bucket),
        )
    }

    /**
     * Tick positions at the centres of equal bands, rather than at the range's
     * two ends and evenly between.
     *
     * End-of-range ticks put half a label outside the plot. Aligning those two
     * to the edges keeps them on screen but shoves them inward into their
     * neighbours, which collide once labels are wide. Band centres leave every
     * label the same room and need no special case at all.
     *
     * Whether every tick can also carry a label is not decided here: the
     * renderer measures the text and thins the labels to fit, because the width
     * depends on the format, font scale and locale.
     */
    private fun ticks(start: Double, end: Double): DoubleArray {
        val span = end - start
        if (span <= 0) return doubleArrayOf(start)
        val band = span / TICK_COUNT
        return DoubleArray(TICK_COUNT) { start + band * (it + 0.5) }
    }

    private fun labelFormatter(span: Double): DateTimeFormatter = when {
        // Always a time of day. Stamping the date onto every tick was both
        // repetitive and cramped; it now appears once, under the tick where the
        // day actually turns over (see xDates).
        span < SPAN_10MIN -> FMT_HMS
        else -> FMT_HM
    }

    private fun label(epoch: Double, fmt: DateTimeFormatter): String =
        Instant.ofEpochSecond(epoch.toLong()).atZone(TimeSeriesHistory.EASTERN).format(fmt)

    /**
     * A date for the first tick and for any tick that starts a new day; blank
     * elsewhere.
     *
     * Over a multi-day window the useful question is "where did the day change",
     * and answering it once at the boundary is both less cluttered and easier to
     * scan than repeating the date on every label.
     */
    /**
     * "EDT", "EST" - the zone the axis is actually plotted in.
     *
     * Resolved from a real instant rather than named statically, so it follows
     * daylight saving instead of quietly going an hour wrong for half the year.
     * Note this is the *sensor's* zone, which is what the timestamps carry - not
     * the phone's, which may well differ.
     */
    private fun zoneAbbreviation(epoch: Double): String = runCatching {
        Instant.ofEpochSecond(epoch.toLong())
            .atZone(TimeSeriesHistory.EASTERN)
            .format(FMT_ZONE)
    }.getOrDefault("")

    private fun dateMarks(ticks: DoubleArray): List<String> {
        var previousDay: java.time.LocalDate? = null
        return ticks.map { epoch ->
            val day = Instant.ofEpochSecond(epoch.toLong())
                .atZone(TimeSeriesHistory.EASTERN).toLocalDate()
            val mark = if (day != previousDay) label(epoch, FMT_DATE) else ""
            previousDay = day
            mark
        }
    }

    companion object {
        /**
         * How far apart two plotted points must be before the line between them
         * is dropped, or null with too few points to establish a normal spacing.
         *
         * Judged against the *median* spacing rather than the mean, because the
         * mean is dragged upwards by exactly the gaps being looked for: one
         * two-day break in an hour of data would raise the mean enough to make
         * itself look ordinary.
         *
         * Both terms matter. Ten times the normal spacing keeps ordinary jitter
         * and the occasional dropped reading connected, which is what a live
         * plot should look like. The absolute floor stops that rule from
         * breaking a 1 Hz trace at every three-second stumble, since ten times
         * one second is still nothing. What survives both is a genuine hole:
         * recording stopped and later started again.
         */
        internal fun gapThreshold(epochs: DoubleArray, bucketSeconds: Double?): Double? {
            if (epochs.size < 3) return null
            val deltas = DoubleArray(epochs.size - 1) { epochs[it + 1] - epochs[it] }
            deltas.sort()
            val normal = maxOf(deltas[deltas.size / 2], bucketSeconds ?: 0.0)
            if (normal <= 0.0) return null
            return maxOf(GAP_FACTOR * normal, MIN_GAP_SECONDS)
        }

        private const val GAP_FACTOR = 10.0
        private const val MIN_GAP_SECONDS = 60.0

        /**
         * Averaging steps, in seconds. Deliberately values a person would name -
         * a 37-second average is technically fine and reads as a glitch.
         */
        /** The sensor's own sampling period; below this there is nothing to average. */
        const val RAW_SAMPLE_PERIOD = 1.0

        val NICE_INTERVALS = listOf(
            1.0, 5.0, 10.0, 15.0, 30.0,
            60.0, 300.0, 600.0, 900.0, 1800.0,
            3600.0, 10_800.0, 21_600.0, 43_200.0, 86_400.0,
        )

        const val DEFAULT_PIN_CYCLE = 2
        private const val MAX_PIN_HISTORY = 12
        private const val MIN_WINDOW = 1.0
        private const val TICK_COUNT = 5
        private const val SPAN_10MIN = 600.0
        private const val SPAN_24H = 86_400.0
        private val FMT_HMS = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val FMT_HM = DateTimeFormatter.ofPattern("HH:mm")
        private val FMT_DATE = DateTimeFormatter.ofPattern("MMM d")
        private val FMT_ZONE = DateTimeFormatter.ofPattern("zzz")
    }
}
