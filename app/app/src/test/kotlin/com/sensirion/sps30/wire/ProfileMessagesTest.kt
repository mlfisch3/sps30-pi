package com.sensirion.sps30.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trips the acquisition-profile and power additions to the line protocol.
 * These mirror `wire.py`; the daemon is the authority, so the client has to
 * tolerate fields it has never seen and daemons that omit the new ones entirely.
 */
class ProfileMessagesTest {

    @Test fun parsesAProfileMessage() {
        val msg = Messages.parse(
            """{"type":"profile","profile":{"mode":"duty_cycle","interval":1.0,
               "cycle_s":900.0,"warmup_s":30.0,"average_s":30.0}}""".trimIndent()
        )
        val profile = msg as ServerMessage.AcquisitionProfile
        assertEquals(Wire.MODE_DUTY_CYCLE, profile.mode)
        assertTrue(profile.dutyCycling)
        assertEquals(900.0, profile.cycleSeconds, 0.0)
        assertEquals(30.0, profile.warmupSeconds, 0.0)
    }

    @Test fun parsesAPowerMessage() {
        val msg = Messages.parse(
            """{"type":"power","power":{"source":"battery","charging":true,
               "percent":62.5,"voltage_v":3.9,"runtime_hours":41.2,"method":"gauge",
               "average_ma":3.5,"clean_due_in_hours":120.0}}""".trimIndent()
        )
        val power = msg as ServerMessage.PowerStatus
        assertTrue(power.onBattery)
        assertTrue(power.charging)
        assertEquals(62.5, power.percent!!, 0.0)
        assertEquals(3.5, power.averageMa!!, 0.0)
        assertEquals("gauge", power.method)
    }

    @Test fun mainsPowerHasNoBatteryFields() {
        val power = Messages.parse(
            """{"type":"power","power":{"source":"mains","undervoltage":true}}"""
        ) as ServerMessage.PowerStatus
        assertFalse(power.onBattery)
        assertNull(power.percent)
        assertTrue("the under-voltage flag is the useful signal here", power.undervoltage)
    }

    @Test fun helloCarriesProfilePowerAndSleepSupport() {
        val hello = Messages.parse(
            """{"type":"hello","product":"00080000","serial":"E316","firmware":"2.1",
               "state":"measuring","csv":"a.csv","index":"0000005",
               "profile":{"mode":"continuous","interval":1.0},
               "power":{"source":"mains"},"supports_sleep":true}""".trimIndent()
        ) as ServerMessage.Hello
        assertEquals(Wire.MODE_CONTINUOUS, hello.profile!!.mode)
        assertEquals("mains", hello.power!!.source)
        assertTrue(hello.supportsSleep)
    }

    @Test fun helloFromAnOlderDaemonStillParses() {
        // No profile/power/supports_sleep at all - must not blow up, and must not
        // hide low-power mode from a sensor that may well support it.
        val hello = Messages.parse(
            """{"type":"hello","product":"p","serial":"s","firmware":"2.1",
               "state":"measuring","csv":"a.csv","index":"0000001"}""".trimIndent()
        ) as ServerMessage.Hello
        assertNull(hello.profile)
        assertNull(hello.power)
        assertTrue(hello.supportsSleep)
    }

    @Test fun profileDefaultsFillInMissingFields() {
        val profile = Messages.parse("""{"type":"profile","profile":{}}""")
            as ServerMessage.AcquisitionProfile
        assertEquals(Wire.MODE_CONTINUOUS, profile.mode)
        assertEquals(1.0, profile.intervalSeconds, 0.0)
        assertEquals(900.0, profile.cycleSeconds, 0.0)
    }

    @Test fun setProfileCommandMatchesTheDaemonSchema() {
        val line = Commands.setProfile(Wire.MODE_DUTY_CYCLE, 1.0, 900.0, 30.0, 30.0)
        assertTrue(line.endsWith("\n"))
        assertTrue(line.contains(""""cmd":"set_profile""""))
        assertTrue(line.contains(""""mode":"duty_cycle""""))
        assertTrue(line.contains(""""cycle_s":900.0"""))
        assertTrue(line.contains(""""warmup_s":30.0"""))
    }

    @Test fun shutdownCommandIsASimpleFrame() {
        assertEquals("""{"cmd":"shutdown"}""" + "\n", Commands.shutdown())
    }
}

/**
 * Phase durations ride on the state message so the banner can count down
 * locally. Without them low-power mode shows a bare "sleeping" for fifteen
 * minutes, which is indistinguishable from being wedged.
 */
class PhaseCountdownTest {

    @Test fun stateCarriesAPhaseDuration() {
        val msg = Messages.parse(
            """{"type":"state","state":"sleeping","detail":"sleeping","seconds":840.0}"""
        ) as ServerMessage.StateMsg
        assertEquals(Wire.STATE_SLEEPING, msg.state)
        assertEquals(840.0, msg.phaseSeconds!!, 0.0)
    }

    @Test fun statesWithoutADurationStillParse() {
        val msg = Messages.parse(
            """{"type":"state","state":"measuring","detail":"sensor detected"}"""
        ) as ServerMessage.StateMsg
        assertNull("continuous mode has no phase to count down", msg.phaseSeconds)
    }

    @Test fun helloCarriesTheCurrentPhaseForAClientJoiningMidCycle() {
        val hello = Messages.parse(
            """{"type":"hello","product":"p","serial":"s","firmware":"2.1",
               "state":"sleeping","detail":"sleeping","seconds":612.5,
               "csv":"a.csv","index":"0000003"}""".trimIndent()
        ) as ServerMessage.Hello
        assertEquals(Wire.STATE_SLEEPING, hello.state)
        assertEquals(612.5, hello.phaseSeconds!!, 0.0)
    }
}
