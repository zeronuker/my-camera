package com.claudeborne.camera.camera

import kotlin.math.max
import kotlin.math.roundToInt

/** Where to focus and measure light for a tap, in the camera sensor's own coordinates. */
object Metering {
    /**
     * @param x the tapped point in the upright picture, 0..1 from the left
     * @param yUp the same point 0..1 from the bottom
     * @param zoom the current zoom ratio: the camera only looks at the middle 1/zoom of the sensor
     * @return left, top, width, height in the sensor's active array
     */
    fun region(x: Float, yUp: Float, sensorOrientation: Int, activeWidth: Int, activeHeight: Int, zoom: Float, size: Float = 0.15f): IntArray {
        val fromTop = 1f - yUp
        // the upright picture is the sensor picture turned clockwise by the sensor angle; this turns the point back
        val (sx, sy) = when (((sensorOrientation % 360) + 360) % 360) {
            90 -> fromTop to 1f - x
            180 -> 1f - x to 1f - fromTop
            270 -> 1f - fromTop to x
            else -> x to fromTop
        }
        val seenW = activeWidth / zoom
        val seenH = activeHeight / zoom
        val centreX = (activeWidth - seenW) / 2f + sx * seenW
        val centreY = (activeHeight - seenH) / 2f + sy * seenH
        val side = minOf((max(seenW, seenH) * size).roundToInt(), activeWidth, activeHeight)
        val left = (centreX - side / 2f).roundToInt().coerceIn(0, activeWidth - side)
        val top = (centreY - side / 2f).roundToInt().coerceIn(0, activeHeight - side)
        return intArrayOf(left, top, side, side)
    }
}
