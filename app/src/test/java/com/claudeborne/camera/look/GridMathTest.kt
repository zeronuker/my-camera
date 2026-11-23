package com.claudeborne.camera.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class GridMathTest {
    @Test fun thirdsHaveFourLines() {
        val g = GridMath.thirds(900f, 1200f)
        assertEquals(4, g.size)
        assertEquals(300f, g[0].x1, 0.001f)
        assertEquals(800f, g[3].y1, 0.001f)
    }

    @Test fun diagonalsMeetTheirPerpendicularsAtARightAngle() {
        val g = GridMath.diagonals(1000f, 600f)
        assertEquals(6, g.size)
        val main = g[0]
        val dx = main.x2 - main.x1
        val dy = main.y2 - main.y1
        val topRight = g[2] // from the top right corner to the first diagonal
        val dot = (topRight.x2 - topRight.x1) * dx + (topRight.y2 - topRight.y1) * dy
        assertEquals(0f, dot, 1f)
        assertTrue(g.all { it.x1 in 0f..1000f && it.x2 in 0f..1000f && it.y1 in 0f..600f && it.y2 in 0f..600f })
    }

    @Test fun spiralStaysInsideAndIsOneUnbrokenLine() {
        for ((w, h) in listOf(1618f to 1000f, 1200f to 900f, 900f to 1200f, 1000f to 1000f)) {
            val s = GridMath.spiral(w, h)
            assertTrue(s.size > 20)
            assertTrue("$w x $h", s.all { (x, y) -> x in -0.5f..w + 0.5f && y in -0.5f..h + 0.5f })
            // no jump between neighbouring points larger than one arc step of the biggest circle
            val biggest = minOf(w, h)
            for (i in 1 until s.size) assertTrue(hypot(s[i].first - s[i - 1].first, s[i].second - s[i - 1].second) < biggest * 0.2f)
        }
    }

    @Test fun spiralStartsAtACornerEdge() {
        val s = GridMath.spiral(1618f, 1000f)
        assertEquals(0f, s.first().first, 0.5f)
        assertEquals(0f, s.first().second, 0.5f)
        // a frame wider than golden gets the spiral in the middle
        val wide = GridMath.spiral(2200f, 1000f)
        assertEquals((2200f - 1618f) / 2f, wide.first().first, 1f)
        assertTrue(abs(s.first().first - s.last().first) > 1f)
    }

    @Test fun gridKinds() {
        assertEquals(GridKind.NONE, GridKind.of(7))
        assertEquals(GridKind.SPIRAL, GridKind.of(3))
    }
}
