package com.claudeborne.camera.proc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepRulesTest {
    @Test fun turnedIsAngularSpeedTimesTime() {
        assertEquals(57.29578f, SweepRules.turned(1f, 0f, 1f), 0.01f)
        assertEquals(28.6479f, SweepRules.turned(0f, 0.5f, 1f), 0.01f)
        assertEquals(0f, SweepRules.turned(0f, 0f, 2f), 0.0001f)
    }

    @Test fun nextPictureAfterTheStep() {
        assertFalse(SweepRules.takeNext(10f))
        assertTrue(SweepRules.takeNext(18f))
    }

    @Test fun fastSweepIsFlagged() {
        assertFalse(SweepRules.tooFast(0.4f, 0f)) // 23 deg/s
        assertTrue(SweepRules.tooFast(1f, 0f)) // 57 deg/s
    }
}
