package com.sensirion.sps30.wear

import com.sensirion.sps30.wire.Wire
import kotlin.math.abs

/**
 * Decides whether a reading is worth pushing to the watch.
 *
 * Pushes are not free: each one wakes the watch's radio, restarts its Data Layer
 * listener, rewrites a SharedPreferences cache and can force a complication redraw.
 * Relaying the daemon's ~1 Hz stream verbatim spent that budget ~4300 times a day
 * to mostly re-send the same number, which is what showed up as watch battery drain.
 *
 * Three gates, cheapest first:
 *  - a hard minimum gap the user controls (`AppConfig.watchRelaySeconds`),
 *  - then either the reading moved by [MIN_DELTA] on some PM series,
 *  - or [heartbeatMs] elapsed, so the watch never crosses its own staleness
 *    threshold and greys the value out.
 */
object RelayPolicy {

    /** ug/m3 a PM series must move before a push earns its battery cost. */
    const val MIN_DELTA = 0.5

    fun shouldPublish(
        nowMs: Long,
        lastPublishMs: Long,
        minGapMs: Long,
        heartbeatMs: Long,
        lastPublished: Map<String, Double>,
        current: Map<String, Double>,
    ): Boolean {
        val since = nowMs - lastPublishMs
        if (since < minGapMs) return false
        if (lastPublished.isEmpty()) return true
        if (since >= heartbeatMs) return true
        return moved(lastPublished, current)
    }

    /** True if any PM series differs by at least [MIN_DELTA]. */
    fun moved(before: Map<String, Double>, after: Map<String, Double>): Boolean =
        Wire.PM_FIELDS.any { field ->
            val now = after[field] ?: return@any false
            val was = before[field] ?: return@any true
            // NaN compares false everywhere, so an unknown previous value pushes.
            !(abs(now - was) < MIN_DELTA)
        }
}
