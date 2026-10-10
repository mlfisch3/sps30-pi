package com.sensirion.sps30.ui

import com.sensirion.sps30.wire.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disconnected banner makes a claim about hardware nobody can see, so its
 * wording is a correctness question rather than a copy question.
 */
class BannerTest {

    @Test fun recordingStatesSayTheSensorIsStillRecording() {
        for (state in listOf(Wire.STATE_MEASURING, Wire.STATE_WARMUP, Wire.STATE_SLEEPING)) {
            assertEquals("the sensor is still recording", lastKnownSensorActivity(state))
        }
    }

    @Test fun aShutDownOrStoppedSensorIsNeverDescribedAsRecording() {
        // The bug: every one of these used to render as "still recording".
        for (state in listOf(Wire.STATE_PAUSED, Wire.STATE_CLEANING, Wire.STATE_SHUTDOWN)) {
            assertFalse(
                "$state must not claim the sensor is recording",
                lastKnownSensorActivity(state).contains("recording"),
            )
        }
    }

    @Test fun anUnknownStateClaimsNothingAboutTheSensor() {
        // Never connected, so there is no last known state to report.
        assertFalse(lastKnownSensorActivity(null).contains("recording"))
        assertTrue(lastKnownSensorActivity(null).isNotEmpty())
        assertFalse(lastKnownSensorActivity("something_new").contains("recording"))
    }
}
