package com.claudeborne.camera.camera

import kotlin.math.abs
import kotlin.math.atan2

/** The tilt of the phone around the viewing direction, for the level line. */
object LevelMath {
    /** The line counts as level within this many degrees. */
    const val LEVEL_DEGREES = 1f

    /**
     * Degrees the phone is turned away from the nearest upright or sideways position, from gravity as the accelerometer sees it
     * ([gx] to the right of the screen, [gy] up the screen). Positive is a turn clockwise as seen by the user; the range is -45..45.
     */
    fun roll(gx: Float, gy: Float): Float {
        val degrees = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat() // 0 when the phone stands upright
        var r = degrees % 90f
        if (r > 45f) r -= 90f
        if (r < -45f) r += 90f
        return r
    }

    fun isLevel(roll: Float) = abs(roll) <= LEVEL_DEGREES
}
