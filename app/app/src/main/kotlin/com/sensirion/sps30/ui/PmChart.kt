package com.sensirion.sps30.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.sensirion.sps30.data.ChartData
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max

/*
 * Annotation type sizes, in canvas pixels.
 *
 * Deliberately generous. This is an air-quality instrument that may well be
 * consulted in a hurry, or by someone without their reading glasses to hand, and
 * an axis nobody can read is a measurement nobody can use. The plot gives up
 * about 2% of its width and half a percent of its height to pay for it, which is
 * a trade worth making every time.
 */
// These are font sizes, and a digit's cap-height is only about 71% of that - the
// first pass at enlarging this text set 38f and got 27px-tall digits, which is
// no better than where it started. Sized from the height wanted on glass.
private const val TEXT_TICK = 52f       // axis tick values (~37px digits)
private const val TEXT_CAPTION = 54f    // axis captions, dates, crosshair readout
private const val TICK_LABEL_GAP = 24f  // clear space demanded between x labels
private const val TEXT_NOTE = 50f       // secondary notes inside the plot

// Left padding carries the tick values plus the rotated axis caption beside
// them; bottom padding carries the time ticks plus a date line under them. Both
// are sized to the type above.
// Whole-number y labels need far less room than "1.08" did, so the gutter gives
// most of that width back to the plot - which had started to look lopsided,
// crammed against the right edge with a wide empty margin on the left.
private const val LEFT_PAD = 150f
private const val RIGHT_PAD = 28f
private const val TOP_PAD = 20f
private const val BOTTOM_PAD = 176f

// Widest-first draw order + graduated widths so coincident series layer visibly.
private val DRAW_ORDER = listOf("pm10", "pm4_0", "pm2_5", "pm1_0")
private val STROKE_WIDTH = mapOf("pm10" to 6.5f, "pm4_0" to 5f, "pm2_5" to 3.5f, "pm1_0" to 2.5f)

// Ignore incidental pinch below this magnitude so a pure drag does not zoom.
private const val ZOOM_DEADZONE = 0.015f

// Smallest PM value a log axis will resolve, ug/m3. Below this the reading is
// past what the sensor meaningfully distinguishes from zero.
private const val LOG_FLOOR = 0.01

// Alert red. Deliberately not from the theme: this must read as a warning in
// both light and dark, and must not collide with any series colour.
private val ALERT_COLOR = Color(0xFFFF5252)

/**
 * Native line chart of the four PM series over time, with pinch-to-zoom
 * (horizontal) and drag-to-pan. Pure rendering of the supplied [ChartData];
 * gestures report intent via [onZoom] (span scale factor) and [onPan] (seconds).
 */
@Composable
fun PmChart(
    data: ChartData?,
    modifier: Modifier = Modifier,
    colors: Map<String, Color> = SeriesColors.color,
    visible: List<String> = DRAW_ORDER,
    onZoom: (Double) -> Unit = {},
    onPan: (Double) -> Unit = {},
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
    onWidthChanged: (Int) -> Unit = {},
    inspectedEpoch: Double? = null,
    onInspect: (Double?) -> Unit = {},
    logY: Boolean = false,
    alertThreshold: Double? = null,
    alertField: String = "pm2_5",
    viewInfo: String = "",
    timeZoneLabel: String = "",
) {
    val axisColor = MaterialTheme.colorScheme.outline
    // Stronger than before: with no separate tick marks the gridlines are what
    // tie a label to a position on the plot, and at 0.4 alpha they were barely
    // there. Minor lines stay faint so the labelled ones still read as primary.
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val latest by rememberUpdatedState(data)
    val onStart by rememberUpdatedState(onGestureStart)
    val onEnd by rememberUpdatedState(onGestureEnd)
    val onWidth by rememberUpdatedState(onWidthChanged)
    val inspect by rememberUpdatedState(onInspect)

    Canvas(
        modifier = modifier
            .onSizeChanged { onWidth(it.width) }
            .pointerInput(Unit) {
                // One finger inspects, two fingers move.
                //
                // Low-power mode records a point every 15 minutes, so the only way
                // to be confident it is working is to read the actual timestamps
                // off the plot. That has to be a single-finger action, which means
                // panning must give up the single finger entirely - otherwise
                // every inspection would drag the data out from under the reader.
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    var moved = false

                    // A single finger starting inside the plot begins inspecting.
                    if (inPlotArea(first.position, size)) {
                        inspect(epochAt(first.position.x, latest, size))
                        first.consume()
                    } else {
                        // Tapping the margins (axis labels, below the axis) is the
                        // "off the plot" gesture that dismisses the readout.
                        inspect(null)
                    }

                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val d = latest
                        if (pressed >= 2) {
                            multiTouch = true
                            if (d != null) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val plotW = max(1f, size.width - LEFT_PAD - RIGHT_PAD)
                                // Dead-zone: ignore the incidental zoom a two-finger drag reports.
                                if (zoom > 0f && abs(zoom - 1f) > ZOOM_DEADZONE) {
                                    if (!moved) onStart()
                                    onZoom(1.0 / zoom.toDouble()); moved = true
                                }
                                if (pan.x != 0f) {
                                    if (!moved) onStart()
                                    onPan(-(pan.x / plotW).toDouble() * d.spanSeconds); moved = true
                                }
                                if (moved) event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (!multiTouch && pressed == 1) {
                            // Still one finger: slide the crosshair with it.
                            val pos = event.changes.first().position
                            if (inPlotArea(pos, size)) {
                                inspect(epochAt(pos.x, latest, size))
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (moved) onEnd()
                }
            },
    ) {
        drawPmChart(
            data, colors, visible, axisColor, gridColor, labelColor,
            inspectedEpoch, logY, alertThreshold, alertField, viewInfo, timeZoneLabel,
        )
    }
}

/** True if [position] is inside the plotting rectangle, excluding the axis margins. */
private fun inPlotArea(position: Offset, size: IntSize): Boolean =
    position.x >= LEFT_PAD &&
        position.x <= size.width - RIGHT_PAD &&
        position.y >= TOP_PAD &&
        position.y <= size.height - BOTTOM_PAD

/** Epoch of the drawn sample nearest [x], or null when there is nothing to inspect. */
private fun epochAt(x: Float, data: ChartData?, size: IntSize): Double? {
    if (data == null || data.x.isEmpty()) return null
    val plotW = max(1f, size.width - LEFT_PAD - RIGHT_PAD)
    val span = (data.end - data.start).coerceAtLeast(1e-6)
    val target = data.start + ((x - LEFT_PAD) / plotW).toDouble() * span
    return data.x.minByOrNull { abs(it - target) }
}

/**
 * Draws the chart into any [DrawScope] - used both by the live [PmChart] Canvas
 * and by [renderChartToBitmap] for PNG export, so there is one source of truth.
 */
fun DrawScope.drawPmChart(
    data: ChartData?,
    colors: Map<String, Color>,
    visible: List<String>,
    axisColor: Color,
    gridColor: Color,
    labelColor: Color,
    inspectedEpoch: Double? = null,
    logY: Boolean = false,
    alertThreshold: Double? = null,
    alertField: String = "pm2_5",
    viewInfo: String = "",
    timeZoneLabel: String = "",
) {
    val plotW = max(1f, size.width - LEFT_PAD - RIGHT_PAD)
    val plotH = max(1f, size.height - TOP_PAD - BOTTOM_PAD)
    val plotLeft = LEFT_PAD
    val plotTop = TOP_PAD
    val plotRight = LEFT_PAD + plotW
    val plotBottom = TOP_PAD + plotH

    drawLine(axisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), 2f)
    drawLine(axisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), 2f)

    if (data == null) {
        drawAxisText("connecting…", plotLeft + plotW / 2f, plotTop + plotH / 2f,
            labelColor, TEXT_CAPTION + 4f, center = true)
        return
    }

    // Resolve the y range: frozen (yMin/yMax) when not autoscaling, else the
    // data extent with a little padding (default 0..10 when empty/flat).
    val (rawMin, rawMax) = resolveYRange(data)
    // On a log axis zero and anything below it have no position, so the axis is
    // floored just under the smallest PM value worth distinguishing rather than
    // at zero. PM readings are non-negative and a true zero is indistinguishable
    // from "cleaner than the sensor can resolve".
    // Snap an autoscaled linear range out to whole steps. Without this a range
    // that happens to sit between two integers - 1.2 to 1.8, say - contains no
    // labelled value at all, and the axis draws with no gridlines and no
    // numbers. Expanding to the enclosing steps guarantees a labelled line top
    // and bottom, which is also what makes it look like a plotted axis rather
    // than a floating trace.
    // One step for both snapping and drawing. Deriving it twice - once from the
    // data extent, once from the snapped range - lets the two disagree whenever
    // snapping carries the span across 1.0, and then the axis snaps out to whole
    // numbers but labels itself in fifths (or the reverse).
    val linearStep = niceStep(rawMax - rawMin)
    val snapped =
        if (!logY && data.yAuto) snapToSteps(rawMin, rawMax, linearStep) else rawMin to rawMax
    val yMin = if (logY) maxOf(rawMin, LOG_FLOOR).coerceAtMost(rawMax / 2.0) else snapped.first
    val yMax = if (logY) maxOf(rawMax, yMin * 10.0) else snapped.second
    val ySpan = (yMax - yMin).coerceAtLeast(1e-6)
    val logLo = log10(yMin.coerceAtLeast(LOG_FLOOR))
    val logSpan = (log10(yMax.coerceAtLeast(LOG_FLOOR * 10)) - logLo).coerceAtLeast(1e-6)

    fun xOf(epoch: Double): Float {
        val span = (data.end - data.start).coerceAtLeast(1e-6)
        return (plotLeft + (epoch - data.start) / span * plotW).toFloat()
    }
    fun yOf(value: Double): Float = if (logY) {
        val t = (log10(value.coerceAtLeast(LOG_FLOOR)) - logLo) / logSpan
        (plotBottom - t * plotH).toFloat().coerceIn(plotTop, plotBottom)
    } else {
        (plotBottom - (value - yMin) / ySpan * plotH).toFloat().coerceIn(plotTop, plotBottom)
    }

    val minorColor = gridColor.copy(alpha = gridColor.alpha * 0.4f)
    if (logY) {
        // A log axis gets decade-ish lines rather than linear ones squashed
        // against the top.
        val rows = 4
        for (i in 0..rows) {
            val value = Math.pow(10.0, logLo + logSpan * i / rows)
            val y = yOf(value)
            drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), 1.5f)
            drawAxisText(formatY(value), plotLeft - 16f, y, labelColor, TEXT_TICK, alignRight = true)
        }
    } else {
        // Labels land on round numbers at a sensible interval instead of five
        // evenly-divided values like 1.08 and 1.76. Those spent width on
        // precision the sensor does not have - it does not resolve tenths
        // reliably - and gave the eye nothing to anchor on. Reading a value to
        // two decimals is what the crosshair is for.
        val step = linearStep
        val firstIndex = ceil(yMin / step - 1e-9)
        // Indexed rather than accumulated: adding 0.1 repeatedly drifts, and the
        // drift shows up as a label reading 1.30000000000000004.
        var n = 0
        var value = (firstIndex + n) * step
        while (value <= yMax + step * 1e-6) {
            val y = yOf(value)
            drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), 1.5f)
            drawAxisText(formatStep(value, step), plotLeft - 16f, y, labelColor,
                TEXT_TICK, alignRight = true)
            // Halfway line, unlabelled: it shows where the midpoint falls without
            // spending any width on saying so.
            val half = value + step / 2.0
            if (half <= yMax) {
                val hy = yOf(half)
                drawLine(minorColor, Offset(plotLeft, hy), Offset(plotRight, hy), 1f)
            }
            n++
            value = (firstIndex + n) * step
        }
        // And the half below the first labelled line, when it is in range.
        val firstHalf = firstIndex * step - step / 2.0
        if (firstHalf >= yMin) {
            val hy = yOf(firstHalf)
            drawLine(minorColor, Offset(plotLeft, hy), Offset(plotRight, hy), 1f)
        }
    }

    // How many ticks can carry a label without touching. Label width varies with
    // the format ("14:05" vs "14:05:32"), the font scale and the locale, so this
    // is measured rather than assumed - guessing a tick count is what let them
    // overlap at some window sizes and not others. Gridlines are drawn at every
    // tick regardless; only the text is thinned, and evenly, so the labels that
    // remain stay regularly spaced.
    val labelStride = run {
        if (data.xTicks.size < 2) return@run 1
        val widest = data.xLabels.maxOfOrNull { measureText(it, TEXT_TICK) } ?: 0f
        val spacing = abs(xOf(data.xTicks[1]) - xOf(data.xTicks[0]))
        if (spacing <= 0f) 1 else ceil((widest + TICK_LABEL_GAP) / spacing).toInt().coerceAtLeast(1)
    }
    for (i in data.xTicks.indices) {
        val x = xOf(data.xTicks[i])
        drawLine(gridColor, Offset(x, plotTop), Offset(x, plotBottom), 1f)
        if (i % labelStride != 0) continue
        val label = data.xLabels.getOrNull(i) ?: continue
        // Every label centres on its tick, with no special case for the ends.
        // Shifting the outermost two inward to keep them on screen is what made
        // them collide with their neighbours; ticks now sit at band centres, so
        // a centred label has room at either edge already.
        drawAxisText(label, x, plotBottom + 44f, labelColor, TEXT_TICK, center = true)
        // The date appears only where the day turns over, so a multi-day window
        // shows at a glance where one day ends and the next begins.
        val date = data.xDates.getOrNull(i)
        if (!date.isNullOrEmpty()) {
            drawAxisText(date, x, plotBottom + 104f, labelColor, TEXT_CAPTION, center = true)
        }
    }
    // Centred under the axis, as an axis caption conventionally is. Naming the
    // actual zone beats saying "local": the readings carry the sensor's clock,
    // which is not necessarily the phone's, and "EDT" says so unambiguously.
    val zone = if (timeZoneLabel.isEmpty()) "" else " ($timeZoneLabel)"
    drawAxisText(
        "Time of day$zone",
        plotLeft + plotW / 2f, plotBottom + 160f, labelColor, TEXT_CAPTION, center = true,
    )

    // Draw widest (PM10) first down to narrowest (PM1.0). In clean air the
    // larger cuts (pm2.5==pm4.0==pm10) are identical; graduated widths make
    // the coincident lines render as a layered colour band - all four stay
    // visible - instead of the last-drawn series overdrawing the others.
    for (field in DRAW_ORDER) {
        if (field !in visible) continue
        val vals = data.series[field] ?: continue
        if (vals.isEmpty() || data.x.isEmpty()) continue
        val color = colors[field] ?: Color.Gray
        val width = STROKE_WIDTH[field] ?: 3f
        val path = Path()
        var started = false
        val n = minOf(vals.size, data.x.size)
        for (j in 0 until n) {
            val px = xOf(data.x[j])
            val py = yOf(vals[j])
            // Lift the pen across a gap in the record. Recording stopped and
            // started again there, so a segment drawn over it would be pure
            // invention - and the longer the gap, the more confident the
            // invention looks. A lone point either side of a gap still gets its
            // dot below, so nothing disappears for being isolated.
            val broken = started && data.gapSeconds?.let { data.x[j] - data.x[j - 1] > it } == true
            if (!started || broken) {
                path.moveTo(px, py)
                started = true
                // A segment of one sample has no line to draw, so mark it.
                val alone = broken || j == 0
                val next = j + 1
                val isolated = alone &&
                    (next >= n || data.gapSeconds?.let { data.x[next] - data.x[j] > it } == true)
                if (isolated) drawCircle(color, width * 0.8f, Offset(px, py))
            } else {
                path.lineTo(px, py)
            }
        }
        drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
        if (n > 0) drawCircle(color, width + 1f, Offset(xOf(data.x[n - 1]), yOf(vals[n - 1])))
    }

    // Alert threshold: a dashed line plus the breaching part of the watched
    // series redrawn in red. Colouring only the excursion (rather than the whole
    // trace) keeps it obvious *when* the air was bad, not merely that it was.
    // Only when the threshold genuinely falls inside the plotted y range. yOf
    // clamps out-of-range values to the axis ends, which drew the line pinned to
    // the top of a chart whose axis stopped well below it - making a reading of
    // 13 look as though it were nearly touching an alert set at 20.
    val threshold = alertThreshold?.takeIf { it > 0.0 && it >= yMin && it <= yMax }
    if (threshold != null && data.x.isNotEmpty()) {
        val ty = yOf(threshold)
        if (ty in plotTop..plotBottom) {
            drawLine(
                ALERT_COLOR.copy(alpha = 0.75f),
                Offset(plotLeft, ty),
                Offset(plotRight, ty),
                2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
            )
            drawAxisText(
                "alert ${formatY(threshold)}",
                plotRight - 6f, ty - 12f, ALERT_COLOR, TEXT_NOTE, alignRight = true,
            )
        }
        val vals = data.series[alertField]
        if (vals != null && alertField in visible) {
            val n = minOf(vals.size, data.x.size)
            var run: Path? = null
            for (j in 0 until n) {
                val over = vals[j] >= threshold
                val px = xOf(data.x[j])
                val py = yOf(vals[j])
                // Same rule as the trace itself: an excursion cannot be drawn as
                // continuing across time when nothing was recorded there.
                val afterGap = j > 0 &&
                    data.gapSeconds?.let { data.x[j] - data.x[j - 1] > it } == true
                if (afterGap && run != null) {
                    drawPath(run, ALERT_COLOR, style = Stroke(width = 5f, cap = StrokeCap.Round))
                    run = null
                }
                if (over) {
                    if (run == null) {
                        run = Path()
                        // Start from the previous point so the rise into the
                        // excursion is coloured too, not just its plateau.
                        if (j > 0 && !afterGap) run.moveTo(xOf(data.x[j - 1]), yOf(vals[j - 1]))
                        else run.moveTo(px, py)
                    }
                    run.lineTo(px, py)
                } else if (run != null) {
                    run.lineTo(px, py)
                    drawPath(run, ALERT_COLOR, style = Stroke(width = 5f, cap = StrokeCap.Round))
                    run = null
                }
            }
            run?.let { drawPath(it, ALERT_COLOR, style = Stroke(width = 5f, cap = StrokeCap.Round)) }
        }
    }

    // The y-axis caption reads bottom-to-top in the gutter beside the tick
    // values, as on any plotted figure.
    drawRotatedText(
        "Particle concentration (${SeriesColors.unit})",
        30f, plotTop + plotH / 2f, labelColor, TEXT_CAPTION,
    )
    // Suppressed while inspecting - the crosshair readout owns this corner then.
    if (viewInfo.isNotEmpty() && inspectedEpoch == null) {
        drawAxisText(viewInfo, plotRight - 8f, plotTop + 30f, labelColor, TEXT_NOTE, alignRight = true)
    }

    if (data.x.isEmpty()) {
        drawAxisText("waiting for readings…", plotLeft + plotW / 2f, plotTop + plotH / 2f,
            labelColor, TEXT_CAPTION, center = true)
    }

    // Inspection crosshair, drawn last so it sits over the series.
    if (inspectedEpoch != null && data.x.isNotEmpty()) {
        val index = data.x.indices.minByOrNull { abs(data.x[it] - inspectedEpoch) } ?: return
        val epoch = data.x[index]
        if (epoch < data.start || epoch > data.end) return
        val x = xOf(epoch)
        drawLine(labelColor, Offset(x, plotTop), Offset(x, plotBottom), 2f)

        // Ring each series at the inspected sample so the numbers below are
        // unambiguous about which trace they came from.
        for (field in DRAW_ORDER) {
            if (field !in visible) continue
            val value = data.series[field]?.getOrNull(index) ?: continue
            drawCircle(colors[field] ?: Color.Gray, 7f, Offset(x, yOf(value)))
        }

        // Readout, top-right of the plot. Flips to the left when the crosshair
        // would otherwise sit underneath it.
        val lines = buildList {
            add(formatStamp(epoch))
            for (field in DRAW_ORDER) {
                if (field !in visible) continue
                val value = data.series[field]?.getOrNull(index) ?: continue
                add("${SeriesColors.label[field] ?: field}  ${formatY(value)}")
            }
        }
        val onRight = x < plotLeft + plotW * 0.55f
        val boxRight = if (onRight) plotRight - 8f else plotLeft + plotW * 0.42f
        var textY = plotTop + 38f
        for ((i, line) in lines.withIndex()) {
            val color = if (i == 0) labelColor else {
                colors[DRAW_ORDER.filter { it in visible }.getOrNull(i - 1)] ?: labelColor
            }
            drawAxisText(line, boxRight, textY, color, TEXT_CAPTION, alignRight = true)
            textY += 52f
        }
    }
}

/** "14:03:27" for sub-day spans, else "Jul 27 14:03" - enough to confirm cadence. */
private fun formatStamp(epoch: Double): String =
    java.time.Instant.ofEpochSecond(epoch.toLong())
        .atZone(com.sensirion.sps30.data.TimeSeriesHistory.EASTERN)
        .format(STAMP_FORMAT)

private val STAMP_FORMAT: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MMM d  HH:mm:ss")

/** Render the current chart to an ARGB [Bitmap] for PNG export. */
fun renderChartToBitmap(
    data: ChartData?,
    visible: List<String>,
    widthPx: Int,
    heightPx: Int,
    background: Color,
    axisColor: Color,
    gridColor: Color,
    labelColor: Color,
    colors: Map<String, Color> = SeriesColors.color,
): android.graphics.Bitmap {
    val image = ImageBitmap(widthPx, heightPx)
    val canvas = Canvas(image)
    CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        canvas,
        Size(widthPx.toFloat(), heightPx.toFloat()),
    ) {
        drawRect(background)
        drawPmChart(data, colors, visible, axisColor, gridColor, labelColor)
    }
    return image.asAndroidBitmap()
}

private fun resolveYRange(data: ChartData): Pair<Double, Double> {
    if (!data.yAuto && data.yMin != null && data.yMax != null && data.yMin < data.yMax) {
        return data.yMin to data.yMax
    }
    val rawMin = data.dataYMin ?: 0.0
    val rawMax = data.dataYMax ?: 10.0
    var lo = rawMin
    var hi = rawMax
    if (hi - lo < 1e-6) { lo -= 1.0; hi += 1.0 }
    val pad = (hi - lo) * 0.08
    lo -= pad
    hi += pad
    if (lo < 0 && rawMin >= 0) lo = 0.0
    return lo to hi
}

/**
 * Draws [text] rotated a quarter turn anticlockwise, centred on ([x], [y]).
 *
 * The convention every paper and instrument uses for a y-axis caption, and the
 * only way to name the quantity without stealing width from the plot: it lives
 * in the gutter that already exists for the tick values.
 */
private fun DrawScope.drawRotatedText(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    sizePx: Float,
) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = sizePx
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val canvas = drawContext.canvas.nativeCanvas
    val saved = canvas.save()
    canvas.rotate(-90f, x, y)
    val fm = paint.fontMetrics
    canvas.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, paint)
    canvas.restoreToCount(saved)
}

/** Width of [text] at [sizePx], for deciding whether it fits before drawing it. */
private fun measureText(text: String, sizePx: Float): Float =
    android.graphics.Paint().apply { textSize = sizePx }.measureText(text)

private fun DrawScope.drawAxisText(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    sizePx: Float,
    center: Boolean = false,
    alignRight: Boolean = false,
) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = sizePx
        isAntiAlias = true
        textAlign = when {
            center -> android.graphics.Paint.Align.CENTER
            alignRight -> android.graphics.Paint.Align.RIGHT
            else -> android.graphics.Paint.Align.LEFT
        }
    }
    val fm = paint.fontMetrics
    val baseline = y - (fm.ascent + fm.descent) / 2f
    drawContext.canvas.nativeCanvas.drawText(text, x, baseline, paint)
}

/**
 * A round interval for the y labels, at least 1.
 *
 * Floored at 1 deliberately: the SPS30 does not resolve fractions of a
 * microgram meaningfully, so labelling them implies a precision the instrument
 * does not have and costs width doing it.
 */
/**
 * A round interval - 1, 2, 5 x a power of ten - giving roughly four lines over
 * [range].
 *
 * Whole numbers are preferred, because the axis is for reading levels at a
 * glance and the crosshair is what precision is for. But that is a preference,
 * not a rule: forcing a step of 1 onto a range narrower than 1 either leaves the
 * axis blank or flattens the trace into a straight line by stretching the range
 * around it. Indoor air sits at 1-3 ug/m3 and moves in hundredths, so that case
 * is the common one, not an edge case. Below a span of 1 the natural fractional
 * step (0.5, 0.2, 0.1 ...) is used and the labels carry exactly the decimals it
 * needs.
 */
private fun niceStep(range: Double): Double {
    if (range <= 0 || !range.isFinite()) return 1.0
    val target = range / 4.0
    val magnitude = Math.pow(10.0, floor(log10(target.coerceAtLeast(1e-9))))
    val step = listOf(1.0, 2.0, 5.0, 10.0)
        .map { it * magnitude }
        .firstOrNull { it >= target } ?: (10.0 * magnitude)
    return if (range >= 1.0) maxOf(1.0, step) else step
}

/**
 * Widen [lo]..[hi] to whole multiples of a round step, with at least two
 * intervals so the axis always carries a labelled line at each end.
 */
private fun snapToSteps(lo: Double, hi: Double, step: Double): Pair<Double, Double> {
    var bottom = floor(lo / step) * step
    var top = ceil(hi / step) * step
    // A range narrower than one step collapses to a single line; give it two.
    if (top - bottom < step * 1.5) {
        top = bottom + step * 2
    }
    // Readings are non-negative, so do not invent a negative axis.
    if (bottom < 0 && lo >= 0) {
        top -= bottom
        bottom = 0.0
    }
    return bottom to top
}

/** Exactly the decimals the interval needs - no trailing noise, none missing. */
private fun formatStep(value: Double, step: Double): String = when {
    step >= 1.0 -> "%.0f".format(value)
    step >= 0.1 -> "%.1f".format(value)
    else -> "%.2f".format(value)
}

private fun formatY(value: Double): String {
    val a = abs(value)
    return when {
        a >= 100 -> "%.0f".format(value)
        a >= 10 -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }
}
