package com.claudeborne.camera.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmRulesTest {
    /** A hand pointing up: wrist at the origin, the middle finger's base 100 px above it. [reach] is how far the fingertips go past their middle joints. */
    private fun hand(fingerTipY: Float, thumbTip: Pt): List<Pt> {
        val l = MutableList(21) { Pt(0f, -50f) }
        l[0] = Pt(0f, 0f)
        l[9] = Pt(0f, -100f)
        l[5] = Pt(-40f, -90f)
        l[4] = thumbTip
        listOf(6, 10, 14, 18).forEach { l[it] = Pt(0f, -150f) } // middle joints
        listOf(8, 12, 16, 20).forEach { l[it] = Pt(0f, fingerTipY) } // tips
        return l
    }

    @Test fun openHandIsAPalm() = assertTrue(PalmRules.isOpenPalm(hand(-220f, Pt(-110f, -50f))))

    @Test fun fistIsNot() = assertFalse(PalmRules.isOpenPalm(hand(-60f, Pt(-45f, -80f))))

    @Test fun thumbTuckedInIsNot() = assertFalse(PalmRules.isOpenPalm(hand(-220f, Pt(-42f, -88f))))

    @Test fun tinyOrIncompleteHandIsNot() {
        assertFalse(PalmRules.isOpenPalm(hand(-220f, Pt(-110f, -50f)).take(10)))
        assertFalse(PalmRules.isOpenPalm(hand(-220f, Pt(-110f, -50f)).map { Pt(it.x / 50f, it.y / 50f) }))
    }
}
