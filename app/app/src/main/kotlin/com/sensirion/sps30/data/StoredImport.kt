package com.sensirion.sps30.data

/**
 * Progress of loading sessions stored on this device into the plot.
 *
 * The archives are hundreds of megabytes and take tens of seconds to read, so
 * this is a state a screen can render rather than a call that either returns or
 * does not. The end states say what was *not* loaded as well as what was: a
 * summary that only counts successes reads as "all of it" when it was not.
 */
sealed interface StoredImport {
    data object Idle : StoredImport

    /** Looking for stored files, before anything is known about their size. */
    data object Scanning : StoredImport

    data class Loading(
        val session: String,
        val sessionsDone: Int,
        val sessionsTotal: Int,
        val points: Int,
    ) : StoredImport {
        val fraction: Float
            get() = if (sessionsTotal > 0) {
                (sessionsDone.toDouble() / sessionsTotal).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
    }

    data class Loaded(
        val sessions: Int,
        val points: Int,
        val rows: Long,
        /** Files that held a header and no readings. */
        val empty: Int,
        /** Files whose header this build could not make sense of. */
        val unrecognised: Int,
        val bucketSeconds: Double,
        val start: Double,
        val end: Double,
        /** True when the point budget ran out before the data did. */
        val truncated: Boolean,
    ) : StoredImport

    data class Failed(val reason: String) : StoredImport

    companion object {
        /**
         * Most averaged points to hold from stored data.
         *
         * Bounds memory and keeps panning responsive. About 150,000 points is
         * some 15 MB in the columnar store and still an order of magnitude more
         * than any window can draw, so the averaging this forces is invisible
         * until the user zooms a long way in - at which point the honest answer
         * really is that the phone is not holding every original reading.
         */
        const val MAX_POINTS = 150_000

        /**
         * Bytes per stored CSV row, measured across the recorded archive
         * (a 79,746-row file is 17.5 MB). Only used to choose an averaging
         * window before reading anything, so being roughly right is enough.
         */
        const val ROW_BYTES = 220.0

        /** Averaging windows to choose from - values a person would name. */
        val BUCKETS = listOf(
            1.0, 2.0, 5.0, 10.0, 15.0, 30.0,
            60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0,
        )

        /**
         * The coarsest-but-smallest averaging window that keeps an estimated
         * [totalBytes] of stored CSV inside [MAX_POINTS].
         *
         * Chosen up front from file sizes rather than adapted while reading,
         * because the bucket ordinal is also the de-duplication key: changing it
         * halfway would make the same session import twice.
         */
        fun bucketFor(totalBytes: Long, maxPoints: Int = MAX_POINTS): Double {
            val rows = totalBytes / ROW_BYTES
            val needed = rows / maxPoints.coerceAtLeast(1)
            return BUCKETS.firstOrNull { it >= needed } ?: BUCKETS.last()
        }
    }
}
