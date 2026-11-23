package com.claudeborne.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class HeatTest {
    @Test fun thermalStatusToHeat() {
        assertEquals(Heat.OK, Heat.of(0)) // none
        assertEquals(Heat.OK, Heat.of(2)) // moderate: only a warning for the system, nothing for photos
        assertEquals(Heat.HOT, Heat.of(3)) // severe
        assertEquals(Heat.TOO_HOT, Heat.of(4)) // critical
        assertEquals(Heat.TOO_HOT, Heat.of(6)) // shutdown
    }
}
