package com.claudeborne.camera.look

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Grid and guides of the Composition setting. */
enum class GridKind(val label: String) { NONE("None"), GRID("Grid"), DIAGONAL("Diagonal lines"), SPIRAL("Golden spiral");

    companion object {
        fun of(index: Int) = entries.getOrElse(index) { NONE }
    }
}

/** A line from (x1, y1) to (x2, y2). */
data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/** The lines of the composition guides, for a frame of [w] by [h]. */
object GridMath {
    /** The rule of thirds: two vertical and two horizontal lines. */
    fun thirds(w: Float, h: Float): List<Segment> = listOf(
        Segment(w / 3, 0f, w / 3, h), Segment(2 * w / 3, 0f, 2 * w / 3, h),
        Segment(0f, h / 3, w, h / 3), Segment(0f, 2 * h / 3, w, 2 * h / 3),
    )

    /** The two diagonals, and from the other two corners the lines that meet them at a right angle. */
    fun diagonals(w: Float, h: Float): List<Segment> {
        val main = listOf(Segment(0f, 0f, w, h), Segment(w, 0f, 0f, h))
        // foot of the perpendicular from a corner onto the diagonal that does not pass through it
        fun foot(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Pair<Float, Float> {
            val dx = bx - ax
            val dy = by - ay
            val t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
            return (ax + t * dx) to (ay + t * dy)
        }
        val corners = listOf(
            Triple(w to 0f, 0f to 0f, w to h), // top right onto the top-left to bottom-right diagonal
            Triple(0f to h, 0f to 0f, w to h),
            Triple(0f to 0f, w to 0f, 0f to h), // top left onto the other diagonal
            Triple(w to h, w to 0f, 0f to h),
        )
        return main + corners.map { (p, a, b) ->
            val f = foot(p.first, p.second, a.first, a.second, b.first, b.second)
            Segment(p.first, p.second, f.first, f.second)
        }
    }

    /**
     * A golden spiral as a polyline: quarter circles that shrink by the golden ratio, in a frame of [w] by [h] (turned a quarter for a
     * portrait frame, so the big curve is always in the long direction).
     */
    fun spiral(w: Float, h: Float, arcs: Int = 9, steps: Int = 14): List<Pair<Float, Float>> {
        if (w < h) return spiral(h, w, arcs, steps).map { (x, y) -> y to x }
        // the spiral only closes up inside a golden rectangle: fit the biggest one in the frame and centre it
        val gold = 1.618034f
        val gw = min(w, h * gold)
        val gh = gw / gold
        val left = (w - gw) / 2f
        val top = (h - gh) / 2f
        var x = left
        var y = top
        var rw = gw
        var rh = gh
        val out = ArrayList<Pair<Float, Float>>()
        for (i in 0 until arcs) {
            val s: Float
            val cx: Float
            val cy: Float
            val from: Float
            val to: Float
            when (i % 4) {
                0 -> { s = rh; cx = x + s; cy = y; from = 180f; to = 90f; x += s; rw -= s } // a square on the left
                1 -> { s = rw; cx = x; cy = y + rh - s; from = 90f; to = 0f; rh -= s } // at the bottom
                2 -> { s = rh; cx = x + rw - s; cy = y + rh; from = 0f; to = -90f; rw -= s } // on the right
                else -> { s = rw; cx = x + s; cy = y + s; from = -90f; to = -180f; y += s; rh -= s } // at the top
            }
            for (k in 0..steps) {
                val a = Math.toRadians((from + (to - from) * k / steps).toDouble())
                out += (cx + s * cos(a).toFloat()) to (cy + s * sin(a).toFloat())
            }
            if (min(rw, rh) < 2f) break
        }
        return out
    }
}
