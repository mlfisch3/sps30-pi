package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurationsTest {
    @Test fun bareNumberIsSeconds() {
        assertEquals(1800.0, Durations.parse("1800"), 0.0)
        assertEquals(0.5, Durations.parse("0.5"), 0.0)
    }

    @Test fun unitSuffixes() {
        assertEquals(1.0, Durations.parse("1s"), 0.0)
        assertEquals(60.0, Durations.parse("1m"), 0.0)
        assertEquals(3600.0, Durations.parse("1h"), 0.0)
        assertEquals(86400.0, Durations.parse("1d"), 0.0)
        assertEquals(7200.0, Durations.parse("2hours"), 0.0)
    }

    @Test fun combinationsAndSpaces() {
        assertEquals(5400.0, Durations.parse("1h30m"), 0.0)
        assertEquals(8130.0, Durations.parse("2h15m30s"), 0.0)
        assertEquals(43200.0, Durations.parse("12 hours"), 0.0)
        assertEquals(3600.0, Durations.parse("1 H"), 0.0) // case-insensitive
    }

    @Test fun rejectsBadInput() {
        for (bad in listOf("", "   ", "abc", "1x", "1h30", "0", "-5", "1h-2m")) {
            var threw = false
            try { Durations.parse(bad) } catch (e: IllegalArgumentException) { threw = true }
            assertTrue("should reject '$bad'", threw)
        }
    }
}

class TimeSeriesHistoryTest {
    private fun r(csv: String?, index: String, second: Int, pm25: Double) =
        com.sensirion.sps30.wire.ServerMessage.Reading(
            csv, index, "2026-07-22T02:00:${"%02d".format(second)}-04:00",
            mapOf("pm1_0" to pm25, "pm2_5" to pm25, "pm4_0" to pm25, "pm10" to pm25),
        )

    @Test fun addAndDedupByCsvAndIndex() {
        val h = TimeSeriesHistory()
        assertTrue(h.add(r("a.csv", "0000001", 1, 1.0)))
        assertFalse("same (csv,index) is a dup", h.add(r("a.csv", "0000001", 1, 9.0)))
        assertEquals(1, h.size)
        // Same index under a DIFFERENT csv is kept (indices restart per file).
        assertTrue(h.add(r("b.csv", "0000001", 2, 2.0)))
        assertEquals(2, h.size)
    }

    @Test fun skipsUnparseableTimestamp() {
        val h = TimeSeriesHistory()
        val bad = com.sensirion.sps30.wire.ServerMessage.Reading("a", "1", "   ", mapOf("pm2_5" to 1.0))
        assertFalse(h.add(bad))
        assertEquals(0, h.size)
    }

    @Test fun windowSlicesByEpochInclusive() {
        val h = TimeSeriesHistory()
        for (s in 0..9) h.add(r("a.csv", "000000$s", s, s.toDouble()))
        val base = TimeSeriesHistory.epochOf("2026-07-22T02:00:00-04:00")!!
        val w = h.window(base + 2, base + 5) // seconds :02..:05 inclusive
        assertEquals(4, w.epochs.size)
        assertEquals(doubleArrayOf(2.0, 3.0, 4.0, 5.0).toList(), w.series["pm2_5"]!!.toList())
    }

    @Test fun retentionTrimsOldPoints() {
        val h = TimeSeriesHistory(retentionSeconds = 3.0)
        for (s in 0..9) h.add(r("a.csv", "000000$s", s, s.toDouble()))
        // latest is :09; cutoff = 09 - 3 = 06, so :06..:09 remain (4 points).
        assertEquals(4, h.size)
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        assertEquals(listOf(6.0, 7.0, 8.0, 9.0), w.series["pm2_5"]!!.toList())
    }

    @Test fun clearEmptiesAndAllowsReadd() {
        val h = TimeSeriesHistory()
        h.add(r("a.csv", "0000001", 1, 1.0))
        h.clear()
        assertEquals(0, h.size)
        assertNull(h.latestEpoch())
        assertTrue("cleared key can be re-added", h.add(r("a.csv", "0000001", 1, 1.0)))
    }

    @Test fun indexOfParsing() {
        assertEquals(7, TimeSeriesHistory.indexOf("0000007"))
        assertEquals(124, TimeSeriesHistory.indexOf("124"))
        assertEquals(0, TimeSeriesHistory.indexOf("  "))
        assertEquals(0, TimeSeriesHistory.indexOf("junk"))
    }

    @Test fun maxIndexForTracksHighWaterPerCsv() {
        val h = TimeSeriesHistory(retentionSeconds = 3.0)
        assertEquals("unknown csv is 0 (nothing to backfill)", 0, h.maxIndexFor("a.csv"))
        for (s in 0..9) h.add(r("a.csv", "000000$s", s, s.toDouble()))
        h.add(r("b.csv", "0000002", 2, 2.0))
        // Retention trimmed :00..:05 for a.csv, but the high-water mark survives.
        assertEquals(9, h.maxIndexFor("a.csv"))
        assertEquals(2, h.maxIndexFor("b.csv"))
        h.clear()
        assertEquals(0, h.maxIndexFor("a.csv"))
    }

    @Test fun addRowsFoldsBackfillDedupsAndUpdatesHighWater() {
        val h = TimeSeriesHistory()
        h.add(r("a.csv", "0000003", 3, 3.0)) // one live row already present
        val columns = listOf("index", "timestamp", "pm1_0", "pm2_5", "pm4_0", "pm10")
        fun row(sec: Int) = listOf(
            "%07d".format(sec), "2026-07-22T02:00:${"%02d".format(sec)}-04:00",
            "$sec.0", "$sec.0", "$sec.0", "$sec.0",
        )
        // Rows :01..:03 - :03 duplicates the live row, so only :01 and :02 are new.
        val added = h.addRows("a.csv", columns, listOf(row(1), row(2), row(3)))
        assertEquals(2, added)
        assertEquals(3, h.size)
        assertEquals(3, h.maxIndexFor("a.csv"))
        // Folded in time order regardless of arrival.
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        assertEquals(listOf(1.0, 2.0, 3.0), w.series["pm2_5"]!!.toList())
    }

    @Test fun addRowsRejectsPayloadMissingIndexOrTimestamp() {
        val h = TimeSeriesHistory()
        val added = h.addRows("a.csv", listOf("pm2_5"), listOf(listOf("1.0")))
        assertEquals(0, added)
        assertEquals(0, h.size)
    }

    @Test fun excludeFlaggedFiltersWindowAndFirstStableSkipsSpike() {
        val h = TimeSeriesHistory()
        // :00..:02 are a flagged startup spike; :03..:05 are clean readings.
        for (s in 0..2) h.add(r("a.csv", "000000$s", s, 9.0).copy(event = "startup"))
        for (s in 3..5) h.add(r("a.csv", "000000$s", s, s.toDouble()))
        // The session's true start is the first non-flagged point (:03), not :00.
        assertEquals(
            TimeSeriesHistory.epochOf("2026-07-22T02:00:03-04:00"),
            h.firstStableEpoch(),
        )
        val base = TimeSeriesHistory.epochOf("2026-07-22T02:00:00-04:00")!!
        val excl = h.window(base, base + 5, excludeFlagged = true)
        assertEquals(3, excl.epochs.size) // :00..:02 dropped
        assertEquals(listOf(3.0, 4.0, 5.0), excl.series["pm2_5"]!!.toList())
        assertEquals(6, h.window(base, base + 5, excludeFlagged = false).epochs.size)
    }

    @Test fun addRowsMapsEventColumnAsFlagged() {
        val h = TimeSeriesHistory()
        val columns = listOf("index", "timestamp", "pm1_0", "pm2_5", "pm4_0", "pm10", "event")
        fun row(sec: Int, ev: String) = listOf(
            "%07d".format(sec), "2026-07-22T02:00:${"%02d".format(sec)}-04:00",
            "$sec.0", "$sec.0", "$sec.0", "$sec.0", ev,
        )
        h.addRows("a.csv", columns, listOf(row(1, "clean"), row(2, "")))
        val base = TimeSeriesHistory.epochOf("2026-07-22T02:00:00-04:00")!!
        assertEquals(1, h.window(base, base + 5, excludeFlagged = true).epochs.size)
        assertEquals(2, h.window(base, base + 5, excludeFlagged = false).epochs.size)
    }
}

class ChartViewTest {
    private fun reading(second: Int, pm: Double) = com.sensirion.sps30.wire.ServerMessage.Reading(
        "a.csv", "%07d".format(second), "2026-07-22T02:00:${"%02d".format(second)}-04:00",
        mapOf("pm1_0" to pm, "pm2_5" to pm, "pm4_0" to pm, "pm10" to pm),
    )

    @Test fun movingWindowRangeAndSpan() {
        val cv = ChartView(windowSeconds = 120.0)
        val (start, end) = cv.visibleRange(1000.0)
        assertEquals(880.0, start, 0.0)
        assertEquals(1000.0, end, 0.0)
    }

    @Test fun computeReturnsWindowedSeriesAndYExtent() {
        val h = TimeSeriesHistory()
        for (s in 0..9) h.add(reading(s, s.toDouble()))
        val now = TimeSeriesHistory.epochOf("2026-07-22T02:00:09-04:00")!!
        val cv = ChartView(windowSeconds = 5.0) // last 5s -> :04..:09
        val data = cv.compute(h, now)
        assertEquals(6, data.x.size)               // :04..:09 inclusive
        assertEquals(4.0, data.dataYMin!!, 0.0)
        assertEquals(9.0, data.dataYMax!!, 0.0)
        assertEquals(5, data.xTicks.size)
        assertEquals(5, data.xLabels.size)
        assertTrue(data.xLabels.all { it.matches(Regex("""\d\d:\d\d:\d\d""")) }) // <10min -> HH:MM:SS
    }

    @Test fun ticksSitInsideTheRangeNotOnItsEdges() {
        // A tick at either end of the range puts half its label outside the plot.
        // Nudging those two inward to compensate is what made them collide with
        // their neighbours, so ticks are placed at band centres instead.
        val h = TimeSeriesHistory()
        for (s in 0..59) h.add(reading(s, 1.0))
        val now = TimeSeriesHistory.epochOf("2026-07-22T02:00:59-04:00")!!
        val cv = ChartView(windowSeconds = 60.0)
        val data = cv.compute(h, now)

        assertTrue("no tick may sit on the range edges", data.xTicks.all {
            it > data.start && it < data.end
        })
        // Evenly spaced, and the outermost gaps to the edges match the spacing
        // between ticks - so every label has the same room.
        val gaps = data.xTicks.toList().zipWithNext { a, b -> b - a }
        assertTrue("ticks should be evenly spaced", gaps.all { kotlin.math.abs(it - gaps[0]) < 1e-6 })
        assertEquals(data.xTicks.first() - data.start, data.end - data.xTicks.last(), 1e-6)
    }

    @Test fun decimationCapsSamplesKeepingEnds() {
        val h = TimeSeriesHistory()
        for (s in 0..59) h.add(reading(s, s.toDouble()))
        val now = TimeSeriesHistory.epochOf("2026-07-22T02:00:59-04:00")!!
        val cv = ChartView(windowSeconds = 100.0)
        val data = cv.compute(h, now, maxPoints = 10)
        assertTrue("decimated to <= 10", data.x.size in 2..10)
        // First and last samples preserved (values equal their second index here).
        assertEquals(0.0, data.series["pm2_5"]!!.first(), 0.0)
        assertEquals(59.0, data.series["pm2_5"]!!.last(), 0.0)
        assertEquals(60, data.sampleCount) // reports pre-decimation count
    }
}

class ChartViewInteractionTest {
    @Test fun toggleModePinsAtCurrentLeftEdge() {
        val cv = ChartView(windowSeconds = 120.0)
        assertEquals(ChartMode.PINNED, cv.toggleMode(1000.0))
        assertEquals(880.0, cv.pinStart!!, 0.0)
        assertEquals(880.0 to 1000.0, cv.visibleRange(1000.0))
        // Right edge grows with now; left stays pinned.
        assertEquals(880.0 to 1500.0, cv.visibleRange(1500.0))
        assertEquals(ChartMode.MOVING, cv.toggleMode(1500.0)) // back to moving
        assertEquals(1380.0 to 1500.0, cv.visibleRange(1500.0))
    }

    @Test fun zoomMovingScalesWindow() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.zoom(0.5, 1000.0) // zoom in -> narrower
        assertEquals(50.0, cv.windowSeconds, 0.0)
        cv.zoom(4.0, 1000.0) // zoom out
        assertEquals(200.0, cv.windowSeconds, 0.0)
    }

    @Test fun zoomFrozenScalesAboutCenter() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.setXRange(1000.0, 2000.0) // center 1500, half 500
        cv.zoom(0.5, 9999.0)
        assertEquals(1250.0 to 1750.0, cv.xFixed) // half -> 250 about 1500
    }

    @Test fun zoomPinnedPullsPinTowardNow() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.setMode(ChartMode.PINNED, 1000.0) // pinStart = 900
        cv.zoom(0.5, 1000.0)                 // now - (now-pin)*0.5 = 1000 - 50 = 950
        assertEquals(950.0, cv.pinStart!!, 0.0)
    }

    @Test fun panMovingFreezesShiftedRange() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pan(-30.0, 1000.0) // moving -> freeze (900,1000) shifted by -30
        assertTrue(cv.xFrozen())
        assertEquals(870.0 to 970.0, cv.xFixed)
    }

    @Test fun panPinnedMovesPinStart() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.setMode(ChartMode.PINNED, 1000.0) // pinStart 900
        cv.pan(50.0, 1000.0)
        assertEquals(950.0, cv.pinStart!!, 0.0)
        assertFalse(cv.xFrozen())
    }

    @Test fun panFrozenShiftsBothEdges() {
        val cv = ChartView()
        cv.setXRange(1000.0, 2000.0)
        cv.pan(100.0, 9999.0)
        assertEquals(1100.0 to 2100.0, cv.xFixed)
    }

    @Test fun shiftPinEntersPinnedThenMoves() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.shiftPin(-20.0, 1000.0) // enters pinned (pinStart 900) then -20 -> 880
        assertEquals(ChartMode.PINNED, cv.mode)
        assertEquals(880.0, cv.pinStart!!, 0.0)
    }

    @Test fun xAutoscaleFreezesAndResumes() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.xAutoscale(false, 1000.0) // freeze current (900,1000)
        assertTrue(cv.xFrozen())
        assertEquals(900.0 to 1000.0, cv.xFixed)
        cv.xAutoscale(true, 1000.0)  // resume live
        assertFalse(cv.xFrozen())
    }

    @Test fun yRangeAndAutoscaleReflectInCompute() {
        val cv = ChartView(windowSeconds = 100.0)
        assertTrue(cv.yAuto)
        cv.setYRange(0.0, 25.0)
        assertFalse(cv.yAuto)
        val data = cv.compute(TimeSeriesHistory(), 1000.0)
        assertFalse(data.yAuto)
        assertEquals(0.0, data.yMin!!, 0.0)
        assertEquals(25.0, data.yMax!!, 0.0)
        cv.yAutoscale(true)
        assertTrue(cv.compute(TimeSeriesHistory(), 1000.0).yAuto)
    }

    @Test fun yAutoscaleOffFreezesAtGivenDataExtent() {
        val cv = ChartView()
        cv.yAutoscale(false, dataMin = 2.0, dataMax = 8.0)
        assertFalse(cv.yAuto)
        assertEquals(2.0, cv.yMin!!, 0.0)
        assertEquals(8.0, cv.yMax!!, 0.0)
    }

    @Test fun invalidArgumentsThrow() {
        val cv = ChartView()
        assertThrows { cv.zoom(0.0, 1.0) }
        assertThrows { cv.zoom(-1.0, 1.0) }
        assertThrows { cv.setXRange(5.0, 5.0) }
        assertThrows { cv.setYRange(5.0, 1.0) }
        assertThrows { cv.setWindow(0.0) }
    }

    private fun assertThrows(block: () -> Unit) {
        var threw = false
        try { block() } catch (e: IllegalArgumentException) { threw = true }
        assertTrue("expected IllegalArgumentException", threw)
    }
}

class PinModeTest {
    @Test fun pinTapFromLiveEntersPinAtLeftEdge() {
        val cv = ChartView(windowSeconds = 100.0)
        assertEquals(ViewMode.LIVE, cv.viewMode())
        cv.pinTap(1000.0)
        assertEquals(ViewMode.PIN, cv.viewMode())
        assertEquals(900.0, cv.pinStart!!, 0.0) // left edge was now - window
        assertEquals(900.0 to 1000.0, cv.visibleRange(1000.0))
        assertEquals(900.0 to 1500.0, cv.visibleRange(1500.0)) // right grows, left pinned
    }

    @Test fun pinTapFromLiveFrozenEntersPinAtFrozenLeftEdge() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pan(-10.0, 1000.0) // freeze at (890,990)
        assertEquals(ViewMode.LIVE_FROZEN, cv.viewMode())
        cv.pinTap(1000.0)
        assertEquals(ViewMode.PIN, cv.viewMode())
        assertEquals(890.0, cv.pinStart!!, 0.0)
    }

    @Test fun pinTapInPinCyclesRecentPins() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(1000.0)      // enter pin at 900
        cv.goLive(1000.0)      // back to live, window 100
        cv.pinTap(2000.0)      // enter pin at 1900; history newest-first [1900, 900]
        assertEquals(1900.0, cv.pinStart!!, 0.0)
        cv.pinTap(2000.0)      // cycle -> 900
        assertEquals(900.0, cv.pinStart!!, 0.0)
        cv.pinTap(2000.0)      // cycle -> 1900
        assertEquals(1900.0, cv.pinStart!!, 0.0)
    }

    @Test fun manualPinDragReentersCycleOnCommit() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(1000.0); cv.goLive(1000.0)
        cv.pinTap(2000.0)          // pin at 1900; set [1900, 900]
        cv.pan(50.0, 2000.0)       // drag the curve -> pinStart 1950
        assertEquals(1950.0, cv.pinStart!!, 0.0)
        cv.commitManualPin()       // gesture end: 1950 becomes the newest cycle entry
        // Cycling now visits the committed 1950 (cycle length defaults to 2: {1950,1900}).
        val seen = mutableSetOf(cv.pinStart!!)
        repeat(4) { cv.pinTap(2000.0); seen.add(cv.pinStart!!) }
        assertTrue("committed dragged pin is in the cycle", 1950.0 in seen)
    }

    @Test fun manualPinDragForgottenWithoutCommit() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(1000.0); cv.goLive(1000.0)
        cv.pinTap(2000.0)          // pin at 1900; set [1900, 900]
        cv.pan(50.0, 2000.0)       // drag to 1950 but never commit
        cv.pinTap(2000.0)          // cycles the old set only
        assertTrue(cv.pinStart!! == 900.0 || cv.pinStart!! == 1900.0)
    }

    @Test fun applyAllShowsWholeSessionThenClearsOnNavigation() {
        val cv = ChartView(windowSeconds = 60.0)
        cv.applyAll(1000.0, 1500.0)
        assertEquals(ViewMode.ALL, cv.viewMode())
        assertEquals(1000.0 to 1500.0, cv.visibleRange(1500.0))
        assertEquals(1000.0 to 2000.0, cv.visibleRange(2000.0)) // left pinned, right grows
        cv.goLive(2000.0)                                       // any nav leaves ALL
        assertEquals(ViewMode.LIVE, cv.viewMode())
    }

    @Test fun pinCycleCountLimitsCycle() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.setPinCycleCount(1) // no cycling
        cv.pinTap(1000.0)
        cv.goLive(1000.0)
        cv.pinTap(2000.0)      // pin at 1900
        cv.pinTap(2000.0)      // cycle limited to 1 -> stays 1900
        assertEquals(1900.0, cv.pinStart!!, 0.0)
    }

    @Test fun windowButtonInPinRepinsAtNowMinusW() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(1000.0)               // pin at 900
        cv.applyWindow(50.0, 2000.0)    // re-pin at now - 50 = 1950
        assertEquals(ViewMode.PIN, cv.viewMode())
        assertEquals(1950.0, cv.pinStart!!, 0.0)
    }

    @Test fun windowButtonInLiveEntersCleanLive() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pan(-5.0, 1000.0)            // live-frozen
        cv.applyWindow(300.0, 1000.0)   // clean live, window 300
        assertEquals(ViewMode.LIVE, cv.viewMode())
        assertEquals(300.0, cv.windowSeconds, 0.0)
        assertEquals(700.0 to 1000.0, cv.visibleRange(1000.0))
    }

    @Test fun goLiveCapturesDisplayedSpanAsWindow() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.setXRange(940.0, 1000.0)     // frozen span 60
        cv.goLive(1000.0)
        assertEquals(ViewMode.LIVE, cv.viewMode())
        assertEquals(60.0, cv.windowSeconds, 0.0)
    }

    @Test fun longPressInLiveSetsReferenceOnlyNoModeSwitch() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinLongPress(1000.0)
        assertEquals(ViewMode.LIVE, cv.viewMode()) // did NOT switch to pin
        assertEquals(900.0, cv.referencePinTime!!, 0.0)
    }

    @Test fun longPressInPinSnapsToReference() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinLongPress(1000.0)   // set reference = 900 while live
        cv.pinTap(3000.0)         // enter pin at 2900
        assertEquals(2900.0, cv.pinStart!!, 0.0)
        cv.pinLongPress(3000.0)   // snap to reference
        assertEquals(900.0, cv.pinStart!!, 0.0)
    }

    @Test fun longPressInPinWithNoReferenceIsNoop() {
        val cv = ChartView(windowSeconds = 100.0)
        cv.pinTap(1000.0) // pin at 900, no reference set
        cv.pinLongPress(1000.0)
        assertEquals(900.0, cv.pinStart!!, 0.0)
    }
}

/**
 * Guards the extraction path that made dragging a day-long chart lag: decimation
 * now happens *inside* [TimeSeriesHistory.window], so cost and allocation must stay
 * flat in history size while the emitted series stays correct.
 */
class WindowDecimationTest {
    private fun history(n: Int, retention: Double = 1e9): TimeSeriesHistory {
        val h = TimeSeriesHistory(retentionSeconds = retention)
        for (i in 0 until n) {
            h.add("a.csv", "$i", isoAt(i), pm(i.toDouble()))
        }
        return h
    }

    /** All four PM series at the same value - the y scan covers every field,
     *  and an absent field reads as 0.0. */
    private fun pm(value: Double) = com.sensirion.sps30.wire.Wire.PM_FIELDS.associateWith { value }

    /** Epoch base + [second], as a wire-format Eastern timestamp. */
    private fun isoAt(second: Int): String =
        java.time.Instant.ofEpochSecond(BASE + second)
            .atZone(TimeSeriesHistory.EASTERN)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    @Test fun keepsFirstAndLastAndRespectsBudget() {
        val h = history(1000)
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, maxPoints = 10)
        assertTrue("within budget, got ${w.epochs.size}", w.epochs.size <= 10)
        assertEquals(0.0, w.series["pm2_5"]!!.first(), 0.0)
        assertEquals(999.0, w.series["pm2_5"]!!.last(), 0.0)
        assertEquals("totalCount reports pre-decimation size", 1000, w.totalCount)
    }

    @Test fun everySeriesIsDecimatedInLockstep() {
        val h = history(500)
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, maxPoints = 25)
        for (field in com.sensirion.sps30.wire.Wire.PM_FIELDS) {
            assertEquals(w.epochs.size, w.series[field]!!.size)
        }
        // pm2_5 == the point ordinal, so values must match the emitted epochs.
        val pm = w.series["pm2_5"]!!
        for (i in w.epochs.indices) assertEquals(w.epochs[i] - BASE, pm[i], 0.0)
    }

    @Test fun yRangeCoversTheWholeWindowNotJustEmittedPoints() {
        val h = TimeSeriesHistory(retentionSeconds = 1e9)
        for (i in 0 until 101) {
            // A lone spike at :50, which a stride of 100 would step straight over.
            val v = if (i == 50) 999.0 else 1.0
            h.add("a.csv", "$i", isoAt(i), pm(v))
        }
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, maxPoints = 2)
        assertEquals(2, w.epochs.size)
        assertFalse("spike was decimated away", w.series["pm2_5"]!!.any { it == 999.0 })
        assertEquals("but autoscale still sees it", 999.0, w.yMax!!, 0.0)
        assertEquals(1.0, w.yMin!!, 0.0)
    }

    @Test fun budgetInteractsWithFlagFilter() {
        val h = TimeSeriesHistory(retentionSeconds = 1e9)
        for (i in 0 until 100) {
            val event = if (i < 40) "settle" else ""
            h.add("a.csv", "$i", isoAt(i), pm(i.toDouble()), event)
        }
        val w = h.window(
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
            excludeFlagged = true, maxPoints = 10,
        )
        assertEquals("60 unflagged points", 60, w.totalCount)
        assertTrue(w.epochs.size <= 10)
        assertEquals("first unflagged", 40.0, w.series["pm2_5"]!!.first(), 0.0)
        assertEquals(99.0, w.series["pm2_5"]!!.last(), 0.0)
        assertEquals("y range skips flagged points", 40.0, w.yMin!!, 0.0)
    }

    @Test fun undecimatedWhenNoBudgetGiven() {
        val h = history(300)
        assertEquals(300, h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).epochs.size)
    }

    @Test fun outOfOrderBackfillIsSortedNotQuadratic() {
        val h = TimeSeriesHistory(retentionSeconds = 1e9)
        // Live data first, then a backfilled file that predates all of it.
        for (i in 500 until 600) h.add("a.csv", "$i", isoAt(i), pm(i.toDouble()))
        val columns = listOf("index", "timestamp") + com.sensirion.sps30.wire.Wire.PM_FIELDS
        val rows = (0 until 500).map { i ->
            listOf("$i", isoAt(i)) + com.sensirion.sps30.wire.Wire.PM_FIELDS.map { "${i.toDouble()}" }
        }
        assertEquals(500, h.addRows("a.csv", columns, rows))

        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        assertEquals(600, w.epochs.size)
        for (i in 1 until w.epochs.size) {
            assertTrue("epochs must stay ascending at $i", w.epochs[i] >= w.epochs[i - 1])
        }
        assertEquals(0.0, w.series["pm2_5"]!!.first(), 0.0)
        assertEquals(599.0, w.series["pm2_5"]!!.last(), 0.0)
    }

    /**
     * The actual regression guard. 48h at 1Hz is the retention ceiling, and the
     * chart recomputes on every gesture flush, so a full-window compute has to stay
     * in single-digit milliseconds. Pre-fix this materialised ~170k points x 4
     * fields through a hash lookup each and took orders of magnitude longer.
     */
    @Test fun fullWindowComputeStaysFastOnTwoDaysOfData() {
        val n = 172_800
        val h = history(n)
        assertEquals(n, h.size)
        val cv = ChartView(windowSeconds = 300.0)
        cv.applyAll(BASE.toDouble(), (BASE + n).toDouble())

        repeat(3) { cv.compute(h, (BASE + n).toDouble(), maxPoints = 1200) } // warm up JIT
        val started = System.nanoTime()
        val iterations = 20
        repeat(iterations) { cv.compute(h, (BASE + n).toDouble(), maxPoints = 1200) }
        val perComputeMs = (System.nanoTime() - started) / 1e6 / iterations

        val data = cv.compute(h, (BASE + n).toDouble(), maxPoints = 1200)
        assertTrue("decimated to <= 1200, got ${data.x.size}", data.x.size <= 1200)
        assertEquals(n, data.sampleCount)
        assertEquals((n - 1).toDouble(), data.dataYMax!!, 0.0)
        assertTrue(
            "full-window compute took %.1f ms/call over %d points".format(perComputeMs, n),
            perComputeMs < 50.0,
        )
    }

    private companion object {
        const val BASE = 1_770_000_000L
    }
}

/**
 * Averaging: the mechanism behind both the manual "avg" buttons and Auto Set.
 * Thinning by dropping samples would discard exactly the excursions worth
 * seeing, so buckets average instead - a spike must raise its bucket, not vanish.
 */
class PlotAveragingTest {
    private fun pm(v: Double) = com.sensirion.sps30.wire.Wire.PM_FIELDS.associateWith { v }

    private fun isoAt(second: Int): String =
        java.time.Instant.ofEpochSecond(BASE + second)
            .atZone(TimeSeriesHistory.EASTERN)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    private fun history(n: Int, value: (Int) -> Double): TimeSeriesHistory {
        val h = TimeSeriesHistory(retentionSeconds = 1e9)
        for (i in 0 until n) h.add("a.csv", "$i", isoAt(i), pm(value(i)))
        return h
    }

    @Test fun bucketsAverageRatherThanDrop() {
        // 0..59 in 10s buckets: means are 4.5, 14.5, 24.5, 34.5, 44.5, 54.5.
        val h = history(60) { it.toDouble() }
        val w = h.window(
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, bucketSeconds = 10.0,
        )
        assertEquals(6, w.epochs.size)
        val pm = w.series["pm2_5"]!!
        assertEquals(4.5, pm.first(), 1e-9)
        assertEquals(54.5, pm.last(), 1e-9)
    }

    @Test fun aSpikeSurvivesAveraging() {
        // One 500 spike among 1.0s. Dropping samples could step over it; the
        // bucket mean must still rise well clear of baseline.
        val h = history(60) { if (it == 33) 500.0 else 1.0 }
        val w = h.window(
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, bucketSeconds = 10.0,
        )
        val pm = w.series["pm2_5"]!!
        assertTrue("spike bucket should stand out, got ${pm.toList()}", pm.any { it > 40.0 })
        assertEquals("and autoscale must cover it", pm.max(), w.yMax!!, 1e-9)
    }

    @Test fun bucketsAreAlignedToAbsoluteTimeNotWindowStart() {
        // Same data, two different window starts -> identical bucket timestamps,
        // so points do not shuffle as the user pans.
        val h = history(120) { it.toDouble() }
        val a = h.window(BASE + 0.0, BASE + 119.0, bucketSeconds = 30.0)
        val b = h.window(BASE + 7.0, BASE + 119.0, bucketSeconds = 30.0)
        val shared = a.epochs.toList().intersect(b.epochs.toList().toSet())
        assertTrue("expected shared bucket centres, got ${a.epochs.toList()} vs ${b.epochs.toList()}",
            shared.size >= 3)
    }

    @Test fun rawWhenNoBucketRequested() {
        val h = history(50) { it.toDouble() }
        val w = h.window(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        assertEquals(50, w.epochs.size)
    }

    @Test fun autoIntervalFollowsTheVisibleSpanAndDecaysOnZoomIn() {
        val h = history(20_000) { 5.0 }
        val cv = ChartView(windowSeconds = 300.0)
        cv.setAutoInterval()

        // Whole session on screen: averaging must kick in.
        cv.applyAll(BASE.toDouble(), (BASE + 20_000).toDouble())
        val wide = cv.compute(h, (BASE + 20_000).toDouble(), maxPoints = 600)
        assertTrue("expected averaging over a 20k-point span", wide.bucketSeconds != null)
        assertTrue(wide.autoInterval)

        // Zoomed right in: few enough points that averaging stops helping, and it
        // must fall away by itself without the user knowing it was ever on.
        cv.applyWindow(120.0, (BASE + 20_000).toDouble())
        val near = cv.compute(h, (BASE + 20_000).toDouble(), maxPoints = 600)
        assertNull("averaging should decay to raw when zoomed in", near.bucketSeconds)
    }

    @Test fun pickingAnIntervalByHandEndsAutoMode() {
        val cv = ChartView(windowSeconds = 300.0)
        cv.setAutoInterval()
        assertTrue(cv.autoInterval)
        cv.setPlotInterval(30.0)
        assertFalse(cv.autoInterval)
        assertEquals(30.0, cv.plotIntervalSeconds!!, 0.0)
        // "raw" clears the bucket entirely.
        cv.setPlotInterval(null)
        assertNull(cv.plotIntervalSeconds)
    }

    private companion object {
        const val BASE = 1_770_000_000L
    }
}

/**
 * Auto works out the averaging the data actually wants, then hands it to the UI
 * as a button of its own. Constrained to a fixed ladder it kept landing one step
 * short of enough smoothing with the next step far too much.
 */
class AutoIntervalChoiceTest {
    private fun pm(v: Double) = com.sensirion.sps30.wire.Wire.PM_FIELDS.associateWith { v }

    private fun isoAt(second: Int): String =
        java.time.Instant.ofEpochSecond(1_770_000_000L + second)
            .atZone(TimeSeriesHistory.EASTERN)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    private fun history(n: Int): TimeSeriesHistory {
        val h = TimeSeriesHistory(retentionSeconds = 1e9)
        for (i in 0 until n) h.add("a.csv", "$i", isoAt(i), pm(5.0))
        return h
    }

    @Test fun autoPicksAWholeNumberOfSeconds() {
        val cv = ChartView(windowSeconds = 300.0)
        cv.setAutoInterval()
        val h = history(20_000)
        cv.applyAll(1_770_000_000.0, 1_770_020_000.0)
        val bucket = cv.compute(h, 1_770_020_000.0, maxPoints = 600).bucketSeconds
        assertTrue("expected averaging over a 20k-point span", bucket != null)
        assertEquals("fractional seconds are noise in a button label",
            bucket!!, Math.floor(bucket), 0.0)
        assertTrue(bucket >= 2.0)
    }

    @Test fun autoSizesTheBucketToTheSpan() {
        val h = history(20_000)
        val cv = ChartView(windowSeconds = 300.0)
        cv.setAutoInterval()
        cv.applyAll(1_770_000_000.0, 1_770_020_000.0)
        val wide = cv.compute(h, 1_770_020_000.0, maxPoints = 600).bucketSeconds!!
        // Roughly span/budget, not an arbitrary step off a ladder.
        assertEquals(20_000.0 / 600, wide, 2.0)
    }

    @Test fun autoFallsToRawWhenEverySampleAlreadyFits() {
        // 60s of 1Hz data against a 600-point budget: nothing to average away,
        // so auto must leave the data alone rather than smoothing it anyway.
        val cv = ChartView(windowSeconds = 60.0)
        cv.setAutoInterval()
        val h = history(120)
        cv.applyWindow(60.0, 1_770_000_120.0)
        val data = cv.compute(h, 1_770_000_120.0, maxPoints = 600)
        assertNull(data.bucketSeconds)
        assertNull("and offers no button for a value it did not pick", data.autoChosenSeconds)
    }

    @Test fun theChosenValueSurvivesSwitchingToRaw() {
        // The whole point of surfacing it as a button: raw and "what auto
        // decided" have to stay one tap apart.
        val cv = ChartView(windowSeconds = 300.0)
        cv.setAutoInterval()
        val h = history(20_000)
        cv.applyAll(1_770_000_000.0, 1_770_020_000.0)
        val chosen = cv.compute(h, 1_770_020_000.0, maxPoints = 600).autoChosenSeconds
        assertTrue(chosen != null)

        cv.setPlotInterval(null) // user taps raw
        val raw = cv.compute(h, 1_770_020_000.0, maxPoints = 600)
        assertNull(raw.bucketSeconds)
        assertEquals("the button must still be offered", chosen, raw.autoChosenSeconds)

        cv.setPlotInterval(chosen) // user taps it back
        assertEquals(chosen, cv.compute(h, 1_770_020_000.0, maxPoints = 600).bucketSeconds)
    }
}

/**
 * A y axis has to carry labelled gridlines whatever the data happens to span.
 * Rounding the labels to whole numbers made that conditional: a range sitting
 * between two integers - 1.2 to 1.8 - contained no labelled value at all, and
 * the axis drew with no lines and no numbers.
 */
class YAxisRangeTest {
    private fun pm(v: Double) = com.sensirion.sps30.wire.Wire.PM_FIELDS.associateWith { v }

    private fun isoAt(second: Int): String =
        java.time.Instant.ofEpochSecond(1_770_000_000L + second)
            .atZone(TimeSeriesHistory.EASTERN)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    /** Values the drawing code would label, for a given data extent. */
    private fun labelledValues(lo: Double, hi: Double): List<Double> {
        val h = TimeSeriesHistory(retentionSeconds = 1e9)
        h.add("a.csv", "0", isoAt(0), pm(lo))
        h.add("a.csv", "1", isoAt(1), pm(hi))
        val cv = ChartView(windowSeconds = 60.0)
        val d = cv.compute(h, 1_770_000_060.0)
        // Mirrors PmChart: round steps from the (padded) data extent.
        val min = d.dataYMin!!
        val max = d.dataYMax!!
        val step = maxOf(1.0, listOf(1.0, 2.0, 5.0, 10.0)
            .map { it * Math.pow(10.0, Math.floor(Math.log10(((max - min) / 4).coerceAtLeast(1e-9)))) }
            .firstOrNull { it >= (max - min) / 4 } ?: 1.0)
        val bottom = Math.floor(min / step) * step
        val top = maxOf(Math.ceil(max / step) * step, bottom + step * 2)
        return generateSequence(bottom) { it + step }.takeWhile { it <= top + 1e-9 }.toList()
    }

    @Test fun aRangeBetweenTwoIntegersStillGetsLabels() {
        val labels = labelledValues(1.2, 1.8)
        assertTrue("expected at least two labelled lines, got $labels", labels.size >= 2)
        assertTrue("labels should be whole numbers, got $labels",
            labels.all { it == Math.floor(it) })
    }

    @Test fun aFlatReadingStillGetsLabels() {
        val labels = labelledValues(1.5, 1.5)
        assertTrue("a flat trace still needs an axis, got $labels", labels.size >= 2)
    }

    @Test fun aWideRangeUsesRoundStepsNotHundredsOfLines() {
        val labels = labelledValues(0.0, 300.0)
        assertTrue("expected a handful of lines, got ${labels.size}", labels.size in 3..12)
        assertTrue(labels.all { it == Math.floor(it) })
    }
}
