package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alert engine decides *whether* to fire; the notifier only carries it out.
 * Readings arrive up to once a second, so the whole value of this class is that
 * it fires on transitions rather than on every sample above the line.
 */
class AlertEngineTest {

    private var clock = 0L
    private val engine = AlertEngine { clock }

    private val rule = AlertRule(id = "a", label = "Elevated", threshold = 20.0)
    private val rules = listOf(rule)

    private fun read(pm25: Double) = engine.evaluate(rules, mapOf("pm2_5" to pm25))

    @Test fun firesOnceOnCrossingNotOnEverySampleAbove() {
        assertTrue(read(25.0).isNotEmpty())
        assertTrue("already breached - must not fire again", read(26.0).isEmpty())
        assertTrue(read(90.0).isEmpty())
    }

    @Test fun staysQuietBelowTheThreshold() {
        assertTrue(read(19.9).isEmpty())
        assertTrue(engine.active.isEmpty())
    }

    @Test fun hysteresisStopsFlappingOnTheLine() {
        assertTrue(read(20.0).isNotEmpty())
        // Dipping just under the line must NOT re-arm, or air hovering at the
        // threshold would alert continuously.
        assertTrue(read(19.0).isEmpty())
        assertTrue("still considered breached", rule.id in engine.active)
        assertTrue(read(20.5).isEmpty())
        // Only a clear drop re-arms it.
        clock += AlertEngine.COOLDOWN_MS
        assertTrue(read(17.0).isEmpty())
        assertFalse(rule.id in engine.active)
        assertTrue("re-armed, so a fresh crossing fires", read(21.0).isNotEmpty())
    }

    @Test fun cooldownCapsHowOftenARuleCanFire() {
        assertTrue(read(30.0).isNotEmpty())
        read(1.0) // re-arm
        assertTrue("within cooldown, so silent despite re-crossing", read(30.0).isEmpty())
        clock += AlertEngine.COOLDOWN_MS
        read(1.0)
        assertTrue(read(30.0).isNotEmpty())
    }

    @Test fun disabledAndReactionlessRulesNeverFire() {
        val off = rule.copy(enabled = false)
        assertTrue(engine.evaluate(listOf(off), mapOf("pm2_5" to 500.0)).isEmpty())

        val inert = rule.copy(
            notify = false, vibrate = false, sound = false,
            watch = false, highlightPlot = false,
        )
        assertFalse("a rule with no reactions is inert", inert.hasReaction)
        assertTrue(engine.evaluate(listOf(inert), mapOf("pm2_5" to 500.0)).isEmpty())
    }

    @Test fun levelsAreIndependent() {
        val low = AlertRule(id = "low", label = "Low", threshold = 20.0)
        val high = AlertRule(id = "high", label = "High", threshold = 50.0)
        val both = listOf(low, high)

        val first = engine.evaluate(both, mapOf("pm2_5" to 25.0))
        assertEquals(listOf("low"), first.map { it.id })

        // Crossing the higher level fires only that one; the lower is already breached.
        val second = engine.evaluate(both, mapOf("pm2_5" to 60.0))
        assertEquals(listOf("high"), second.map { it.id })
        assertEquals(setOf("low", "high"), engine.active)
    }

    @Test fun watchesTheRulesOwnField() {
        val coarse = AlertRule(id = "c", label = "Coarse", threshold = 10.0, field = "pm10")
        val list = listOf(coarse)
        assertTrue(engine.evaluate(list, mapOf("pm2_5" to 900.0)).isEmpty())
        assertTrue(engine.evaluate(list, mapOf("pm10" to 11.0)).isNotEmpty())
    }

    @Test fun missingFieldIsNotABreach() {
        assertTrue(engine.evaluate(rules, mapOf("pm10" to 999.0)).isEmpty())
    }

    @Test fun deletedRulesDropTheirState() {
        read(30.0)
        assertTrue(rule.id in engine.active)
        engine.evaluate(emptyList(), mapOf("pm2_5" to 30.0))
        assertTrue("state for removed rules must not linger", engine.active.isEmpty())
    }

    @Test fun rearmMakesTheTestButtonAlwaysWork() {
        assertTrue(read(30.0).isNotEmpty())
        assertTrue(read(30.0).isEmpty())
        engine.rearm(rule)
        assertTrue("test must fire even mid-breach and mid-cooldown", read(30.0).isNotEmpty())
    }

    @Test fun defaultsAlertAboutSomethingWorthKnowing() {
        val defaults = AlertEngine.defaults()
        val enabled = defaults.filter { it.enabled }
        assertEquals("a fresh install should not be silent", 1, enabled.size)
        assertEquals(20.0, enabled.single().threshold, 0.0)
        assertTrue(enabled.single().notify)
        // The louder levels exist but stay off until asked for.
        assertTrue(defaults.any { it.vibrate })
        assertTrue(defaults.any { it.sound })
    }
}
