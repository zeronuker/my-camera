package com.claudeborne.camera.capture

import com.claudeborne.camera.camera.Metering
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlsLogicTest {
    @Test fun boxHeightFollowsAspect() {
        assertEquals(1440, AspectMath.boxHeight(Aspect.R4_3, 1080, 2400))
        assertEquals(1920, AspectMath.boxHeight(Aspect.R16_9, 1080, 2400))
        assertEquals(1080, AspectMath.boxHeight(Aspect.R1_1, 1080, 2400))
        assertEquals(2400, AspectMath.boxHeight(Aspect.FULL, 1080, 2400))
        assertEquals(1000, AspectMath.boxHeight(Aspect.R4_3, 1080, 1000)) // never taller than the screen
    }

    @Test fun captureShape() {
        assertEquals(4f / 3f, AspectMath.captureAspect(Aspect.R1_1, 2.2f), 0.001f)
        assertEquals(16f / 9f, AspectMath.captureAspect(Aspect.R16_9, 2.2f), 0.001f)
        assertEquals(2.2f, AspectMath.captureAspect(Aspect.FULL, 2.2f), 0.001f)
        assertEquals(Triple(512, 0, 3072), AspectMath.squareCrop(4096, 3072))
    }

    @Test fun tapAtCentreMetersTheCentre() {
        assertArrayEquals(intArrayOf(1700, 1200, 600, 600), Metering.region(0.5f, 0.5f, 90, 4000, 3000, 1f, 0.15f))
    }

    @Test fun tapAtTopOfUprightPictureIsTheSensorsLeftEdge() {
        // sensor mounted at 90 degrees: the upright top is the sensor's left side
        val r = Metering.region(0.5f, 1f, 90, 4000, 3000, 1f)
        assertEquals(0, r[0])
        assertEquals(1200, r[1])
    }

    @Test fun zoomShrinksTheArea() {
        // at 2x the camera sees the middle half; a tap at the right edge lands at the right edge of that middle part
        val r = Metering.region(1f, 0.5f, 0, 4000, 3000, 2f, 0.15f)
        assertEquals(3000, r[0] + r[2] / 2)
        assertEquals(1500, r[1] + r[2] / 2)
    }

    @Test fun regionStaysInsideTheSensor() {
        val r = Metering.region(0f, 0f, 0, 4000, 3000, 1f)
        assertEquals(0, r[0])
        assertEquals(3000 - r[3], r[1])
    }

    @Test fun evLabelsAndSteps() {
        assertEquals("0.0", Ev.label(0f))
        assertEquals("+0.7", Ev.label(0.67f))
        assertEquals("-1.0", Ev.label(-1f))
        assertEquals(3, Ev.toIndex(1f, 1f / 3f, -12, 12))
        assertEquals(-12, Ev.toIndex(-9f, 1f / 3f, -12, 12))
        assertEquals(0, Ev.toIndex(1f, 0f, -12, 12))
    }
}
