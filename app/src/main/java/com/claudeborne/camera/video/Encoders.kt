package com.claudeborne.camera.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** What this phone's video encoders can do, asked from MediaCodec once and remembered. */
object Encoders {
    private val infos by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder } }
    private val answers = HashMap<Triple<Boolean, Res, Int>, Boolean>()

    private fun forMime(mime: String) = infos.filter { i -> i.supportedTypes.any { it.equals(mime, ignoreCase = true) } }

    fun hasHevc() = forMime(MediaFormat.MIMETYPE_VIDEO_HEVC).isNotEmpty()

    /** Whether some encoder of the codec records [res] at [fps]. */
    @Synchronized
    fun supports(hevc: Boolean, res: Res, fps: Int): Boolean = answers.getOrPut(Triple(hevc, res, fps)) {
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        forMime(mime).any { info ->
            runCatching { info.getCapabilitiesForType(mime).videoCapabilities.areSizeAndRateSupported(res.width, res.height, fps.toDouble()) }.getOrDefault(false)
        }
    }

    /** The codec to record [res] at [fps] with: HEVC when wanted and able, else AVC when able, else null (no encoder does it). */
    fun codecFor(wantHevc: Boolean, res: Res, fps: Int): Boolean? = when {
        wantHevc && supports(true, res, fps) -> true
        supports(false, res, fps) -> false
        else -> null
    }

    /** Highest bitrate any encoder of the codec takes. */
    fun maxBitrate(hevc: Boolean): Int {
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        return forMime(mime).maxOfOrNull { runCatching { it.getCapabilitiesForType(mime).videoCapabilities.bitrateRange.upper }.getOrDefault(100_000_000) } ?: 100_000_000
    }

    /** An HEVC encoder that takes 10-bit (Main10) input; without one the HDR choices are hidden. */
    fun hasTenBit(): Boolean {
        val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        return forMime(mime).any { info ->
            runCatching { info.getCapabilitiesForType(mime).profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 } }.getOrDefault(false)
        }
    }

    /** Dolby Vision: only reported (names of the encoders found), never offered. See the Phase 2 hand-over note. */
    fun dolbyVisionEncoders(): List<String> = forMime("video/dolby-vision").map { it.name }
}
