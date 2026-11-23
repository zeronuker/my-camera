package com.claudeborne.camera.scan

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A point of the picture as fractions: 0,0 top left, 1,1 bottom right. */
data class Pt(val x: Float, val y: Float)

/** The four corners of a document, always in the order top left, top right, bottom right, bottom left. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    val points get() = listOf(tl, tr, br, bl)

    /** Share of the picture the quad covers (shoelace). */
    val area get() = abs(points.indices.sumOf { i -> val a = points[i]; val b = points[(i + 1) % 4]; (a.x * b.y - b.x * a.y).toDouble() }).toFloat() / 2f

    fun with(index: Int, p: Pt): Quad = when (index) { 0 -> copy(tl = p); 1 -> copy(tr = p); 2 -> copy(br = p); else -> copy(bl = p) }

    companion object {
        /** The whole picture: what a page gets when no document edge is found. */
        val FULL = Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))
    }
}

/** Pure maths of the document scanner (no OpenCV here, so it is unit tested). */
object DocRules {
    const val MIN_AREA = 0.15f // a document smaller than this share of the frame is not trusted

    /** Puts any four points in the order top left, top right, bottom right, bottom left. */
    fun order(pts: List<Pt>): Quad {
        require(pts.size == 4)
        val tl = pts.minBy { it.x + it.y }
        val br = pts.maxBy { it.x + it.y }
        val tr = pts.minBy { it.y - it.x }
        val bl = pts.maxBy { it.y - it.x }
        return Quad(tl, tr, br, bl)
    }

    /** Convex (no bow-tie, no dent) and big enough. */
    fun plausible(q: Quad, minArea: Float = MIN_AREA): Boolean {
        if (q.area < minArea) return false
        var sign = 0
        for (i in 0 until 4) {
            val a = q.points[i]; val b = q.points[(i + 1) % 4]; val c = q.points[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Width and height in pixels of the flattened page: the longer of each pair of opposite edges, scaled down so the long side is at most [maxEdge]. */
    fun outputSize(q: Quad, imageW: Int, imageH: Int, maxEdge: Int): Pair<Int, Int> {
        fun len(a: Pt, b: Pt) = hypot((a.x - b.x) * imageW, (a.y - b.y) * imageH)
        var w = maxOf(len(q.tl, q.tr), len(q.bl, q.br))
        var h = maxOf(len(q.tl, q.bl), len(q.tr, q.br))
        val k = maxEdge / maxOf(w, h, 1f)
        if (k < 1f) { w *= k; h *= k }
        return w.roundToInt().coerceAtLeast(16) to h.roundToInt().coerceAtLeast(16)
    }

    /** A moving corner stays inside the picture. */
    fun clamp(p: Pt) = Pt(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))

    /** Index of the corner nearest to [p] if it is within [reach] (a fraction of the picture), else -1. */
    fun nearest(q: Quad, p: Pt, reach: Float): Int {
        val d = q.points.map { hypot((it.x - p.x).toDouble(), (it.y - p.y).toDouble()) }
        val best = d.indices.minBy { d[it] }
        return if (d[best] <= reach) best else -1
    }

    /** PDF page size in points: 595 wide (A4), as tall as the picture needs. */
    fun pdfPage(w: Int, h: Int): Pair<Int, Int> = 595 to (595f * h / w.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)

    private fun stamp(timeMs: Long) = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timeMs))
    fun jpegName(timeMs: Long, page: Int) = "SCAN_${stamp(timeMs)}_${page}.jpg"
    fun pdfName(timeMs: Long) = "SCAN_${stamp(timeMs)}.pdf"
}
