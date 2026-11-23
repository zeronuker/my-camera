package com.claudeborne.camera.dual

import kotlin.math.abs

/** The ways two or more cameras are laid out on one picture. */
enum class Layout(val label: String) { PIP("Picture in picture"), TOP_BOTTOM("Top and bottom"), SIDE_BY_SIDE("Side by side") }

/**
 * One camera's place on the composite. [source] is the camera's number (0 is the first stream the engine opened); the rectangle is in
 * fractions of the whole picture, y counted from the bottom (as GL does); [mirror] flips it (selfie); [border] draws a thin line round it.
 */
data class Tile(val source: Int, val x: Float, val y: Float, val w: Float, val h: Float, val mirror: Boolean = false, val border: Boolean = false)

/** The small picture of Picture-in-picture: its centre (fractions, y from the bottom) and its width as a fraction of the picture width. */
data class Pip(val cx: Float = 0.76f, val cy: Float = 0.78f, val w: Float = 0.34f) {
    companion object {
        const val MIN_W = 0.2f
        const val MAX_W = 0.6f
    }
}

object DualRules {
    /** The small picture is a 3:4 portrait; its height as a fraction of the picture height, for a picture of [outAspect] (width / height). */
    fun pipHeight(w: Float, outAspect: Float): Float = w * outAspect * 4f / 3f

    /** Keeps the small picture whole inside the frame and a usable size. */
    fun clamp(p: Pip, outAspect: Float): Pip {
        val w = p.w.coerceIn(Pip.MIN_W, Pip.MAX_W)
        val h = pipHeight(w, outAspect).coerceAtMost(0.9f)
        return Pip(p.cx.coerceIn(w / 2f, 1f - w / 2f), p.cy.coerceIn(h / 2f, 1f - h / 2f), w)
    }

    /**
     * The tiles of a two-camera layout. Source 0 is the rear camera and 1 the front; [swap] puts the front camera in the big place.
     * Top and bottom puts the first picture on top; side by side puts it on the left.
     */
    fun two(layout: Layout, swap: Boolean, pip: Pip, outAspect: Float, mirrorFront: Boolean): List<Tile> {
        val big = if (swap) 1 else 0
        val small = 1 - big
        fun mirror(s: Int) = s == 1 && mirrorFront
        return when (layout) {
            Layout.PIP -> {
                val p = clamp(pip, outAspect)
                val h = pipHeight(p.w, outAspect)
                listOf(Tile(big, 0f, 0f, 1f, 1f, mirror(big)), Tile(small, p.cx - p.w / 2f, p.cy - h / 2f, p.w, h, mirror(small), border = true))
            }
            Layout.TOP_BOTTOM -> listOf(Tile(big, 0f, 0.5f, 1f, 0.5f, mirror(big)), Tile(small, 0f, 0f, 1f, 0.5f, mirror(small)))
            Layout.SIDE_BY_SIDE -> listOf(Tile(big, 0f, 0f, 0.5f, 1f, mirror(big)), Tile(small, 0.5f, 0f, 0.5f, 1f, mirror(small)))
        }
    }

    /**
     * Quad view: four equal tiles, two by two (sources 0 and 1 on top, 2 and 3 below), or with [count] 3 two rear cameras on top and
     * the front camera under them across the whole width. The front camera is source [front].
     */
    fun quad(count: Int, front: Int, mirrorFront: Boolean): List<Tile> {
        fun cell(i: Int) = Tile(i, if (i % 2 == 0) 0f else 0.5f, if (i < 2) 0.5f else 0f, 0.5f, 0.5f, mirror = i == front && mirrorFront)
        return when (count) {
            4 -> (0 until 4).map(::cell)
            3 -> listOf(cell(0), cell(1), Tile(2, 0f, 0f, 1f, 0.5f, mirror = 2 == front && mirrorFront))
            else -> emptyList()
        }
    }

    /** The biggest preview size of at most [maxPixels], preferring [aspect] (width / height), then the most pixels. Sizes are (w, h). */
    fun chooseSize(sizes: List<Pair<Int, Int>>, maxPixels: Int, aspect: Float): Pair<Int, Int>? {
        val fit = sizes.filter { it.first.toLong() * it.second <= maxPixels }
        if (fit.isEmpty()) return null
        val close = fit.filter { abs(it.first.toFloat() / it.second - aspect) < 0.02f }
        return close.ifEmpty { fit }.maxByOrNull { it.first.toLong() * it.second }
    }

    /** What Quad view tries next when the phone refuses or the tiles run too slowly: from 3 rear cameras to 2 (plus the front). Null after the last. */
    fun stepDown(rearCameras: Int): Int? = if (rearCameras >= 3) 2 else null

    /** True when the slowest camera delivered under [minFps] over [seconds] (judged only after 3 s). */
    fun tooSlow(framesPerSource: List<Int>, seconds: Float, minFps: Float): Boolean {
        if (framesPerSource.isEmpty() || seconds < 3f) return false
        return framesPerSource.minOrNull()!! / seconds < minFps
    }
}
