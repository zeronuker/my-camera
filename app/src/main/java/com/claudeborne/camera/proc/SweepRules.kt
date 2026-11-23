package com.claudeborne.camera.proc

import kotlin.math.hypot

/** The maths of the panorama sweep: how far the phone has turned (from the gyroscope) and when to take the next picture. Pure, unit tested. */
object SweepRules {
    const val STEP_DEG = 18f // about a third of the narrow field of view: enough overlap for the stitcher
    const val MAX_FRAMES = 14
    const val MAX_SPEED_DEG_S = 45f // faster than this the pictures blur

    /** Degrees turned in [dtSec] at the angular speeds [wx] and [wy] (rad/s around the phone's two screen axes; turning in place around the third axis is not a sweep). */
    fun turned(wx: Float, wy: Float, dtSec: Float): Float = hypot(wx, wy) * dtSec * 57.29578f

    fun speedDegPerSec(wx: Float, wy: Float): Float = hypot(wx, wy) * 57.29578f

    fun takeNext(sinceLast: Float) = sinceLast >= STEP_DEG

    fun tooFast(wx: Float, wy: Float) = speedDegPerSec(wx, wy) > MAX_SPEED_DEG_S
}
