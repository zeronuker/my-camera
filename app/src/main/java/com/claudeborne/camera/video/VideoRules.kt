package com.claudeborne.camera.video

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** The three recording styles that share the video pipeline. */
enum class VideoStyle { NORMAL, SLOMO, TIMELAPSE }

/** 10-bit HDR choices of the video menu. [camera] is the `DynamicRangeProfiles` constant, [transfer] the MediaFormat transfer. */
enum class Hdr(val label: String, val camera: Long, val transfer: Int) {
    HLG10("HLG10", 0x2L, 7), // DynamicRangeProfiles.HLG10, MediaFormat.COLOR_TRANSFER_HLG
    HDR10("HDR10", 0x4L, 6), // DynamicRangeProfiles.HDR10, MediaFormat.COLOR_TRANSFER_ST2084
}

object VideoRules {
    /** `VID_20261123_154210_123.mp4`, in the same style as the photos. */
    fun fileName(timeMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("'VID_'yyyyMMdd_HHmmss_SSS", Locale.US).apply { timeZone = zone }.format(Date(timeMs)) + ".mp4"

    /**
     * Video bits per second. Bits per pixel and frame depend on the codec (HEVC needs about two thirds of AVC), on the
     * Standard / High quality setting, and on 10-bit (a quarter more). Very high frame rates get less per frame because
     * neighbouring frames are nearly identical. Never above [cap].
     */
    fun bitrate(width: Int, height: Int, fps: Int, hevc: Boolean, high: Boolean, tenBit: Boolean, cap: Int = 200_000_000): Int {
        var bpp = if (high) 0.12 else 0.075
        if (!hevc) bpp *= 1.5
        if (tenBit) bpp *= 1.25
        val frames = if (fps > 60) 60 + (fps - 60) * 0.35 else fps.toDouble()
        return (width.toDouble() * height * frames * bpp).roundToInt().coerceIn(2_000_000, cap)
    }

    /** The size of the saved video: [res] landscape, or turned to portrait when the phone is held upright. */
    fun frameSize(res: Res, portrait: Boolean): Pair<Int, Int> = if (portrait) res.height to res.width else res.width to res.height

    /** Whether the recording is portrait for a [turn] (clockwise quarter turns of the upright picture, in degrees). */
    fun portrait(turn: Int) = turn % 180 == 0

    /** The turn (degrees, clockwise) of the upright portrait picture so the video is upright for how the phone is held. */
    fun turn(deviceDegrees: Int, front: Boolean): Int = if (front) deviceDegrees % 360 else (360 - deviceDegrees) % 360

    /** `0:07` or `1:02:09`, the recording timer. */
    fun timer(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, sec) else "%d:%02d".format(Locale.US, m, sec)
    }

    /** Slo-mo plays back at this rate, so 120 fps is 4x slower and 240 fps 8x slower. */
    const val PLAYBACK_FPS = 30

    /** How much slower than real time a slo-mo clip plays. */
    fun slowFactor(captureFps: Int) = captureFps / PLAYBACK_FPS

    /** Slo-mo clip time of a frame captured [realNs] after the start. */
    fun slowMoPts(realNs: Long, captureFps: Int): Long = realNs * slowFactor(captureFps)
}

/** Time-lapse: one frame every [speed] / 30 seconds, played back at 30 fps, so the clip runs [speed] times faster than life. */
object TimeLapse {
    val SPEEDS = listOf(10, 30, 60, 120, 300)
    const val DEFAULT_SPEED = 30
    const val OUT_FPS = 30

    /** Seconds of real time between two kept frames. */
    fun intervalSec(speed: Int) = speed.toDouble() / OUT_FPS

    /** Presentation time of the [index]-th kept frame (0 based). */
    fun pts(index: Long): Long = index * 1_000_000_000L / OUT_FPS

    /** Whether the frame at [elapsedNs] since the start is the next one to keep, given [keptSoFar]. */
    fun keep(elapsedNs: Long, keptSoFar: Long, speed: Int): Boolean = elapsedNs >= (keptSoFar * speed * 1_000_000_000L) / OUT_FPS

    /** Length of the clip for [realSeconds] of recording. */
    fun clipSeconds(realSeconds: Long, speed: Int): Double = realSeconds.toDouble() / speed

    fun label(speed: Int) = "${speed}x"
}
