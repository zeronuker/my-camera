package com.claudeborne.camera.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceOpsTest {
    /** A symmetric made-up face in a 1000 x 1000 image: top (500,300), chin (500,700), eyes at y 440, edges at x 330 and 670. */
    private fun mesh(dx: Float = 0f, dy: Float = 0f): Map<Int, Pair<Float, Float>> {
        val m = HashMap<Int, Pair<Float, Float>>()
        fun put(i: Int, x: Float, y: Float) { m[i] = (x + dx) to (y + dy) }
        put(10, 500f, 300f); put(152, 500f, 700f)
        put(33, 400f, 440f); put(133, 440f, 440f); put(159, 420f, 430f); put(145, 420f, 450f)
        put(362, 560f, 440f); put(263, 600f, 440f); put(386, 580f, 430f); put(374, 580f, 450f)
        put(234, 330f, 480f); put(454, 670f, 480f)
        put(172, 380f, 640f); put(397, 620f, 640f)
        put(132, 350f, 560f); put(361, 650f, 560f)
        put(116, 380f, 500f); put(345, 620f, 500f)
        put(21, 360f, 380f); put(251, 640f, 380f)
        put(129, 470f, 540f); put(358, 530f, 540f)
        return m
    }

    private fun face(dx: Float = 0f, dy: Float = 0f) = Face.fromPixels(mesh(dx, dy), 1000, 1000)!!

    @Test fun faceGeometry() {
        val f = face()
        assertEquals(0.4f, f.height, 1e-4f)
        assertEquals(0.5f, f.centre.x, 1e-4f)
        assertEquals(0.5f, f.centre.y, 1e-4f)
        assertEquals(1f, f.up.y, 1e-4f) // the top of the head is up (y grows upwards in frame units)
    }

    @Test fun missingPointGivesNoFace() {
        val m = mesh().toMutableMap().apply { remove(358) }
        assertNull(Face.fromPixels(m, 1000, 1000))
    }

    @Test fun tinyFaceIsDropped() {
        val tiny = mesh().mapValues { (_, v) -> 500f + (v.first - 500f) * 0.02f to 500f + (v.second - 500f) * 0.02f }
        assertNull(Face.fromPixels(tiny, 1000, 1000))
    }

    @Test fun nothingOnMeansNothingPacked() {
        assertEquals(0, RetouchOps.pack(listOf(face()), Retouch.NONE, 1f).count)
        assertEquals(0, RetouchOps.pack(emptyList(), Retouch.NATURAL, 1f).count)
    }

    @Test fun skinSlidersAddNoWarpButAnOval() {
        val p = RetouchOps.pack(listOf(face()), Retouch(texture = 50, tone = 20), 1f)
        assertEquals(0, p.count)
        assertEquals(1, p.faces)
        assertEquals(0.5f, p.smooth, 1e-4f)
        assertEquals(0.2f, p.tone, 1e-4f)
    }

    @Test fun faceWidthPushesBothSidesInward() {
        val p = RetouchOps.pack(listOf(face()), Retouch(width = 100), 1f)
        assertEquals(4, p.count) // two cheekbone-level edges and two jaw corners
        var left = 0
        var right = 0
        for (i in 0 until p.count) {
            val cx = p.opA[i * 4]
            val vx = p.opB[i * 4]
            if (cx < 0.5f) { assertTrue("left side moves right", vx > 0f); left++ } else { assertTrue("right side moves left", vx < 0f); right++ }
        }
        assertEquals(2, left); assertEquals(2, right)
    }

    @Test fun eyesGrowAndNoseNarrows() {
        val e = RetouchOps.pack(listOf(face()), Retouch(eyes = 100), 1f)
        assertEquals(2, e.count)
        assertTrue(e.opB[2] > 0f && e.opB[3] > 0f) // content magnified: source nearer the centre
        val n = RetouchOps.pack(listOf(face()), Retouch(nose = 100), 1f)
        assertEquals(1, n.count)
        assertTrue(n.opB[2] < 0f && n.opB[3] == 0f) // narrower across, same height
    }

    @Test fun hairlineGoesDownAndChinGoesUp() {
        val h = RetouchOps.pack(listOf(face()), Retouch(hairline = 100), 1f)
        assertTrue(h.opB[1] < 0f)
        val l = RetouchOps.pack(listOf(face()), Retouch(length = 100), 1f)
        assertTrue(l.opB[1] > 0f)
    }

    @Test fun allSlidersFitTwoFaces() {
        val all = Retouch(100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, correct = 55f)
        val p = RetouchOps.pack(listOf(face(), face(0.2f * 1000f, 0f)), all, 1f)
        assertTrue(p.count <= Packed.MAX_OPS)
        assertEquals(2, p.faces)
    }

    @Test fun strongerSliderMovesMore() {
        val a = RetouchOps.pack(listOf(face()), Retouch(cheeks = 20), 1f).opB[0]
        val b = RetouchOps.pack(listOf(face()), Retouch(cheeks = 80), 1f).opB[0]
        assertTrue(kotlin.math.abs(b) > kotlin.math.abs(a) * 3.9f)
    }

    @Test fun distortionNeedsFaceAwayFromMiddle() {
        val centre = V(0.5f, 0.5f)
        val half = centre.length()
        assertNull(RetouchOps.distortion(face(), centre, half, 55f)) // face in the middle of a 1:1 frame
        val edge = Face(face().pts.mapValues { (_, v) -> V(v.x + 0.35f, v.y) }, 1f)
        val s = RetouchOps.distortion(edge, centre, half, 55f)
        assertNotNull(s)
        assertTrue(s!!.s < 0f) // squeezed along the line from the middle
        val nearer = Face(face().pts.mapValues { (_, v) -> V(v.x + 0.2f, v.y) }, 1f)
        assertTrue(RetouchOps.distortion(nearer, centre, half, 55f)!!.s > s.s) // less squeeze closer in
    }

    @Test fun edgeAngleFollowsZoom() {
        assertEquals(0f, RetouchOps.edgeAngle(1f), 1e-4f)
        assertEquals(0f, RetouchOps.edgeAngle(2f), 1e-4f)
        assertEquals(55f, RetouchOps.edgeAngle(0.67f), 0.1f)
        assertEquals(55f, RetouchOps.edgeAngle(0.5f), 1e-4f)
    }

    @Test fun falloffIsSmoothAndEndsAtTheEdge() {
        assertEquals(1f, RetouchOps.falloff(0f), 1e-6f)
        assertEquals(0f, RetouchOps.falloff(1f), 1e-6f)
        assertEquals(0f, RetouchOps.falloff(1.5f), 1e-6f)
        assertTrue(RetouchOps.falloff(0.3f) > RetouchOps.falloff(0.6f))
    }

    @Test fun storeRoundTripsAndSurvivesGarbage() {
        val r = Retouch(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 5)
        assertEquals(r, RetouchStore.decode(RetouchStore.encode(r)))
        assertEquals(Retouch.NONE, RetouchStore.decode(""))
        assertEquals(Retouch.NONE, RetouchStore.decode("1,2,x"))
        assertEquals(Retouch.NONE, RetouchStore.decode("1,2,3"))
    }

    @Test fun slidersClampAndPresetsAreRecognised() {
        assertEquals(100, RetouchSlider.EYES.get(RetouchSlider.EYES.set(Retouch.NONE, 500)))
        assertEquals(0, RetouchSlider.NOSE.get(RetouchSlider.NOSE.set(Retouch(nose = 5), -3)))
        assertEquals(RetouchPreset.NATURAL, RetouchPreset.of(Retouch.NATURAL))
        assertEquals(RetouchPreset.ORIGINAL, RetouchPreset.of(Retouch.NONE.copy(correct = 30f)))
        assertNull(RetouchPreset.of(Retouch.NATURAL.copy(eyes = 99)))
    }

    @Test fun trackSteadiesJitterAndLetsGoOfLostFaces() {
        val t = FaceTrack(keep = 2, follow = 0.5f)
        val a = t.update(listOf(face()))
        val b = t.update(listOf(face(dx = 10f))) // 0.01 of the frame to the right
        assertEquals(a[0].centre.x + 0.005f, b[0].centre.x, 1e-4f) // only half way
        assertEquals(1, t.update(emptyList()).size) // one miss: kept
        assertEquals(1, t.update(emptyList()).size)
        assertEquals(0, t.update(emptyList()).size) // third miss: gone
        val far = t.update(listOf(face(dx = 300f)))
        assertEquals(0.8f, far[0].centre.x, 1e-4f) // a new face starts at once
    }
}
