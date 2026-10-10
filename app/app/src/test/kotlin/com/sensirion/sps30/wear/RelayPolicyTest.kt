package com.sensirion.sps30.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch-battery guard. Each push wakes the watch radio and can force a
 * complication redraw, so the policy must stay stingy in steady air while never
 * letting the displayed value go stale.
 */
class RelayPolicyTest {

    private val minGap = 180_000L
    private val heartbeat = 300_000L

    private fun pm(value: Double) = mapOf(
        "pm1_0" to value, "pm2_5" to value, "pm4_0" to value, "pm10" to value,
    )

    private fun should(sinceMs: Long, last: Map<String, Double>, now: Map<String, Double>) =
        RelayPolicy.shouldPublish(
            nowMs = sinceMs, lastPublishMs = 0L,
            minGapMs = minGap, heartbeatMs = heartbeat,
            lastPublished = last, current = now,
        )

    @Test fun firstReadingAlwaysPublishes() {
        assertTrue(should(minGap, emptyMap(), pm(3.5)))
    }

    @Test fun minimumGapIsAbsolute() {
        // Even a huge jump waits for the user's configured interval.
        assertFalse(should(minGap - 1, pm(3.5), pm(500.0)))
    }

    @Test fun steadyAirIsSuppressedUntilTheHeartbeat() {
        assertFalse("noise below the delta is not worth a push", should(minGap, pm(3.5), pm(3.6)))
        assertFalse(should(heartbeat - 1, pm(3.5), pm(3.6)))
        assertTrue("but the watch must not go stale", should(heartbeat, pm(3.5), pm(3.6)))
    }

    @Test fun realChangePublishesAsSoonAsTheGapAllows() {
        assertTrue(should(minGap, pm(3.5), pm(4.0)))
        assertTrue("a drop counts too", should(minGap, pm(4.0), pm(3.5)))
    }

    @Test fun movementOnAnySeriesCounts() {
        val before = mapOf("pm1_0" to 1.0, "pm2_5" to 1.0, "pm4_0" to 1.0, "pm10" to 1.0)
        val coarseOnly = before + ("pm10" to 9.0)
        assertTrue(RelayPolicy.moved(before, coarseOnly))
        assertFalse(RelayPolicy.moved(before, before))
    }

    @Test fun unknownPreviousValuePublishes() {
        // NaN (a field the daemon omitted) must not silently suppress forever.
        assertTrue(RelayPolicy.moved(mapOf("pm2_5" to Double.NaN), mapOf("pm2_5" to 3.5)))
    }
}
