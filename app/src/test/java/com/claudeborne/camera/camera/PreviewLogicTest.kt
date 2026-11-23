package com.claudeborne.camera.camera

import com.claudeborne.camera.gl.PreviewMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewLogicTest {
    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 0.001f)
        assertEquals(expected.second, actual.second, 0.001f)
    }

    @Test fun zoomLabelsAreCutNotRounded() {
        assertEquals("0.6", ZoomStops.format(0.67f))
        assertEquals("1", ZoomStops.format(1f))
        assertEquals("3.5", ZoomStops.format(3.5f))
        assertEquals("2.4", ZoomStops.format(2.46f))
    }

    @Test fun activePillFollowsZoom() {
        val stops = ZoomStops.stops(1f)
        val atOne = ZoomStops.pills(1f, stops)
        assertEquals(listOf("0.6", "1x", "2", "3.5", "7"), atOne.map { it.text })
        assertTrue(atOne[1].selected)
        val between = ZoomStops.pills(2.46f, stops)
        assertEquals("2.4x", between[2].text)
        assertTrue(between[2].selected)
        assertEquals("0.6x", ZoomStops.pills(0.67f, stops)[0].text)
    }

    @Test fun defaultWideChangesTheOneXButton() {
        val stops = ZoomStops.stops(ZoomStops.wideRatio(1)) // 28 mm
        assertEquals(28f / 24f, stops[1], 0.001f)
        assertEquals("1.1", ZoomStops.pills(2f, stops)[1].text) // labelled by where it jumps to
        val atMain = ZoomStops.pills(1f, stops) // 24 mm is still in the wide button's range
        assertTrue(atMain[1].selected)
        assertEquals("1x", atMain[1].text)
    }

    @Test fun tappingTheActiveButtonStepsThroughFocalLengths() {
        val wide = 1f
        // 24 -> 28 -> 35 -> 24
        assertEquals(28f / 24f, ZoomStops.next(1, 1f, wide, true, true, true)!!, 0.001f)
        assertEquals(35f / 24f, ZoomStops.next(1, 28f / 24f, wide, true, true, true)!!, 0.001f)
        assertEquals(1f, ZoomStops.next(1, 35f / 24f, wide, true, true, true)!!, 0.001f)
        assertEquals(35f / 24f, ZoomStops.next(1, 1f, wide, false, true, true)!!, 0.001f) // 28 mm switched off
        assertNull(ZoomStops.next(1, 1f, wide, false, false, true)) // nothing to step to
        assertEquals(243f / 24f, ZoomStops.next(4, 7f, wide, true, true, true)!!, 0.001f)
        assertNull(ZoomStops.next(4, 7f, wide, true, true, false))
        assertNull(ZoomStops.next(2, 2f, wide, true, true, true))
    }

    @Test fun previewSizePrefersBiggestFourThree() {
        val sizes = listOf(4096 to 3072, 1920 to 1440, 1920 to 1080, 1440 to 1080)
        assertEquals(1920 to 1440, choosePreviewSize(sizes))
        assertEquals(1920 to 1080, choosePreviewSize(listOf(1920 to 1080, 1280 to 720)))
        assertNull(choosePreviewSize(emptyList()))
    }

    // back camera, mounted at 90 degrees, 1920x1440 buffer, on a 1080x2400 screen
    @Test fun pictureCroppedToFillScreen() {
        fun back(u: Float, v: Float) = PreviewMath.map(u, v, 90, false, 1920, 1440, 1080, 2400)
        assertPoint(0.5f to 0.5f, back(0.5f, 0.5f))
        assertPoint(0.5f to 1f, back(0.5f, 1f)) // the picture is already upright: the top stays the top
        assertPoint(0.8f to 0.5f, back(1f, 0.5f)) // sides are cropped: only 60% of the width fits
    }

    @Test fun selfiePreviewIsMirrored() {
        val plain = PreviewMath.map(0f, 0.5f, 270, false, 1920, 1440, 1080, 2400)
        val mirrored = PreviewMath.map(1f, 0.5f, 270, true, 1920, 1440, 1080, 2400)
        assertPoint(plain, mirrored)
    }

    @Test fun matrixAgreesWithMap() {
        val m = PreviewMath.matrix(90, false, 1920, 1440, 1080, 2400)
        val (u, v) = 0.3f to 0.8f
        val expected = PreviewMath.map(u, v, 90, false, 1920, 1440, 1080, 2400)
        assertPoint(expected, (m[0] * u + m[3] * v + m[6]) to (m[1] * u + m[4] * v + m[7]))
    }
}
