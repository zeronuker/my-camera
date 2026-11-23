package com.claudeborne.camera.camera

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** One lens button: where it sits in the row, the zoom ratio it jumps to, its text, and whether it is the active one. */
class LensPill(val index: Int, val stop: Float, val text: String, val selected: Boolean)

/**
 * Rear zoom buttons (PLAN.md Phase 1). The camera's own zoom range is 0.67 to 20; 0.67 is the stock "0.6". Zoom 1.0 is the
 * 24 mm main lens, so 28 mm is 28/24 and 243 mm is 243/24. Extra focal lengths are reached like in the stock app: tap the
 * active button again and it steps to the next one.
 */
object ZoomStops {
    private const val MM28 = 28f / 24f
    private const val MM35 = 35f / 24f
    private const val MM243 = 243f / 24f
    private const val WIDE = 1
    private const val TELE = 4

    /** The "1x" button's zoom for the Default wide-angle setting (0 = 24 mm, 1 = 28 mm, 2 = 35 mm). */
    fun wideRatio(choice: Int) = when (choice) { 1 -> MM28; 2 -> MM35; else -> 1f }

    fun stops(wide: Float) = floatArrayOf(0.67f, wide, 2f, 3.5f, 7f)

    /** "0.6" for 0.67, "2" for 2, "2.4" for 2.46: cut, not rounded, so the button never claims more zoom than it has. */
    fun format(ratio: Float): String {
        val cut = floor(ratio * 10f + 1e-3f) / 10f
        return if (cut >= 1f && cut == cut.toInt().toFloat()) "${cut.toInt()}" else String.format(Locale.US, "%.1f", cut)
    }

    /**
     * The active button is the highest one at or below [zoom] (the wide button starts at 1.0 whatever its jump target is, so 24 mm
     * still counts as it); it shows the live zoom with an "x" when the zoom sits between stops.
     */
    fun pills(zoom: Float, stops: FloatArray): List<LensPill> {
        val from = stops.copyOf().also { it[WIDE] = minOf(it[WIDE], 1f) }
        val active = from.indices.lastOrNull { from[it] <= zoom + 0.02f } ?: 0
        return stops.mapIndexed { i, stop ->
            val text = if (i != active) format(stop) else (if (abs(zoom - stop) < 0.05f) format(stop) else format(zoom)) + "x"
            LensPill(i, stop, text, i == active)
        }
    }

    /** Where tapping the already active button [pill] goes: the next focal length of its row, or null when it has only one. */
    fun next(pill: Int, zoom: Float, wide: Float, extra28: Boolean, extra35: Boolean, extra243: Boolean): Float? {
        val row = when (pill) {
            WIDE -> listOf(1f, MM28, MM35).filter { it == 1f || it == wide || (it == MM28 && extra28) || (it == MM35 && extra35) }
            TELE -> listOf(7f, MM243).filter { it == 7f || extra243 }
            else -> return null
        }
        if (row.size < 2) return null
        val here = row.indices.minByOrNull { abs(row[it] - zoom) } ?: 0
        return row[(here + 1) % row.size]
    }
}

/** Preview size for the viewfinder: the biggest 4:3 size up to [maxWidth] wide (the sensor is 4:3); else the biggest size that fits. */
fun choosePreviewSize(sizes: List<Pair<Int, Int>>, maxWidth: Int = 1920): Pair<Int, Int>? {
    val fits = sizes.filter { it.first <= maxWidth }
    val fourThree = fits.filter { abs(it.first * 3 - it.second * 4) <= 4 }
    return (fourThree.ifEmpty { fits }.ifEmpty { sizes }).maxByOrNull { it.first.toLong() * it.second }
}
