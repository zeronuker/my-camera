package com.claudeborne.camera.capture

import kotlin.math.min
import kotlin.math.roundToInt

/** The viewfinder and photo shape. [ratio] is width over height of the viewfinder in portrait (0 = the whole screen). */
enum class Aspect(val label: String, val ratio: Float) {
    R4_3("4:3", 3f / 4f),
    R16_9("16:9", 9f / 16f),
    R1_1("1:1", 1f),
    FULL("Full", 0f),
}

object AspectMath {
    /** Height of the viewfinder box for a screen [width] wide and [fullHeight] tall. */
    fun boxHeight(aspect: Aspect, width: Int, fullHeight: Int): Int =
        if (aspect == Aspect.FULL) fullHeight else min(fullHeight, (width / aspect.ratio).roundToInt())

    /**
     * The long side over the short side to look for among the camera's photo sizes. 1:1 shoots 4:3 and is cropped square
     * afterwards: the camera lists no big square size.
     */
    fun captureAspect(aspect: Aspect, screenLongOverShort: Float): Float = when (aspect) {
        Aspect.R4_3, Aspect.R1_1 -> 4f / 3f
        Aspect.R16_9 -> 16f / 9f
        Aspect.FULL -> screenLongOverShort
    }

    /** The centred square of a [width] x [height] picture as (left, top, side). */
    fun squareCrop(width: Int, height: Int): Triple<Int, Int, Int> {
        val side = min(width, height)
        return Triple((width - side) / 2, (height - side) / 2, side)
    }
}
