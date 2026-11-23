package com.claudeborne.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelMathTest {
    @Test fun uprightIsZero() = assertEquals(0f, LevelMath.roll(0f, 9.8f), 0.01f)

    @Test fun sidewaysIsAlsoLevel() {
        assertEquals(0f, LevelMath.roll(9.8f, 0f), 0.01f)
        assertEquals(0f, LevelMath.roll(-9.8f, 0f), 0.01f)
        assertEquals(0f, LevelMath.roll(0f, -9.8f), 0.01f)
    }

    @Test fun smallTiltsKeepTheirSign() {
        assertEquals(10f, LevelMath.roll(1.7f, 9.65f), 0.2f)
        assertEquals(-10f, LevelMath.roll(-1.7f, 9.65f), 0.2f)
    }

    @Test fun rangeIsWithinFortyFive() {
        for (a in 0 until 360 step 7) {
            val r = LevelMath.roll(Math.sin(Math.toRadians(a.toDouble())).toFloat() * 9.8f, Math.cos(Math.toRadians(a.toDouble())).toFloat() * 9.8f)
            assertTrue("angle $a gave $r", r in -45.001f..45.001f)
        }
    }

    @Test fun levelWindow() {
        assertTrue(LevelMath.isLevel(0.9f))
        assertFalse(LevelMath.isLevel(2f))
    }

    @Test fun peakLevelOfSound() {
        val silent = ByteArray(8)
        assertEquals(0f, com.claudeborne.camera.video.VideoRules.peakLevel(silent, 8), 0f)
        // one loud sample (-16384, little endian 0x00 0xC0) among quiet ones
        val loud = byteArrayOf(0, 0, 0x00, 0xC0.toByte(), 1, 0, 0, 0)
        assertEquals(0.5f, com.claudeborne.camera.video.VideoRules.peakLevel(loud, 8), 0.001f)
    }

    @Test fun histogramCounts() {
        val black = ByteArray(16) { if (it % 4 == 3) 255.toByte() else 0 }
        val bins = com.claudeborne.camera.gl.Histogram.of(black)
        assertEquals(4, bins[0])
        val white = ByteArray(8) { 255.toByte() }
        assertEquals(2, com.claudeborne.camera.gl.Histogram.of(white)[63])
    }
}
