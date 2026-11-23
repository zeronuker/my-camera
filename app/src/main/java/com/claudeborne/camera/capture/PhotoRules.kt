package com.claudeborne.camera.capture

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class Flash {
    OFF, AUTO, ON, TORCH;

    /** The flash button cycles through what the camera has: the rear has all four, the front only off and on (screen flash). */
    fun next(rear: Boolean): Flash = when {
        !rear -> if (this == ON) OFF else ON
        else -> entries[(ordinal + 1) % entries.size]
    }

    val label get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

/** How a photo is stored. [mime] and [extension] are what MediaStore and the file name get. */
enum class StillFormat(val mime: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    JPEG_R("image/jpeg", "jpg"), // Ultra HDR: a normal JPEG with a gain map inside
    HEIC("image/heic", "heic"),
}

object PhotoRules {
    /**
     * Which format to shoot. A mirrored selfie needs its EXIF rewritten, which would break the gain map of an Ultra HDR
     * file, so the front camera with mirroring on always makes a plain JPEG.
     */
    fun chooseFormat(front: Boolean, mirror: Boolean, heif: Boolean, ultraHdr: Boolean, supported: Set<StillFormat>): StillFormat {
        val wanted = when {
            front && mirror -> StillFormat.JPEG
            heif -> StillFormat.HEIC
            ultraHdr -> StillFormat.JPEG_R
            else -> StillFormat.JPEG
        }
        return if (wanted in supported) wanted else StillFormat.JPEG
    }

    /** The JPEG_ORIENTATION to ask the camera for: how far the picture must turn clockwise to stand upright. */
    fun jpegOrientation(sensorOrientation: Int, deviceDegrees: Int, front: Boolean): Int =
        ((sensorOrientation + (if (front) deviceDegrees else -deviceDegrees)) % 360 + 360) % 360

    /**
     * The phone's tilt (0..359 from OrientationEventListener, growing clockwise) as the quarter turn [jpegOrientation] expects.
     * The listener and Android's display rotation count in opposite directions, hence the 360 minus.
     */
    fun deviceDegrees(tilt: Int): Int = (360 - ((tilt + 45) / 90) * 90 % 360) % 360

    /** EXIF orientation tag for a turn of [jpegOrientation], or the mirrored (selfie) version of it. */
    fun exifOrientation(jpegOrientation: Int, mirrored: Boolean): Int {
        val plain = mapOf(0 to 1, 90 to 6, 180 to 3, 270 to 8)
        val flipped = mapOf(0 to 2, 90 to 5, 180 to 4, 270 to 7) // turn first, then flip left-right
        return (if (mirrored) flipped else plain).getValue(jpegOrientation)
    }

    /** `IMG_20261123_154210_123.jpg`, in the same style as the stock app. */
    fun fileName(timeMs: Long, extension: String, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("'IMG_'yyyyMMdd_HHmmss_SSS", Locale.US).apply { timeZone = zone }.format(Date(timeMs)) + "." + extension

    /** The largest size whose shape is closest to the screen's, so what is saved matches what the viewfinder shows. */
    fun chooseStillSize(sizes: List<Pair<Int, Int>>, screenAspect: Float): Pair<Int, Int>? {
        fun gap(s: Pair<Int, Int>) = kotlin.math.abs(s.first.toFloat() / s.second - screenAspect)
        val best = sizes.minOfOrNull { gap(it) } ?: return null
        return sizes.filter { gap(it) <= best + 0.05f }.maxByOrNull { it.first.toLong() * it.second }
    }
}
