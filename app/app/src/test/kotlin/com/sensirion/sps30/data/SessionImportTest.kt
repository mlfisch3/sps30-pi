package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * Reading the stored archive back.
 *
 * The files span the life of the project, so tolerance is the requirement being
 * tested here as much as correctness: the oldest have no `event` column, a great
 * many are a header and nothing else, and an interrupted transfer leaves a
 * half-written last line.
 */
class SessionCsvTest {

    private val header =
        "index,timestamp,pm1_0,pm2_5,pm4_0,pm10,nc0_5,nc1_0,nc2_5,nc4_0,nc10,typical_size,event"
    private val headerNoEvent =
        "index,timestamp,pm1_0,pm2_5,pm4_0,pm10,nc0_5,nc1_0,nc2_5,nc4_0,nc10,typical_size"

    private fun row(index: Int, time: String, pm: Double, event: String = "") =
        "%07d,%s,%s,%s,%s,%s,0,0,0,0,0,0.5,%s".format(index, time, pm, pm, pm, pm, event)

    private fun read(text: String, bucket: Double = 1.0): Triple<List<Double>, List<Double>, SessionCsv.FileResult> {
        val epochs = ArrayList<Double>()
        val values = ArrayList<Double>()
        val result = SessionCsv.read(BufferedReader(StringReader(text)), bucket) { _, epoch, v, _ ->
            epochs.add(epoch)
            values.add(v[1]) // pm2_5
        }
        return Triple(epochs, values, result)
    }

    @Test fun readsARecentFileWithTheEventColumn() {
        val text = listOf(
            header,
            row(1, "2026-07-31T00:40:48-04:00", 1.0, "startup"),
            row(2, "2026-07-31T00:40:49-04:00", 2.0),
        ).joinToString("\n")
        val (epochs, values, result) = read(text)
        assertEquals(2, epochs.size)
        assertEquals(2L, result.rowsRead)
        assertNull(result.skip)
        assertEquals(listOf(1.0, 2.0), values)
    }

    @Test fun readsAnOlderFileThatPredatesTheEventColumn() {
        // 2026-07-18's files have twelve columns, not thirteen.
        val text = listOf(
            headerNoEvent,
            "0000001,2026-07-18T21:58:18-04:00,7.2,9.1,10.3,10.5,45.6,56.2,58.0,58.3,58.4,0.62",
        ).joinToString("\n")
        val (epochs, values, result) = read(text)
        assertEquals(1, epochs.size)
        assertEquals(9.1, values[0], 1e-9)
        assertNull(result.skip)
    }

    @Test fun aHeaderWithNoReadingsIsReportedEmptyRatherThanFailing() {
        val (_, _, result) = read(header)
        assertEquals(SessionCsv.Skip.EMPTY, result.skip)
        assertEquals(0L, result.rowsRead)
    }

    @Test fun aFileThatIsNotSessionDataIsReportedUnrecognised() {
        val (_, _, result) = read("name,quantity,price\nwidget,2,3.50")
        assertEquals(SessionCsv.Skip.UNRECOGNISED, result.skip)
    }

    @Test fun aTruncatedLastLineDoesNotDiscardTheFile() {
        // What a transfer cut off mid-write leaves behind.
        val text = listOf(
            header,
            row(1, "2026-07-31T00:40:48-04:00", 1.0),
            "0000002,2026-07-31T00:40:49-04:00,3.1,3.2",
            "0000003,2026-07-3",
        ).joinToString("\n")
        val (epochs, _, result) = read(text)
        // The short-but-timestamped row still counts; the mangled one cannot.
        assertEquals(2, epochs.size)
        assertEquals(2L, result.rowsRead)
        assertNull(result.skip)
    }

    @Test fun columnsAreFoundByNameNotPosition() {
        val text = listOf(
            "pm2_5,timestamp,pm1_0",
            "4.0,2026-07-31T00:40:48-04:00,1.0",
        ).joinToString("\n")
        val (_, values, result) = read(text)
        assertNull(result.skip)
        assertEquals(4.0, values[0], 1e-9)
    }

    @Test fun readingsInsideOneWindowBecomeOnePoint() {
        val text = (0 until 60).joinToString("\n", prefix = header + "\n") { i ->
            row(i + 1, "2026-07-31T00:40:%02d-04:00".format(i), i.toDouble())
        }
        val (epochs, values, _) = read(text, bucket = 60.0)
        assertEquals(1, epochs.size)
        assertEquals(29.5, values[0], 1e-9) // mean of 0..59
    }

    @Test fun theSameFileAlwaysReducesToTheSameBucketsSoReimportingAddsNothing() {
        val text = (0 until 40).joinToString("\n", prefix = header + "\n") { i ->
            row(i + 1, "2026-07-31T00:40:%02d-04:00".format(i + 10), 1.0)
        }
        fun buckets(): List<Long> {
            val out = ArrayList<Long>()
            SessionCsv.read(BufferedReader(StringReader(text)), 15.0) { b, _, _, _ -> out.add(b) }
            return out
        }
        assertEquals(buckets(), buckets())
        // Aligned to absolute time, so the first bucket does not start at the
        // first reading - which is what makes the ordinal a stable key.
        assertTrue(buckets().zipWithNext().all { (a, b) -> b == a + 1 })
    }

    @Test fun aBucketIsFlaggedOnlyWhenEveryReadingInItWasDisturbed() {
        val flags = ArrayList<Boolean>()
        val text = listOf(
            header,
            row(1, "2026-07-31T00:40:00-04:00", 1.0, "startup"),
            row(2, "2026-07-31T00:40:01-04:00", 1.0, "startup"),
            row(3, "2026-07-31T00:41:00-04:00", 1.0, "startup"),
            row(4, "2026-07-31T00:41:01-04:00", 1.0),
        ).joinToString("\n")
        SessionCsv.read(BufferedReader(StringReader(text)), 60.0) { _, _, _, f -> flags.add(f) }
        assertEquals(listOf(true, false), flags)
    }

    @Test fun theFastTimestampPathAgreesWithTheGeneralParser() {
        for (t in listOf(
            "2026-07-31T00:40:48-04:00",
            "2026-01-01T00:00:00-05:00",
            "2024-02-29T23:59:59-05:00", // leap day
            "2026-12-31T23:59:59+01:00", // a positive offset
        )) {
            assertEquals(TimeSeriesHistory.epochOf(t), SessionCsv.parseEpoch(t))
        }
    }

    @Test fun anUnusableTimestampIsRejectedRatherThanGuessedAt() {
        assertNull(SessionCsv.parseEpoch("2026-07-3"))
        assertNull(SessionCsv.parseEpoch(""))
        assertNull(SessionCsv.parseEpoch("20x6-07-31T00:40:48-04:00"))
    }
}

/** Holding imported points beside the live stream. */
class ImportedHistoryTest {

    private val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0)

    private fun historyWithLiveNow(now: Double): TimeSeriesHistory {
        val h = TimeSeriesHistory(retentionSeconds = 3600.0)
        h.addRows(
            "SPS30-live.csv",
            listOf("index", "timestamp") + Wire.FIELDS,
            (0 until 3).map { i ->
                listOf("${i + 1}", iso(now + i)) + List(Wire.FIELDS.size) { "5.0" }
            },
        )
        return h
    }

    private fun iso(epoch: Double): String = java.time.Instant.ofEpochSecond(epoch.toLong())
        .atZone(TimeSeriesHistory.EASTERN)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    @Test fun importedPointsSurviveRetentionThatWouldOtherwiseDropThem() {
        val now = 1_800_000_000.0
        val h = historyWithLiveNow(now)
        // A week old, against an hour of retention.
        assertTrue(h.addImported("SPS30-old.csv", 1, now - 604_800.0, values))
        h.finishImport()
        assertEquals(4, h.size)
        assertEquals(now - 604_800.0, h.earliestEpoch()!!, 1e-6)
    }

    @Test fun removingTheImportLeavesTheLiveStreamIntact() {
        val now = 1_800_000_000.0
        val h = historyWithLiveNow(now)
        h.addImported("SPS30-old.csv", 1, now - 604_800.0, values)
        h.addImported("SPS30-old.csv", 2, now - 604_700.0, values)
        h.finishImport()
        assertEquals(5, h.size)

        h.clearImported()
        assertEquals(3, h.size)
        assertEquals(now, h.earliestEpoch()!!, 1e-6)
        assertTrue(h.importedSegments().isEmpty())
    }

    @Test fun aSessionIsNeverImportedTwice() {
        val h = TimeSeriesHistory()
        val now = 1_800_000_000.0
        assertTrue(h.addImported("SPS30-a.csv", 7, now, values))
        assertTrue(!h.addImported("SPS30-a.csv", 7, now, values))
        assertTrue(h.hasImported("SPS30-a.csv"))
        assertEquals(1, h.importedPointCount())
    }

    @Test fun anImportedSessionCannotCollideWithTheLiveFileOfTheSameName() {
        // Both are called SPS30-live.csv, and an imported point's index is a time
        // bucket rather than the sensor's counter - so un-namespaced they would
        // silently discard each other.
        val now = 1_800_000_000.0
        val h = historyWithLiveNow(now)
        assertTrue(h.addImported("SPS30-live.csv", 1, now - 100.0, values))
        h.finishImport()
        assertEquals(4, h.size)
    }

    @Test fun framingTheLiveSessionIgnoresWhatWasImported() {
        val now = 1_800_000_000.0
        val h = historyWithLiveNow(now)
        h.addImported("SPS30-old.csv", 1, now - 604_800.0, values)
        h.finishImport()

        assertEquals(now - 604_800.0, h.firstStableEpoch()!!, 1e-6) // "all"
        assertEquals(now, h.firstStableLiveEpoch()!!, 1e-6) // Auto Set
    }

    @Test fun importedPointsWindowLikeAnyOther() {
        val h = TimeSeriesHistory()
        val base = 1_800_000_000.0
        for (i in 0 until 10) h.addImported("SPS30-a.csv", i, base + i * 60.0, doubleArrayOf(i.toDouble(), i.toDouble(), 0.0, 0.0))
        h.finishImport()
        val w = h.window(base, base + 540.0)
        assertEquals(10, w.totalCount)
        assertEquals(0.0, w.yMin!!, 1e-9)
        assertEquals(9.0, w.yMax!!, 1e-9)
    }
}

/** Choosing how hard to average before reading anything. */
class ImportBucketTest {

    @Test fun theWholeStoredArchiveFitsInsideTheBudget() {
        // The real figure: 208 MB of CSV across 245 sessions.
        val bucket = StoredImport.bucketFor(208_000_000L)
        val estimatedPoints = (208_000_000L / StoredImport.ROW_BYTES) / bucket
        assertTrue("$bucket s left $estimatedPoints points", estimatedPoints <= StoredImport.MAX_POINTS)
    }

    @Test fun aSmallArchiveIsNotAveragedAtAll() {
        assertEquals(1.0, StoredImport.bucketFor(1_000_000L), 1e-9)
    }

    @Test fun theWindowGrowsWithTheArchiveAndIsAlwaysOneOfTheNamedSteps() {
        var previous = 0.0
        for (mb in listOf(1L, 50L, 200L, 1_000L, 20_000L)) {
            val bucket = StoredImport.bucketFor(mb * 1_000_000L)
            assertTrue(bucket >= previous)
            assertTrue("$bucket is not a named step", bucket in StoredImport.BUCKETS)
            previous = bucket
        }
    }
}

/** Not drawing lines across time when nothing was recorded there. */
class ChartGapTest {

    private fun evenly(count: Int, step: Double) = DoubleArray(count) { it * step }

    @Test fun aSteadyTraceIsNeverBroken() {
        val gap = ChartView.gapThreshold(evenly(100, 1.0), null)
        assertNotNull(gap)
        assertTrue("1 Hz data must stay joined, threshold was $gap", gap!! > 1.0)
    }

    @Test fun anOccasionalDroppedReadingDoesNotBreakTheTrace() {
        // The Pi's under-voltage drops a few seconds at a time; that is a stumble
        // in a recording, not a hole in it.
        val gap = ChartView.gapThreshold(evenly(100, 1.0), null)!!
        assertTrue("a 5 s stumble must stay joined", 5.0 <= gap)
    }

    @Test fun aGapBetweenRecordingsIsBroken() {
        val epochs = DoubleArray(100) { if (it < 50) it * 1.0 else it * 1.0 + 86_400.0 }
        val gap = ChartView.gapThreshold(epochs, null)!!
        assertTrue("a day-long break must not be joined", 86_400.0 > gap)
    }

    @Test fun theThresholdFollowsTheAveragingWindow() {
        // Hourly points are normal in a month-long view; ten minutes is not a gap.
        val hourly = ChartView.gapThreshold(evenly(100, 3600.0), 3600.0)!!
        assertTrue(hourly > 3600.0)
        assertTrue(hourly > ChartView.gapThreshold(evenly(100, 1.0), null)!!)
    }

    @Test fun theMedianIsUsedSoOneHugeGapCannotNormaliseItself() {
        // With a mean, a single two-day break in an hour of 1 Hz data would raise
        // the "normal" spacing above itself and go undetected.
        val epochs = DoubleArray(3600) { if (it < 1800) it * 1.0 else it * 1.0 + 172_800.0 }
        val gap = ChartView.gapThreshold(epochs, null)!!
        assertTrue("the break must exceed the threshold", 172_800.0 > gap)
    }

    @Test fun tooFewPointsToJudgeSaysSoRatherThanGuessing() {
        assertNull(ChartView.gapThreshold(doubleArrayOf(), null))
        assertNull(ChartView.gapThreshold(doubleArrayOf(1.0, 2.0), null))
    }
}
