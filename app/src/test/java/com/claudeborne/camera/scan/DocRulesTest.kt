package com.claudeborne.camera.scan

import org.junit.Assert.*
import org.junit.Test

class DocRulesTest {
    private val a = Pt(0.1f, 0.1f)
    private val b = Pt(0.9f, 0.15f)
    private val c = Pt(0.85f, 0.9f)
    private val d = Pt(0.05f, 0.8f)

    @Test fun orderIgnoresTheInputOrder() {
        val q = DocRules.order(listOf(c, a, d, b))
        assertEquals(a, q.tl); assertEquals(b, q.tr); assertEquals(c, q.br); assertEquals(d, q.bl)
    }

    @Test fun bigConvexQuadIsPlausible() {
        assertTrue(DocRules.plausible(Quad(a, b, c, d)))
        assertTrue(DocRules.plausible(Quad.FULL))
    }

    @Test fun smallOrBowTieQuadIsNot() {
        assertFalse(DocRules.plausible(Quad(Pt(0.4f, 0.4f), Pt(0.5f, 0.4f), Pt(0.5f, 0.5f), Pt(0.4f, 0.5f))))
        assertFalse(DocRules.plausible(Quad(a, c, b, d))) // twisted
        assertFalse(DocRules.plausible(Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(0.5f, 0.1f), Pt(0f, 1f)))) // a dent
    }

    @Test fun areaOfFullIsOne() = assertEquals(1f, Quad.FULL.area, 0.0001f)

    @Test fun outputSizeFollowsTheLongerEdges() {
        val (w, h) = DocRules.outputSize(Quad.FULL, 3000, 4000, 8000)
        assertEquals(3000, w); assertEquals(4000, h)
        val (w2, h2) = DocRules.outputSize(Quad.FULL, 3000, 4000, 2000)
        assertEquals(1500, w2); assertEquals(2000, h2)
    }

    @Test fun nearestCornerNeedsToBeCloseEnough() {
        val q = Quad(a, b, c, d)
        assertEquals(2, DocRules.nearest(q, Pt(0.83f, 0.88f), 0.1f))
        assertEquals(-1, DocRules.nearest(q, Pt(0.5f, 0.5f), 0.1f))
    }

    @Test fun movedCornerStaysInside() {
        assertEquals(Pt(1f, 0f), DocRules.clamp(Pt(1.4f, -0.2f)))
        assertEquals(Quad(Pt(0.2f, 0.2f), b, c, d), Quad(a, b, c, d).with(0, Pt(0.2f, 0.2f)))
    }

    @Test fun pdfPageIsA4WideAndKeepsTheShape() {
        assertEquals(595 to 842, DocRules.pdfPage(2100, 2970))
    }

    @Test fun namesAreStamped() {
        assertTrue(DocRules.jpegName(0L, 2).matches(Regex("""SCAN_\d{8}_\d{6}_2\.jpg""")))
        assertTrue(DocRules.pdfName(0L).endsWith(".pdf"))
    }
}
