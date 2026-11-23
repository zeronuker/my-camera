package com.claudeborne.camera.scan

import org.junit.Assert.*
import org.junit.Test

class ScanRulesTest {
    @Test fun linksCanBeOpened() {
        assertTrue(QrRules.canOpen("https://example.com/a"))
        assertTrue(QrRules.canOpen(" HTTP://example.com"))
        assertTrue(QrRules.canOpen("tel:+123"))
        assertEquals("LINK", QrRules.kind("https://example.com"))
        assertEquals("EMAIL", QrRules.kind("mailto:a@b.c"))
    }

    @Test fun plainTextAndWifiAreNotOpened() {
        assertFalse(QrRules.canOpen("hello world"))
        assertFalse(QrRules.canOpen("WIFI:S:home;T:WPA;P:secret;;"))
        assertFalse(QrRules.canOpen("javascript:alert(1)"))
        assertFalse(QrRules.canOpen("intent://x#Intent;end"))
        assertEquals("TEXT", QrRules.kind("a: b"))
        assertEquals("WI-FI", QrRules.kind("WIFI:S:home;T:WPA;P:secret;;"))
    }

    @Test fun wifiPasswordIsNeverShown() {
        val shown = QrRules.shown("WIFI:S:my\\;net;T:WPA;P:secret;;")
        assertEquals("Network: my;net", shown)
        assertFalse(shown.contains("secret"))
    }

    @Test fun longTextIsCut() {
        assertEquals(70, QrRules.shown("x".repeat(200)).length)
        assertEquals("short", QrRules.shown(" short "))
    }

    @Test fun textIsRecommendedOnlyWhenItFillsTheFrame() {
        val page = List(10) { Frac(0.1f, 0.1f + it * 0.08f, 0.9f, 0.16f + it * 0.08f) } // 10 lines, 0.0480 each
        assertTrue(TextRules.recommend(page, 300))
        assertFalse(TextRules.recommend(page, 5))
        assertFalse(TextRules.recommend(listOf(Frac(0.4f, 0.4f, 0.6f, 0.42f)), 30)) // a small sign
    }

    @Test fun outlineWrapsAllTextAndStaysInside() {
        val o = TextRules.outline(listOf(Frac(0.1f, 0.2f, 0.5f, 0.3f), Frac(0.2f, 0.5f, 0.99f, 0.6f)), 40)!!
        assertEquals(0.075f, o.left, 0.001f)
        assertEquals(0.175f, o.top, 0.001f)
        assertEquals(1f, o.right, 0.001f)
        assertEquals(0.625f, o.bottom, 0.001f)
        assertNull(TextRules.outline(emptyList(), 40))
        assertNull(TextRules.outline(listOf(Frac(0f, 0f, 0.1f, 0.1f)), 3))
    }

    @Test fun chineseWinsOnlyWithChineseCharacters() {
        assertEquals("hello", TextRules.pick("hello", "hell0"))
        assertEquals("你好 hello", TextRules.pick("hello", "你好 hello"))
        assertEquals("only chinese result", TextRules.pick("", "only chinese result"))
    }
}
