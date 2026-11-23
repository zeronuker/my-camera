package com.claudeborne.camera.gl

import kotlin.math.min

/**
 * Where on the camera picture each point of the screen reads from: flips it for the selfie camera and crops it to fill the
 * view (like the stock app). [sensorOrientation] only tells which way the 4:3 picture is turned, to get its upright shape for the crop.
 */
object PreviewMath {
    /** A screen point (0..1 across, 0..1 up) to the buffer point (0..1, 0..1 up) it shows. */
    fun map(
        u: Float, v: Float, sensorOrientation: Int, mirror: Boolean,
        bufW: Int, bufH: Int, viewW: Int, viewH: Int, turn: Int = 0,
    ): Pair<Float, Float> {
        if (turn % 360 != 0) return turned(u, v, sensorOrientation, mirror, bufW, bufH, viewW, viewH, turn)
        val turn = ((sensorOrientation % 360) + 360) % 360
        val quarter = turn == 90 || turn == 270
        val imageW = if (quarter) bufH else bufW // the picture's size once it is upright
        val imageH = if (quarter) bufW else bufH
        val imageAspect = imageW.toFloat() / imageH
        val viewAspect = viewW.toFloat() / viewH
        val showX = min(1f, viewAspect / imageAspect) // the share of the picture that fits across the view
        val showY = min(1f, imageAspect / viewAspect)
        var px = u - 0.5f
        val py = v - 0.5f
        if (mirror) px = -px
        // no turn here: Android already hands the SurfaceTexture upright (its transform matrix carries the sensor angle),
        // checked against the emulator's own camera app
        return px * showX + 0.5f to py * showY + 0.5f
    }

    /**
     * Video held sideways: the output is the upright picture turned [turn] degrees clockwise (a multiple of 90), so a point of the
     * output reads from the point of the upright view it came from. The upright view has the output's sides swapped for 90 and 270.
     */
    private fun turned(u: Float, v: Float, sensorOrientation: Int, mirror: Boolean, bufW: Int, bufH: Int, viewW: Int, viewH: Int, turn: Int): Pair<Float, Float> {
        val t = ((turn % 360) + 360) % 360
        val quarter = t == 90 || t == 270
        val srcW = if (quarter) viewH else viewW
        val srcH = if (quarter) viewW else viewH
        val px = (u - 0.5f) * viewW
        val py = (v - 0.5f) * viewH
        val c = when (t) { 90 -> 0f; 180 -> -1f; 270 -> 0f; else -> 1f }
        val s = when (t) { 90 -> 1f; 270 -> -1f; else -> 0f }
        val sx = px * c - py * s
        val sy = px * s + py * c
        return map(sx / srcW + 0.5f, sy / srcH + 0.5f, sensorOrientation, mirror, bufW, bufH, srcW, srcH)
    }

    /** [map] as a 3x3 matrix in the column order OpenGL expects, for the shader. */
    fun matrix(sensorOrientation: Int, mirror: Boolean, bufW: Int, bufH: Int, viewW: Int, viewH: Int, turn: Int = 0): FloatArray {
        fun at(u: Float, v: Float) = map(u, v, sensorOrientation, mirror, bufW, bufH, viewW, viewH, turn)
        val o = at(0f, 0f)
        val ux = at(1f, 0f)
        val uy = at(0f, 1f)
        return floatArrayOf(ux.first - o.first, ux.second - o.second, 0f, uy.first - o.first, uy.second - o.second, 0f, o.first, o.second, 1f)
    }
}
