package com.sensirion.sps30.data

import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Windowed slice: parallel epoch array and per-field value arrays, time-ordered.
 *
 * [epochs] and [series] are already decimated to the caller's `maxPoints` budget,
 * but [totalCount], [yMin] and [yMax] describe the *whole* window, so y-autoscale
 * still sees a spike that decimation happened to step over.
 */
class WindowData(
    val epochs: DoubleArray,
    val series: Map<String, DoubleArray>,
    val totalCount: Int = epochs.size,
    val yMin: Double? = null,
    val yMax: Double? = null,
)

/**
 * Full-resolution multi-series store keyed on (csv, index), ordered by time -
 * a Kotlin port of `chartview.TimeSeriesHistory`. De-dup is by (csv, index) so
 * live readings (whose index restarts per CSV) never collide across files.
 * Points older than `latest - retentionSeconds` are trimmed to bound memory.
 *
 * Storage is columnar: parallel primitive arrays rather than a list of point
 * objects. A 48h history at 1Hz is ~170k points, and [window] has to touch every
 * value in range to autoscale y, so that walk must be plain array reads - one
 * hash lookup per point per field is what made dragging the chart lag.
 * A [head] offset makes retention-trimming O(1) instead of shifting every point
 * down once a second.
 *
 * Not thread-safe; confine mutation to a single (UI/VM) coroutine context.
 */
class TimeSeriesHistory(retentionSeconds: Double = 172_800.0) { // 48h default

    var retentionSeconds: Double = retentionSeconds
        private set

    /** One point exposed for export. */
    data class Sample(
        val csv: String,
        val index: Int,
        val epoch: Double,
        val values: Map<String, Double>,
        val event: String = "",
    )

    private val fieldCount = Wire.PM_FIELDS.size

    private var epochs = DoubleArray(INITIAL_CAPACITY)
    private var cols = Array(fieldCount) { DoubleArray(INITIAL_CAPACITY) }
    private var csvIds = IntArray(INITIAL_CAPACITY)
    private var indices = IntArray(INITIAL_CAPACITY)
    // Interned event id; 0 is always "", so `eventIds[p] != 0` is the flagged test.
    private var eventIds = IntArray(INITIAL_CAPACITY)

    private var head = 0 // physical index of logical 0
    private var tail = 0 // physical index one past the logical last

    private val csvTable = StringTable()
    private val eventTable = StringTable()
    // Packed (csvId, index) longs - avoids allocating a Pair per dedup check.
    private val seen = HashSet<Long>()
    // High-water index per csv file, for reconnect backfill (`get_file since_index`).
    // A high-water mark, so trimming old points never lowers it.
    private val maxIndexByCsv = HashMap<String, Int>()

    // --- imported archive ---------------------------------------------------
    // Points read from session files stored on this device, kept in the same
    // store as the live stream so windowing, autoscale, the crosshair and export
    // all work on them unchanged.

    /** Oldest imported epoch, below which retention must not trim. Null when none. */
    private var archiveFloorEpoch: Double? = null

    /** Interned csv ids belonging to imports, so they can be removed again exactly. */
    private val importedIds = HashSet<Int>()

    /** Per-segment extent, insertion-ordered, for reporting what was loaded. */
    private val importedByName = LinkedHashMap<String, ImportedSegment>()

    /** What one stored session contributed to the plot. */
    data class ImportedSegment(
        val name: String,
        var points: Int = 0,
        var start: Double = Double.NaN,
        var end: Double = Double.NaN,
    )

    val size: Int get() = tail - head
    fun isEmpty(): Boolean = size == 0

    /** Highest stored Pi index for [csv] (0 if none) - the `since_index` to backfill from. */
    fun maxIndexFor(csv: String): Int = maxIndexByCsv[csv] ?: 0

    /** Time-ordered snapshot of every buffered point (for CSV export). */
    fun snapshot(): List<Sample> = ArrayList<Sample>(size).also { out ->
        for (i in 0 until size) {
            val p = head + i
            val values = LinkedHashMap<String, Double>(fieldCount)
            for (f in 0 until fieldCount) values[Wire.PM_FIELDS[f]] = cols[f][p]
            out.add(
                Sample(
                    csvTable.name(csvIds[p]),
                    indices[p],
                    epochs[p],
                    values,
                    eventTable.name(eventIds[p]),
                )
            )
        }
    }

    fun earliestEpoch(): Double? = if (isEmpty()) null else epochs[head]
    fun latestEpoch(): Double? = if (isEmpty()) null else epochs[tail - 1]

    /**
     * Epoch of the earliest *stable* (non-flagged) point - the current acquisition
     * session's true start (settling spikes are skipped). Falls back to the earliest
     * point if every buffered point is flagged, and null if empty. Used by "all".
     */
    fun firstStableEpoch(): Double? {
        for (p in head until tail) if (eventIds[p] == 0) return epochs[p]
        return earliestEpoch()
    }

    /**
     * As [firstStableEpoch], but ignoring anything imported from stored files.
     *
     * "The whole session" and "everything I have" stop being the same question
     * once older sessions can be loaded from this device, and the two need
     * separate answers: framing the live session should not silently zoom out to
     * a fortnight because an archive is also on the plot.
     */
    fun firstStableLiveEpoch(): Double? {
        if (importedIds.isEmpty()) return firstStableEpoch()
        for (p in head until tail) {
            if (csvIds[p] in importedIds) continue
            if (eventIds[p] == 0) return epochs[p]
        }
        for (p in head until tail) if (csvIds[p] !in importedIds) return epochs[p]
        return null
    }

    /** Add a live [ServerMessage.Reading]. Returns true if newly stored. */
    fun add(reading: ServerMessage.Reading): Boolean =
        add(reading.csv, reading.index, reading.timestamp, reading.values, reading.event)

    /**
     * Add one reading by parts. Returns false if it duplicated an existing
     * (csv, index) or lacked a usable timestamp. [event] non-blank marks it flagged.
     */
    fun add(
        csv: String?,
        index: String,
        timestamp: String,
        values: Map<String, Double>,
        event: String = "",
    ): Boolean {
        val name = csv ?: ""
        val csvId = csvTable.intern(name)
        val idx = indexOf(index)
        val key = keyOf(csvId, idx)
        if (key in seen) return false
        val epoch = epochOf(timestamp) ?: return false
        insertAt(bisectRight(epoch), epoch, csvId, idx, eventTable.intern(event), values)
        seen.add(key)
        noteMaxIndex(name, idx)
        trim()
        return true
    }

    /** Fold in a batch; returns the number newly added. */
    fun merge(readings: Iterable<ServerMessage.Reading>): Int {
        var added = 0
        for (r in readings) if (add(r)) added++
        return added
    }

    /**
     * Fold a daemon `file_data` payload (backfill) tagged with [csv] into history.
     * [columns] names each cell (expects `index`, `timestamp`, and PM fields, per
     * [Wire.CSV_COLUMNS]); rows past the retention horizon still de-dup by (csv, index).
     * Returns the number of rows newly stored. Mirrors `chartview.from_rows`.
     *
     * Rows are appended in bulk and the store re-sorted once if they interleave with
     * existing points, rather than shifting the tail per row - importing a day-long
     * file that predates the live buffer would otherwise be quadratic.
     */
    fun addRows(csv: String, columns: List<String>, rows: List<List<String>>): Int {
        val idxCol = columns.indexOf("index")
        val tsCol = columns.indexOf("timestamp")
        if (idxCol < 0 || tsCol < 0) return 0
        val eventCol = columns.indexOf(Wire.COL_EVENT) // -1 for older files without it
        val fieldCols = Wire.PM_FIELDS.map { columns.indexOf(it) }
        val csvId = csvTable.intern(csv)

        var added = 0
        var outOfOrder = false
        var last = latestEpoch() ?: Double.NEGATIVE_INFINITY
        ensureTailRoom(rows.size)

        for (row in rows) {
            if (idxCol >= row.size || tsCol >= row.size) continue
            val idx = indexOf(row[idxCol])
            val key = keyOf(csvId, idx)
            if (key in seen) continue
            val epoch = epochOf(row[tsCol]) ?: continue

            val p = tail
            epochs[p] = epoch
            for (f in 0 until fieldCount) {
                val ci = fieldCols[f]
                cols[f][p] = if (ci in row.indices) row[ci].toDoubleOrNull() ?: 0.0 else 0.0
            }
            csvIds[p] = csvId
            indices[p] = idx
            eventIds[p] = eventTable.intern(if (eventCol in row.indices) row[eventCol] else "")
            tail++

            if (epoch < last) outOfOrder = true
            last = epoch
            seen.add(key)
            noteMaxIndex(csv, idx)
            added++
        }

        if (added > 0) {
            if (outOfOrder) sortByEpoch()
            trim()
        }
        return added
    }

    fun setRetention(seconds: Double) {
        retentionSeconds = seconds
        trim()
    }

    fun clear() {
        head = 0
        tail = 0
        seen.clear()
        maxIndexByCsv.clear()
        importedIds.clear()
        importedByName.clear()
        archiveFloorEpoch = null
    }

    // --- importing stored sessions ------------------------------------------

    /** What each stored session contributed, in the order the segments arrived. */
    fun importedSegments(): List<ImportedSegment> = importedByName.values.toList()

    fun importedPointCount(): Int = importedByName.values.sumOf { it.points }

    /**
     * True once [segment] has been folded in.
     *
     * Consecutive archives overlap heavily - each is a copy of *every* session
     * recorded so far - so this lets a caller skip a session it already holds
     * instead of decompressing and parsing it a second time.
     */
    fun hasImported(segment: String): Boolean = segment in importedByName

    /**
     * Append one point read from a stored session file.
     *
     * [values] is in [Wire.PM_FIELDS] order and is copied, so one array may be
     * reused for every point. Returns false only for a duplicate.
     *
     * Segment names are namespaced here rather than by the caller, because
     * stored files carry the same `SPS30-*.csv` names the live stream uses and
     * an imported point's index is a time bucket, not the sensor's own counter.
     * Left un-namespaced the two would collide and silently discard each other.
     *
     * Points are appended unsorted; call [finishImport] once at the end.
     */
    fun addImported(
        segment: String,
        index: Int,
        epoch: Double,
        values: DoubleArray,
        event: String = "",
    ): Boolean {
        val csvId = csvTable.intern(IMPORT_PREFIX + segment)
        val key = keyOf(csvId, index)
        if (key in seen) return false
        ensureTailRoom(1)
        val p = tail
        epochs[p] = epoch
        for (f in 0 until fieldCount) cols[f][p] = values.getOrElse(f) { 0.0 }
        csvIds[p] = csvId
        indices[p] = index
        eventIds[p] = eventTable.intern(event)
        tail++
        seen.add(key)
        importedIds.add(csvId)
        val seg = importedByName.getOrPut(segment) { ImportedSegment(segment) }
        seg.points++
        if (seg.start.isNaN() || epoch < seg.start) seg.start = epoch
        if (seg.end.isNaN() || epoch > seg.end) seg.end = epoch
        archiveFloorEpoch = minOf(archiveFloorEpoch ?: epoch, epoch)
        return true
    }

    /** Restore time order and re-apply retention after a run of [addImported]. */
    fun finishImport() {
        if (size > 1) sortByEpoch()
        trim()
    }

    /**
     * Drop every imported point, leaving the live stream untouched.
     *
     * A real removal rather than letting retention reclaim them: the user asked
     * for these and can ask for them to go, and "they will fade out eventually"
     * is not an answer to that.
     */
    fun clearImported() {
        if (importedIds.isEmpty()) return
        var w = head
        for (p in head until tail) {
            if (csvIds[p] in importedIds) {
                seen.remove(keyOf(csvIds[p], indices[p]))
                continue
            }
            if (w != p) copyPoint(p, w)
            w++
        }
        tail = w
        importedIds.clear()
        importedByName.clear()
        archiveFloorEpoch = null
        trim()
    }

    private fun copyPoint(from: Int, to: Int) {
        epochs[to] = epochs[from]
        for (f in 0 until fieldCount) cols[f][to] = cols[f][from]
        csvIds[to] = csvIds[from]
        indices[to] = indices[from]
        eventIds[to] = eventIds[from]
    }

    /**
     * (epochs, {field: values}) for points with start <= epoch <= end, in order.
     * When [excludeFlagged], disturbed settling/clean-spike points are omitted.
     *
     * [maxPoints] decimates *during* extraction - the result never allocates more
     * than that many values however long the window is. First and last points are
     * always kept, matching the stride rule the chart used to apply afterwards.
     */
    fun window(
        startEpoch: Double,
        endEpoch: Double,
        excludeFlagged: Boolean = false,
        maxPoints: Int? = null,
        bucketSeconds: Double? = null,
    ): WindowData {
        if (bucketSeconds != null && bucketSeconds > 0.0) {
            return bucketedWindow(startEpoch, endEpoch, excludeFlagged, maxPoints, bucketSeconds)
        }
        val lo = bisectLeft(startEpoch)
        val hi = bisectRight(endEpoch)

        // Pass 1: how many points survive the flag filter. An int-array scan, so
        // even a full 48h window costs microseconds.
        var total = 0
        if (excludeFlagged) {
            for (i in lo until hi) if (eventIds[head + i] == 0) total++
        } else {
            total = (hi - lo).coerceAtLeast(0)
        }
        if (total == 0) return emptyWindow()

        val budget = if (maxPoints == null || maxPoints <= 0) total else minOf(maxPoints, total)
        val onlyLast = budget < 2
        val stride = if (onlyLast || total <= budget) 1 else ceilDiv(total - 1, budget - 1)
        val emitted = when {
            onlyLast -> 1
            stride == 1 -> total
            else -> ceilDiv(total, stride) + if ((total - 1) % stride == 0) 0 else 1
        }

        // Pass 2: emit the strided subset while accumulating the exact y range over
        // every point in the window (not just the emitted ones).
        val outEpochs = DoubleArray(emitted)
        val outCols = Array(fieldCount) { DoubleArray(emitted) }
        var yLo = Double.POSITIVE_INFINITY
        var yHi = Double.NEGATIVE_INFINITY
        var ordinal = 0 // position among filter-surviving points
        var w = 0
        for (i in lo until hi) {
            val p = head + i
            if (excludeFlagged && eventIds[p] != 0) continue
            val emit = w < emitted &&
                if (onlyLast) ordinal == total - 1
                else ordinal % stride == 0 || ordinal == total - 1
            if (emit) outEpochs[w] = epochs[p]
            for (f in 0 until fieldCount) {
                val v = cols[f][p]
                if (v < yLo) yLo = v
                if (v > yHi) yHi = v
                if (emit) outCols[f][w] = v
            }
            if (emit) w++
            ordinal++
        }

        return WindowData(
            epochs = outEpochs,
            series = seriesMap(outCols),
            totalCount = total,
            yMin = if (yLo == Double.POSITIVE_INFINITY) null else yLo,
            yMax = if (yHi == Double.NEGATIVE_INFINITY) null else yHi,
        )
    }

    /**
     * Mean of each field over fixed [bucketSeconds] windows.
     *
     * Days of 1 Hz data are unreadably dense, and thinning by simply dropping
     * samples throws away exactly the excursions worth seeing. Averaging within a
     * bucket keeps them - a spike raises its bucket's mean rather than vanishing
     * because the stride stepped over it - and yields a curve the eye can follow.
     *
     * Buckets are aligned to absolute multiples of [bucketSeconds] so the same
     * data always buckets the same way, rather than shifting as the window pans.
     * Each emitted point is timestamped at the mean of its samples, so it sits
     * where the data actually is.
     */
    private fun bucketedWindow(
        startEpoch: Double,
        endEpoch: Double,
        excludeFlagged: Boolean,
        maxPoints: Int?,
        bucketSeconds: Double,
    ): WindowData {
        val lo = bisectLeft(startEpoch)
        val hi = bisectRight(endEpoch)
        if (hi <= lo) return emptyWindow()

        val epochOut = ArrayList<Double>()
        val sums = Array(fieldCount) { ArrayList<Double>() }
        var yLo = Double.POSITIVE_INFINITY
        var yHi = Double.NEGATIVE_INFINITY

        var bucketId = Long.MIN_VALUE
        var epochSum = 0.0
        var count = 0
        val fieldSum = DoubleArray(fieldCount)

        fun flush() {
            if (count == 0) return
            epochOut.add(epochSum / count)
            for (f in 0 until fieldCount) {
                val mean = fieldSum[f] / count
                sums[f].add(mean)
                if (mean < yLo) yLo = mean
                if (mean > yHi) yHi = mean
            }
        }

        for (i in lo until hi) {
            val p = head + i
            if (excludeFlagged && eventIds[p] != 0) continue
            val id = Math.floorDiv(epochs[p].toLong(), bucketSeconds.toLong().coerceAtLeast(1L))
            if (id != bucketId) {
                flush()
                bucketId = id
                epochSum = 0.0
                count = 0
                java.util.Arrays.fill(fieldSum, 0.0)
            }
            epochSum += epochs[p]
            for (f in 0 until fieldCount) fieldSum[f] += cols[f][p]
            count++
        }
        flush()

        val total = epochOut.size
        if (total == 0) return emptyWindow()

        // A bucket per second over two days still outruns the renderer, so the
        // stride cap still applies on top of the averaging.
        val budget = if (maxPoints == null || maxPoints <= 0) total else minOf(maxPoints, total)
        val stride = if (budget < 2 || total <= budget) 1 else ceilDiv(total - 1, budget - 1)
        val kept = if (stride == 1) {
            (0 until total).toList()
        } else {
            buildList {
                var i = 0
                while (i < total) { add(i); i += stride }
                if (last() != total - 1) add(total - 1)
            }
        }

        val outEpochs = DoubleArray(kept.size) { epochOut[kept[it]] }
        val outCols = Array(fieldCount) { f -> DoubleArray(kept.size) { sums[f][kept[it]] } }
        return WindowData(
            epochs = outEpochs,
            series = seriesMap(outCols),
            totalCount = total,
            yMin = if (yLo == Double.POSITIVE_INFINITY) null else yLo,
            yMax = if (yHi == Double.NEGATIVE_INFINITY) null else yHi,
        )
    }

    // --- storage internals --------------------------------------------------

    private fun seriesMap(arrays: Array<DoubleArray>): Map<String, DoubleArray> {
        val out = LinkedHashMap<String, DoubleArray>(fieldCount)
        for (f in 0 until fieldCount) out[Wire.PM_FIELDS[f]] = arrays[f]
        return out
    }

    private fun emptyWindow() =
        WindowData(DoubleArray(0), seriesMap(Array(fieldCount) { DoubleArray(0) }), 0, null, null)

    private fun noteMaxIndex(csv: String, index: Int) {
        val prev = maxIndexByCsv[csv]
        if (prev == null || index > prev) maxIndexByCsv[csv] = index
    }

    private fun insertAt(
        pos: Int,
        epoch: Double,
        csvId: Int,
        index: Int,
        eventId: Int,
        values: Map<String, Double>,
    ) {
        ensureTailRoom(1)
        val p = head + pos
        if (p < tail) {
            val n = tail - p
            System.arraycopy(epochs, p, epochs, p + 1, n)
            for (f in 0 until fieldCount) System.arraycopy(cols[f], p, cols[f], p + 1, n)
            System.arraycopy(csvIds, p, csvIds, p + 1, n)
            System.arraycopy(indices, p, indices, p + 1, n)
            System.arraycopy(eventIds, p, eventIds, p + 1, n)
        }
        epochs[p] = epoch
        for (f in 0 until fieldCount) cols[f][p] = values[Wire.PM_FIELDS[f]] ?: 0.0
        csvIds[p] = csvId
        indices[p] = index
        eventIds[p] = eventId
        tail++
    }

    private fun ensureTailRoom(extra: Int) {
        if (tail + extra <= epochs.size) return
        val needed = size + extra
        if (needed * 2 <= epochs.size) compact() else growTo(maxOf(needed * 2, INITIAL_CAPACITY))
    }

    /** Slide the live range down to index 0 in place. */
    private fun compact() {
        if (head == 0) return
        val n = size
        System.arraycopy(epochs, head, epochs, 0, n)
        for (f in 0 until fieldCount) System.arraycopy(cols[f], head, cols[f], 0, n)
        System.arraycopy(csvIds, head, csvIds, 0, n)
        System.arraycopy(indices, head, indices, 0, n)
        System.arraycopy(eventIds, head, eventIds, 0, n)
        head = 0
        tail = n
    }

    private fun growTo(capacity: Int) {
        val n = size
        epochs = epochs.copyOfRange(head, tail).copyOf(capacity)
        cols = Array(fieldCount) { f -> cols[f].copyOfRange(head, tail).copyOf(capacity) }
        csvIds = csvIds.copyOfRange(head, tail).copyOf(capacity)
        indices = indices.copyOfRange(head, tail).copyOf(capacity)
        eventIds = eventIds.copyOfRange(head, tail).copyOf(capacity)
        head = 0
        tail = n
    }

    /** Stable sort of the live range by epoch, permuting every column together. */
    private fun sortByEpoch() {
        val n = size
        if (n < 2) return
        val order = (0 until n).sortedBy { epochs[head + it] }
        val e = DoubleArray(epochs.size)
        val c = Array(fieldCount) { DoubleArray(epochs.size) }
        val ci = IntArray(epochs.size)
        val ii = IntArray(epochs.size)
        val ei = IntArray(epochs.size)
        for (w in 0 until n) {
            val r = head + order[w]
            e[w] = epochs[r]
            for (f in 0 until fieldCount) c[f][w] = cols[f][r]
            ci[w] = csvIds[r]
            ii[w] = indices[r]
            ei[w] = eventIds[r]
        }
        epochs = e
        cols = c
        csvIds = ci
        indices = ii
        eventIds = ei
        head = 0
        tail = n
    }

    private fun trim() {
        if (isEmpty()) return
        // Retention exists to bound what a live stream accumulates on its own.
        // Imported points are not that: the user named them and waited for them,
        // and they are older than the horizon by definition, so applying
        // retention to them would delete the import as fast as it arrived.
        val cutoff = minOf(epochs[tail - 1] - retentionSeconds, archiveFloorEpoch ?: Double.MAX_VALUE)
        val drop = bisectLeft(cutoff)
        if (drop <= 0) return
        for (i in 0 until drop) seen.remove(keyOf(csvIds[head + i], indices[head + i]))
        head += drop
        if (head > epochs.size / 2) compact()
    }

    /** First logical index whose epoch > [epoch] (ties keep insertion order). */
    private fun bisectRight(epoch: Double): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (epochs[head + mid] <= epoch) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First logical index whose epoch >= [epoch]. */
    private fun bisectLeft(epoch: Double): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (epochs[head + mid] < epoch) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Interns strings to dense ids so columns can stay primitive. Id 0 is "". */
    private class StringTable {
        private val ids = HashMap<String, Int>()
        private val names = ArrayList<String>()

        init {
            intern("")
        }

        fun intern(value: String): Int = ids.getOrPut(value) {
            names.add(value)
            names.size - 1
        }

        fun name(id: Int): String = names[id]
    }

    companion object {
        val EASTERN: ZoneId = ZoneId.of("America/New_York")

        private const val INITIAL_CAPACITY = 1024

        /** Keeps imported segment names out of the live stream's key space. */
        const val IMPORT_PREFIX = "stored:"

        private fun keyOf(csvId: Int, index: Int): Long =
            (csvId.toLong() shl 32) or (index.toLong() and 0xFFFFFFFFL)

        // Ceiling of a/b for non-negative a and positive b. Kotlin Int division
        // truncates toward zero, so the -(-a/b) trick does NOT work here.
        private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

        /** Parse a wire index ("0000007" or "7") into an int; junk/blank -> 0. */
        fun indexOf(value: String): Int {
            val t = value.trim()
            if (t.isEmpty()) return 0
            t.toIntOrNull()?.let { return it }
            return t.toDoubleOrNull()?.toInt() ?: 0
        }

        /** UTC epoch seconds from an ISO timestamp, or null if unusable. */
        fun epochOf(timestamp: String): Double? {
            val t = timestamp.trim()
            if (t.isEmpty()) return null
            return try {
                OffsetDateTime.parse(t).toEpochSecond().toDouble()
            } catch (e: Exception) {
                try {
                    LocalDateTime.parse(t).atZone(EASTERN).toEpochSecond().toDouble()
                } catch (e2: Exception) {
                    null
                }
            }
        }
    }
}
