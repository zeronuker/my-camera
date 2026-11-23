package com.claudeborne.camera.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/**
 * A small H.264 encoder that runs while Live photo is on and keeps the last couple of seconds of encoded frames in memory. When the shutter is
 * pressed the clip (from just before the press to a moment after) is cut out of it and muxed into an MP4. The frames come from the GL pipeline
 * through [surface], like a video recording. No sound.
 */
class ClipRing(width: Int, height: Int, private val keepUs: Long = 1_700_000) {
    private class Sample(val data: ByteArray, val ptsUs: Long, val key: Boolean)

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val surface: Surface
    private val samples = ArrayDeque<Sample>()
    @Volatile private var format: MediaFormat? = null
    @Volatile private var running = true
    private val drain: Thread

    init {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f) // a keyframe twice a second, so the clip can start close to where it should
        }
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
        drain = Thread({
            val info = MediaCodec.BufferInfo()
            while (running) {
                val i = try { codec.dequeueOutputBuffer(info, 20_000) } catch (_: Exception) { break }
                when {
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = codec.outputFormat
                    i >= 0 -> {
                        val buf = codec.getOutputBuffer(i)
                        if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val bytes = ByteArray(info.size)
                            buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(bytes)
                            synchronized(samples) {
                                samples.addLast(Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0))
                                trim()
                            }
                        }
                        try { codec.releaseOutputBuffer(i, false) } catch (_: Exception) { break }
                    }
                }
            }
        }, "clip-ring").apply { start() }
    }

    /** Drops what is older than [keepUs] before the newest frame, but always keeps a keyframe to start from. */
    private fun trim() {
        val latest = samples.last().ptsUs
        while (true) {
            val nextKey = samples.indexOfFirst { it.key && it !== samples.first() }
            if (nextKey < 0 || samples[nextKey].ptsUs > latest - keepUs) break
            repeat(nextKey) { samples.removeFirst() }
        }
    }

    /** The clip so far as MP4 bytes, or null when there is nothing usable. Blocks (writes a file): call it off the main thread. */
    fun mp4(tempDir: File): ByteArray? {
        val fmt = format ?: return null
        val list = synchronized(samples) { samples.toList() }
        val start = list.indexOfFirst { it.key }
        if (start < 0 || list.size - start < 5) return null
        val clip = list.subList(start, list.size)
        val file = File(tempDir, "live.mp4")
        return try {
            val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(fmt)
            muxer.start()
            val info = MediaCodec.BufferInfo()
            val t0 = clip.first().ptsUs
            for (s in clip) {
                info.set(0, s.data.size, s.ptsUs - t0, if (s.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, java.nio.ByteBuffer.wrap(s.data), info)
            }
            muxer.stop(); muxer.release()
            file.readBytes()
        } catch (_: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    fun release() {
        running = false
        runCatching { drain.join(300) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
    }
}
