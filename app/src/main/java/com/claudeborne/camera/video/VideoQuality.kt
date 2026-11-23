package com.claudeborne.camera.video

/** The four resolutions of the quality picker. Sizes are the landscape camera sizes; 2K is 2560x1440 (stock "2K"). */
enum class Res(val label: String, val width: Int, val height: Int) {
    R720("720p", 1280, 720),
    R1080("1080p", 1920, 1080),
    R2K("2K", 2560, 1440),
    R4K("4K", 3840, 2160),
}

/**
 * One entry of the quality picker: a resolution at a frame rate. [highSpeed] entries (120 and 240 fps) run in a constrained
 * high-speed camera session, which has its own rules (no HDR, 1 or 2 surfaces, only the sizes the camera lists).
 */
data class VideoOption(val res: Res, val fps: Int) {
    val highSpeed get() = fps > 60
    /** "4K · 30", the text of the pill. */
    val label get() = "${res.label} · $fps"
}

/** What one camera can do for video, read from `CameraCharacteristics` once per open (see CameraEngine). */
class VideoCaps(
    /** Landscape sizes the camera can stream into a SurfaceTexture (normal sessions). */
    val sizes: Set<Pair<Int, Int>>,
    /** AE target ranges, as (low, high). */
    val aeRanges: List<Pair<Int, Int>>,
    /** Constrained high-speed: size to the fps values (the high end of each range) it can run at. */
    val highSpeed: Map<Pair<Int, Int>, Set<Int>>,
    /** `CONTROL_VIDEO_STABILIZATION_MODE` values the camera accepts (0 off, 1 on, 2 preview stabilisation). */
    val stabModes: Set<Int>,
    /** `DynamicRangeProfiles` constants the camera can stream to a 10-bit surface (empty when 10-bit is not possible). */
    val tenBitProfiles: Set<Long>,
) {
    val previewStabilisation get() = 2 in stabModes
}

object VideoQuality {
    val DEFAULT = VideoOption(Res.R4K, 30)

    /**
     * Every normal-session choice the camera can do (30 and 60 fps), plus 120 fps high-speed on the rear. 4K at 120 fps only
     * counts when [mainLens] is true (the probe found it on the main lens only; the others list no 4K high-speed size).
     * [encoderOk] tells whether the video encoder can do a size at a rate (checked against MediaCodec by the caller).
     */
    fun options(caps: VideoCaps, front: Boolean, mainLens: Boolean, highSpeed: Boolean = true, encoderOk: (Res, Int) -> Boolean = { _, _ -> true }): List<VideoOption> {
        val out = mutableListOf<VideoOption>()
        for (res in Res.entries) {
            val size = res.width to res.height
            for (fps in listOf(30, 60)) {
                if (size in caps.sizes && caps.aeRanges.any { it.second == fps } && encoderOk(res, fps)) out += VideoOption(res, fps)
            }
        }
        if (!front && highSpeed) {
            for (res in listOf(Res.R720, Res.R1080, Res.R4K)) {
                if (res == Res.R4K && !mainLens) continue
                if (120 in caps.highSpeed[res.width to res.height].orEmpty() && encoderOk(res, 120)) out += VideoOption(res, 120)
            }
        }
        return out
    }

    /** Slo-mo mode choices: 120 and 240 fps (rear only), 4K only at 120 on the main lens. Highest quality first. */
    fun sloMoOptions(caps: VideoCaps, mainLens: Boolean, highSpeed: Boolean = true, encoderOk: (Res, Int) -> Boolean = { _, _ -> true }): List<VideoOption> {
        if (!highSpeed) return emptyList()
        val out = mutableListOf<VideoOption>()
        for (res in listOf(Res.R4K, Res.R1080, Res.R720)) {
            if (res == Res.R4K && !mainLens) continue
            for (fps in listOf(120, 240)) {
                if (fps in caps.highSpeed[res.width to res.height].orEmpty() && encoderOk(res, fps)) out += VideoOption(res, fps)
            }
        }
        return out.filter { it.res != Res.R4K || it.fps == 120 }
    }

    /** Time-lapse choices: the 30 fps entries (a time-lapse file is played back at 30 fps). */
    fun timeLapseOptions(all: List<VideoOption>) = all.filter { it.fps == 30 }

    /** The wanted option if the lens can do it; else the same resolution at the nearest rate, else the best one left, else null. */
    fun resolve(wanted: VideoOption, available: List<VideoOption>): VideoOption? {
        if (wanted in available) return wanted
        val sameRes = available.filter { it.res == wanted.res }.minByOrNull { kotlin.math.abs(it.fps - wanted.fps) }
        if (sameRes != null) return sameRes
        return available.filter { it.fps == wanted.fps }.maxByOrNull { it.res.width }
            ?: available.filter { it.fps <= 60 }.maxByOrNull { it.res.width * 1000 + it.fps }
            ?: available.firstOrNull()
    }

    /** The AE range to ask for: fixed at [fps], or (when [adaptive]) down to the lowest lower end the camera lists for it. */
    fun aeRange(caps: VideoCaps, fps: Int, adaptive: Boolean): Pair<Int, Int>? {
        val fixed = caps.aeRanges.firstOrNull { it.first == fps && it.second == fps }
        val loose = caps.aeRanges.filter { it.second == fps }.minByOrNull { it.first }
        return if (adaptive) loose ?: fixed else fixed ?: loose
    }

    /** The stabilisation mode to request: Ultra Steady (preview stabilisation) if wanted and offered, else normal, else none. High-speed has none. */
    fun stabilisation(caps: VideoCaps, ultra: Boolean, highSpeed: Boolean): Int = when {
        highSpeed -> 0
        ultra && caps.previewStabilisation -> 2
        1 in caps.stabModes -> 1
        else -> 0
    }

    /** Adaptive frame rate setting: 0 off, 1 for 30 fps videos only, 2 for 30 and 60 fps videos. */
    fun adaptive(setting: Int, fps: Int) = (setting == 1 && fps == 30) || (setting == 2 && (fps == 30 || fps == 60))
}

/** The main lens range of the logical rear camera, for the 4K 120 fps rule and "Lock lens". */
object LensZone {
    const val ULTRAWIDE_MAX = 1f
    const val MAIN_MAX = 3.5f

    /** 0 ultra wide (below 1x), 1 main (1x up to the periscope), 2 periscope. */
    fun of(zoom: Float) = when {
        zoom < ULTRAWIDE_MAX - 0.02f -> 0
        zoom < MAIN_MAX - 0.02f -> 1
        else -> 2
    }

    fun isMain(zoom: Float) = of(zoom) == 1

    /** The zoom range of the lens [zoom] is on, so a locked lens cannot cross into another one. */
    fun range(zoom: Float, min: Float, max: Float): ClosedFloatingPointRange<Float> = when (of(zoom)) {
        0 -> min..(ULTRAWIDE_MAX - 0.02f)
        1 -> ULTRAWIDE_MAX..(MAIN_MAX - 0.02f)
        else -> MAIN_MAX..max
    }
}
