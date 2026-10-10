package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Wire
import java.io.BufferedReader

/**
 * Reads recorded session CSVs back off this device and averages them down to
 * something a phone can plot.
 *
 * Deliberately forgiving. The stored files span the whole life of the project:
 * the oldest have no `event` column, some are a header and nothing else, and a
 * transfer that was interrupted leaves a half-written final line. None of that
 * is worth an error dialog when the user has asked to see whatever is there, so
 * the rule throughout is to take what parses and account for the rest, never to
 * abandon a file over one bad row or the run over one bad file.
 *
 * The columns are found by name from the header, so a file whose column order
 * differs, or which carries columns this build has never heard of, still reads
 * correctly. A file whose header cannot be recognised at all is reported rather
 * than guessed at.
 */
object SessionCsv {

    /** Where the fields this build cares about sit in one file's rows. */
    class Layout(
        val timestampCol: Int,
        /** Column of each [Wire.PM_FIELDS] entry, -1 when the file lacks it. */
        val fieldCols: IntArray,
        /** Column of `event`, -1 in files written before it existed. */
        val eventCol: Int,
    ) {
        companion object {
            /**
             * Read a header line, or null if it is not an SPS30 session header.
             *
             * The bar is a timestamp plus at least one PM field: without a time
             * there is nothing to plot against, and without a value there is
             * nothing to plot. Anything richer than that is a bonus.
             */
            fun of(header: String): Layout? {
                val names = header.split(',').map { it.trim().lowercase() }
                val ts = names.indexOf("timestamp")
                if (ts < 0) return null
                val cols = IntArray(Wire.PM_FIELDS.size) { names.indexOf(Wire.PM_FIELDS[it]) }
                if (cols.all { it < 0 }) return null
                return Layout(ts, cols, names.indexOf(Wire.COL_EVENT))
            }
        }
    }

    /** Why a file contributed nothing, for an honest summary afterwards. */
    enum class Skip { EMPTY, UNRECOGNISED }

    /** One file's contribution. [skip] is null when rows were produced. */
    class FileResult(val rowsRead: Long, val pointsEmitted: Int, val skip: Skip?)

    /**
     * Read one session file, averaging into fixed [bucketSeconds] windows, and
     * hand each averaged point to [emit] as (epoch, values, flagged).
     *
     * Buckets are aligned to absolute multiples of [bucketSeconds] and the point
     * is stamped at the middle of its bucket, so the same file always reduces to
     * the same points regardless of where reading started. That is what lets the
     * bucket ordinal double as a de-duplication key: re-importing an archive
     * that was already loaded adds nothing rather than a second copy.
     *
     * A bucket is flagged if every reading in it was flagged. One disturbed
     * reading among sixty should not condemn the minute, but a warm-up period
     * entirely made of disturbed readings is exactly what the flag is for.
     */
    fun read(
        reader: BufferedReader,
        bucketSeconds: Double,
        emit: (bucket: Long, epoch: Double, values: DoubleArray, flagged: Boolean) -> Unit,
    ): FileResult {
        require(bucketSeconds >= 1.0) { "bucket must be at least a second, got $bucketSeconds" }
        val header = reader.readLine() ?: return FileResult(0, 0, Skip.EMPTY)
        val layout = Layout.of(header) ?: return FileResult(0, 0, Skip.UNRECOGNISED)
        val n = Wire.PM_FIELDS.size
        val bucketLength = bucketSeconds.toLong()

        var rows = 0L
        var emitted = 0
        val sums = DoubleArray(n)
        val values = DoubleArray(n)
        var bucket = Long.MIN_VALUE
        var count = 0
        var flaggedCount = 0

        fun flush() {
            if (count == 0) return
            for (f in 0 until n) values[f] = sums[f] / count
            // Middle of the bucket, not the mean of the sample times: the mean
            // drifts with which samples happened to survive, and a stable stamp
            // is what makes the bucket ordinal a usable key.
            emit(bucket, (bucket + 0.5) * bucketLength, values, flaggedCount == count)
            emitted++
            java.util.Arrays.fill(sums, 0.0)
            count = 0
            flaggedCount = 0
        }

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            val cells = line.split(',')
            // Only the timestamp is required. Every other column is read where
            // it exists, so a row cut short by an interrupted transfer still
            // contributes what it did manage to record.
            if (layout.timestampCol >= cells.size) continue
            val epoch = parseEpoch(cells[layout.timestampCol]) ?: continue
            rows++
            val id = Math.floorDiv(epoch.toLong(), bucketLength)
            if (id != bucket) {
                flush()
                bucket = id
            }
            for (f in 0 until n) {
                val c = layout.fieldCols[f]
                sums[f] += if (c in cells.indices) cells[c].toDoubleOrNull() ?: 0.0 else 0.0
            }
            if (layout.eventCol in cells.indices && cells[layout.eventCol].isNotEmpty()) flaggedCount++
            count++
        }
        flush()
        return FileResult(rows, emitted, if (emitted == 0) Skip.EMPTY else null)
    }

    /**
     * Epoch seconds from a session timestamp.
     *
     * `2026-07-26T14:27:17-04:00` is fixed-width, and a session file holds one
     * per reading - over a million across the stored set. `OffsetDateTime.parse`
     * costs microseconds each, which turns reading the archive from seconds into
     * minutes, so the exact shape is read arithmetically and anything else falls
     * back to the general parser.
     */
    fun parseEpoch(text: String): Double? {
        val s = text.trim()
        if (s.length == 25 && s[4] == '-' && s[7] == '-' && s[10] == 'T' &&
            s[13] == ':' && s[16] == ':' && (s[19] == '+' || s[19] == '-') && s[22] == ':'
        ) {
            val year = num(s, 0, 4)
            val month = num(s, 5, 7)
            val day = num(s, 8, 10)
            val hour = num(s, 11, 13)
            val minute = num(s, 14, 16)
            val second = num(s, 17, 19)
            val offsetHour = num(s, 20, 22)
            val offsetMinute = num(s, 23, 25)
            if (year >= 0 && month in 1..12 && day in 1..31 && hour in 0..23 &&
                minute in 0..59 && second in 0..60 && offsetHour >= 0 && offsetMinute >= 0
            ) {
                val offset = (offsetHour * 3600 + offsetMinute * 60).toLong()
                val local = daysFromCivil(year, month, day) * 86_400L +
                    hour * 3600L + minute * 60L + second
                return (if (s[19] == '-') local + offset else local - offset).toDouble()
            }
        }
        return TimeSeriesHistory.epochOf(s)
    }

    /** Digits of `s[from, to)` as an int, or -1 if any character is not a digit. */
    private fun num(s: String, from: Int, to: Int): Int {
        var acc = 0
        for (i in from until to) {
            val d = s[i] - '0'
            if (d < 0 || d > 9) return -1
            acc = acc * 10 + d
        }
        return acc
    }

    /**
     * Days from 1970-01-01 to a proleptic Gregorian date (Howard Hinnant's
     * `days_from_civil`), which is the whole of a calendar without a calendar
     * object per row.
     */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400                                        // [0, 399]
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1  // [0, 365]
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy                // [0, 146096]
        return era * 146_097 + doe - 719_468
    }
}
