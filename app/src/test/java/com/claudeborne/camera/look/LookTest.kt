package com.claudeborne.camera.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    @Test fun originalLeavesEveryColourAlone() {
        val original = Filters.all[0]
        for (c in listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0.2f, 0.5f, 0.9f))) {
            val g = original.grade(c[0], c[1], c[2])
            for (i in 0..2) assertEquals(c[i], g[i], 0.001f)
        }
    }

    @Test fun fourteenLooksWithOwnNamesPlusOriginal() {
        assertEquals(15, Filters.all.size)
        assertEquals(Filters.all.size, Filters.all.map { it.name }.toSet().size)
        val brands = listOf("portra", "800t", "tri-x", "ccd", "neon", "primrose")
        assertTrue(Filters.all.none { f -> brands.any { it in f.name.lowercase() } })
    }

    @Test fun monoFiltersGiveGreys() {
        for (f in Filters.all.filter { it.mono }) {
            val g = f.grade(0.8f, 0.3f, 0.1f)
            assertEquals(g[0], g[1], 0.001f)
            assertEquals(g[1], g[2], 0.001f)
        }
    }

    @Test fun gradesStayInRange() {
        for (f in Filters.all) for (v in listOf(0f, 0.25f, 0.5f, 1f)) {
            val g = f.grade(v, 1f - v, v)
            assertTrue(f.name, g.all { it in 0f..1f })
        }
    }

    @Test fun lutHasTheRightSizeAndIdentityForOriginal() {
        val lut = Filters.lut(0)
        assertEquals(16 * 16 * 16 * 4, lut.size)
        // blue 15, green 0, red 15 is pure magenta in the identity table
        val at = (0 * 256 + 15 * 16 + 15) * 4
        assertEquals(255, lut[at].toInt() and 0xFF)
        assertEquals(0, lut[at + 1].toInt() and 0xFF)
        assertEquals(255, lut[at + 2].toInt() and 0xFF)
        // alpha is opaque everywhere
        assertTrue((0 until lut.size / 4).all { lut[it * 4 + 3] == 255.toByte() })
    }

    @Test fun softLightPositions() {
        assertEquals(10, (0..Look.SOFT_MAX).count())
        assertEquals(0f, SoftLight.strength(0), 0f)
        assertEquals(1f, SoftLight.strength(9), 0f)
        assertEquals("Weak", SoftLight.label(3))
        assertEquals("Medium", SoftLight.label(6))
        assertEquals("Strong", SoftLight.label(9))
        assertEquals("None", SoftLight.label(0))
        assertEquals("4", SoftLight.label(4))
    }

    @Test fun sceneAddsToTheStyleAndStaysInRange() {
        val look = Look(tone = 8).withScene(Scene.all[2])
        assertEquals(10, look.tone)
        assertTrue(Scene.all[1].ev < 0 && Scene.all[3].ev < Scene.all[1].ev)
        assertEquals(Look.NONE, Look.NONE.withScene(Scene.all[0]))
    }

    @Test fun identityAndPresets() {
        assertTrue(Look.NONE.isIdentity)
        assertFalse(Look(soft = 1).isIdentity)
        val standard = StylePreset.all[0]
        assertEquals(0, standard.tone + standard.sat + standard.warm + standard.tint + standard.sharp + standard.soft)
        assertEquals(6, StylePreset.all.size)
    }

    @Test fun tiltStaysUsable() {
        val t = TiltRules.clamp(Tilt(2f, 0.9f, -30f))
        assertEquals(0.95f, t.centre, 0f)
        assertEquals(0.4f, t.half, 0f)
        assertEquals(150f, t.angle, 0.001f)
        assertEquals(0.04f, TiltRules.resize(Tilt(0.5f, 0.1f), 0.1f).half, 0.0001f)
    }
}
