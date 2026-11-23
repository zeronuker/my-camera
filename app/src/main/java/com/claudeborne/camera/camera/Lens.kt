package com.claudeborne.camera.camera

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** One lens button: the zoom ratio it jumps to, its text, and whether it is the active one. */
class LensPill(val stop: Float, val text: String, val selected: Boolean)

/** Rear zoom stops shown as lens pills (PLAN.md Phase 1). The camera's own zoom range is 0.67 to 20; 0.67 is the stock "0.6". */
object ZoomStops {
    val rear = floatArrayOf(0.67f, 1f, 2f, 3.5f, 7f)

    /** "0.6" for 0.67, "2" for 2, "2.4" for 2.46: cut, not rounded, so the button never claims more zoom than it has. */
    fun format(ratio: Float): String {
        val cut = floor(ratio * 10f + 1e-3f) / 10f
        return if (cut >= 1f && cut == cut.toInt().toFloat()) "${cut.toInt()}" else String.format(Locale.US, "%.1f", cut)
    }

    /** The active pill is the highest stop at or below [zoom]; it shows the live zoom with an "x" when the zoom sits between stops. */
    fun pills(zoom: Float): List<LensPill> {
        val active = rear.indices.lastOrNull { rear[it] <= zoom + 0.02f } ?: 0
        return rear.mapIndexed { i, stop ->
            val text = if (i != active) format(stop) else (if (abs(zoom - stop) < 0.05f) format(stop) else format(zoom)) + "x"
            LensPill(stop, text, i == active)
        }
    }
}

/** Preview size for the viewfinder: the biggest 4:3 size up to [maxWidth] wide (the sensor is 4:3); else the biggest size that fits. */
fun choosePreviewSize(sizes: List<Pair<Int, Int>>, maxWidth: Int = 1920): Pair<Int, Int>? {
    val fits = sizes.filter { it.first <= maxWidth }
    val fourThree = fits.filter { abs(it.first * 3 - it.second * 4) <= 4 }
    return (fourThree.ifEmpty { fits }.ifEmpty { sizes }).maxByOrNull { it.first.toLong() * it.second }
}
