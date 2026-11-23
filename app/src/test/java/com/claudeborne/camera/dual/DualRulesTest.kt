package com.claudeborne.camera.dual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualRulesTest {
    private val aspect = 0.45f

    @Test fun pipKeepsInsideTheFrame() {
        val p = DualRules.clamp(Pip(cx = 2f, cy = -1f, w = 5f), aspect)
        assertEquals(Pip.MAX_W, p.w, 1e-6f)
        val h = DualRules.pipHeight(p.w, aspect)
        assertTrue(p.cx + p.w / 2f <= 1.0001f)
        assertTrue(p.cy - h / 2f >= -0.0001f)
        assertEquals(Pip.MIN_W, DualRules.clamp(Pip(w = 0.01f), aspect).w, 1e-6f)
    }

    @Test fun pipTileIsPortraitThreeByFour() {
        val t = DualRules.two(Layout.PIP, false, Pip(), aspect, true)
        assertEquals(2, t.size)
        assertEquals(1f, t[0].w, 0f)
        val small = t[1]
        assertTrue(small.border)
        // on a 1080 x 2400 picture (aspect 0.45) the small tile is 3:4
        assertEquals(3f / 4f, (small.w * 1080f) / (small.h * 2400f), 0.01f)
    }

    @Test fun swapPutsTheFrontInTheBigPlace() {
        val a = DualRules.two(Layout.PIP, false, Pip(), aspect, true)
        val b = DualRules.two(Layout.PIP, true, Pip(), aspect, true)
        assertEquals(0, a[0].source); assertEquals(1, a[1].source)
        assertEquals(1, b[0].source); assertEquals(0, b[1].source)
        assertTrue(b[0].mirror && !b[1].mirror) // only the front camera is mirrored
        assertFalse(DualRules.two(Layout.PIP, true, Pip(), aspect, false)[0].mirror)
    }

    @Test fun splitLayoutsFillTheWholePicture() {
        for (layout in listOf(Layout.TOP_BOTTOM, Layout.SIDE_BY_SIDE)) {
            val t = DualRules.two(layout, false, Pip(), aspect, false)
            assertEquals(1f, t.sumOf { (it.w * it.h).toDouble() }.toFloat(), 1e-5f)
            assertFalse(t.any { it.border })
        }
        assertEquals(0.5f, DualRules.two(Layout.TOP_BOTTOM, false, Pip(), aspect, false)[0].y, 0f) // rear on top
        assertEquals(0f, DualRules.two(Layout.SIDE_BY_SIDE, false, Pip(), aspect, false)[0].x, 0f) // rear on the left
    }

    @Test fun quadTilesCoverThePicture() {
        for (n in listOf(3, 4)) {
            val t = DualRules.quad(n, front = n - 1, mirrorFront = true)
            assertEquals(n, t.size)
            assertEquals(1f, t.sumOf { (it.w * it.h).toDouble() }.toFloat(), 1e-5f)
            assertTrue(t.last().mirror)
            assertEquals(n, t.map { it.source }.distinct().size)
        }
        assertTrue(DualRules.quad(2, 1, false).isEmpty())
    }

    @Test fun sizeChoice() {
        val sizes = listOf(1920 to 1080, 1440 to 1080, 1280 to 960, 4096 to 3072, 640 to 480)
        assertEquals(1440 to 1080, DualRules.chooseSize(sizes, 1920 * 1080, 4f / 3f))
        assertEquals(1280 to 960, DualRules.chooseSize(sizes, 1300 * 960, 4f / 3f))
        assertEquals(1920 to 1080, DualRules.chooseSize(listOf(1920 to 1080, 1280 to 720), 1920 * 1080, 4f / 3f)) // no 4:3: biggest that fits
        assertNull(DualRules.chooseSize(sizes, 100, 1f))
    }

    @Test fun ladderAndSpeedCheck() {
        assertEquals(2, DualRules.stepDown(3))
        assertNull(DualRules.stepDown(2))
        assertFalse(DualRules.tooSlow(listOf(10, 10), 2f, 24f)) // too early to judge
        assertTrue(DualRules.tooSlow(listOf(120, 60), 4f, 24f)) // 15 fps in the slowest tile
        assertFalse(DualRules.tooSlow(listOf(120, 110), 4f, 24f))
        assertFalse(DualRules.tooSlow(emptyList(), 10f, 24f))
    }
}
