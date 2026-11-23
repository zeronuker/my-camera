package com.claudeborne.camera.proc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitRulesTest {
    @Test fun widerApertureBlursMore() {
        assertTrue(PortraitRules.sigma(0, 2000) > PortraitRules.sigma(1, 2000))
        assertTrue(PortraitRules.sigma(2, 2000) > PortraitRules.sigma(3, 2000))
        assertEquals(32f, PortraitRules.sigma(0, 2000), 0.01f)
    }

    @Test fun labelsReadLikeFNumbers() {
        assertEquals("f/1.4", PortraitRules.label(0))
        assertEquals("f/2.0", PortraitRules.label(1))
        assertEquals("f/2.8", PortraitRules.label(2))
        assertEquals("f/4.0", PortraitRules.label(3))
        assertEquals("f/4.0", PortraitRules.label(9)) // out of range stays inside the list
    }

    @Test fun bigPersonMeansMoveBack() {
        assertTrue(PortraitRules.tooClose(0.7f))
        assertFalse(PortraitRules.tooClose(0.3f))
    }
}
