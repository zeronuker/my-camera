package com.claudeborne.camera.capture

import java.util.Locale
import kotlin.math.roundToInt

/** Exposure compensation: the screen works in stops (EV), the camera in steps of its own size. */
object Ev {
    /** The most the dial moves either way, or less if the camera allows less. */
    const val LIMIT = 3f

    fun toIndex(stops: Float, step: Float, min: Int, max: Int): Int =
        if (step <= 0f) 0 else (stops / step).roundToInt().coerceIn(min, max)

    /** "+0.7", "-1.0", "0.0": the sign only when it matters. */
    fun label(stops: Float): String {
        val tenths = (stops * 10f).roundToInt()
        return String.format(Locale.US, if (tenths > 0) "+%.1f" else "%.1f", tenths / 10f)
    }
}
