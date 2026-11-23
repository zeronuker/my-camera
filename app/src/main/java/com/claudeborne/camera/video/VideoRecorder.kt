package com.claudeborne.camera.video

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Surface
import com.claudeborne.camera.gl.PreviewRenderer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records the pictures the GL pipeline draws into [inputSurface] (and, for normal video, the microphone) with MediaCodec and
 * MediaMuxer, straight into a pending MediaStore entry in `DCIM/Camera`. It owns no screen: once [stop] is called the file is
 * finished on its own threads, even if the app is closed meanwhile. Everything that can fail ends in [Listener], never a throw.
 */
class VideoRecorder(private val context: Context, private val cfg: Config, private val listener: Listener) {
    class Config(
        val width: Int, val height: Int,
        /** Frame rate the file is played back at (30 for slo-mo and time-lapse). */
        val playbackFps: Int,
        /** Camera frame rate, for the slo-mo stretch and the encoder's operating rate. */
        val captureFps: Int,
        val hevc: Boolean, val hdr: Hdr?, val bitrate: Int, val audio: Boolean, val style: VideoStyle, val timeLapseSpeed: Int,
        /** Camera timestamps share the clock of SystemClock.elapsedRealtimeNanos (CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME). */
        val cameraClockIsBoot: Boolean,
        val location: Location? = null,
        /**
         * High-speed (120 and 240 fps): the camera writes straight into [inputSurface] (a constrained high-speed session
         * needs a recording surface to deliver every frame; the screen only gets a slower preview), so the frames
         * are not drawn by GL. The picture is turned by [orientationHint] when played back.
         */
        val direct: Boolean = false,
        val orientationHint: Int = 0,
    )

    interface Listener {
        /** Called on the main thread when the file is complete ([uri] is null when nothing worth keeping was recorded). */
        fun onFinished(uri: Uri?, error: String?)

        /** Called from any thread when recording cannot go on (storage full, encoder failed); the screen should call [stop]. */
        fun onProblem(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val resolver = context.contentResolver

    private var uri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var muxer: MediaMuxer? = null
    private var video: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var record: AudioRecord? = null
    private var audioChannels = 2
    private var audioRate = AUDIO_RATE
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    // the muxer starts when every track has told its format
    private val muxLock = Object()
    private var tracksExpected = 1
    private var tracksAdded = 0
    private var muxStarted = false
    @Volatile private var muxFailed = false
    @Volatile private var samples = 0
    @Volatile private var framesOut = 0 // frames the video encoder produced (for the error text)

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var stopping = false
    private var startReal = 0L
    private var pausedTotal = 0L
    private var pausedAt = 0L
    private var resumeReal = 0L
    private var lastPts = -1L
    private var kept = 0L
    @Volatile private var audioResync = true

    /** The encoder's input surface; the renderer draws every frame into it. */
    var inputSurface: Surface? = null
        private set

    /** Milliseconds recorded so far, pauses left out. */
    fun activeMs(): Long {
        if (!running && startReal == 0L) return 0
        val now = if (paused) pausedAt else SystemClock.elapsedRealtimeNanos()
        return ((now - startReal - pausedTotal) / 1_000_000).coerceAtLeast(0)
    }

    val isPaused get() = paused

    /** How loud the microphone is right now, 0..1 (1 is the loudest the microphone can record). */
    @Volatile var level = 0f
        private set

    private fun peak(pcm: ByteArray, bytes: Int): Float = VideoRules.peakLevel(pcm, bytes)

    /** Creates the file, the encoders and the input surface. Throws only what [start] callers catch (see [Result]). */
    fun prepare(): Result<Surface> = runCatching {
        val name = VideoRules.fileName(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val u = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("could not create the file")
        uri = u
        try {
            val p = resolver.openFileDescriptor(u, "rw") ?: throw IOException("could not open the file")
            pfd = p
            val m = MediaMuxer(p.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            cfg.location?.let { runCatching { m.setLocation(it.latitude.toFloat(), it.longitude.toFloat()) } }
            if (cfg.orientationHint != 0) m.setOrientationHint(cfg.orientationHint)
            muxer = m
            video = createVideoEncoder().also { inputSurface = it.createInputSurface(); it.start() }
            if (cfg.audio) {
                try {
                    createAudio()
                    tracksExpected = 2
                } catch (e: Exception) {
                    throw IOException("the microphone could not start", e)
                }
            }
        } catch (e: Throwable) {
            abort()
            throw e
        }
        inputSurface!!
    }

    private fun createVideoEncoder(): MediaCodec {
        val mime = if (cfg.hevc || cfg.hdr != null) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val f = MediaFormat.createVideoFormat(mime, cfg.width, cfg.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, cfg.playbackFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (cfg.captureFps > 30) setInteger(MediaFormat.KEY_OPERATING_RATE, cfg.captureFps)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
            if (cfg.hdr != null) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, cfg.hdr.transfer)
                if (cfg.hdr == Hdr.HDR10) setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdr10StaticInfo())
            }
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f)
        val codec = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(mime)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    private fun createAudio() {
        var channels = 2
        var rec: AudioRecord? = null
        for (source in listOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)) {
            for (ch in listOf(2, 1)) {
                val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                val min = AudioRecord.getMinBufferSize(AUDIO_RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                @Suppress("MissingPermission") // the screen asks for RECORD_AUDIO before it starts a recording with sound
                val r = AudioRecord(source, AUDIO_RATE, mask, AudioFormat.ENCODING_PCM_16BIT, min * 4)
                if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; channels = ch; break } else r.release()
            }
            if (rec != null) break
        }
        record = rec ?: throw IOException("no microphone")
        audioChannels = channels
        audioRate = AUDIO_RATE
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 2) 192_000 else 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
            it.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
    }

    /** Starts the clock, the audio and the drain threads. The renderer should start drawing into [inputSurface] first. */
    fun start() {
        startReal = SystemClock.elapsedRealtimeNanos()
        running = true
        videoThread = Thread({ drainVideo() }, "video-drain").also { it.start() }
        if (cfg.audio) audioThread = Thread({ audioLoop() }, "audio").also { it.start() }
    }

    fun pause() {
        if (!running || paused) return
        pausedAt = SystemClock.elapsedRealtimeNanos()
        paused = true
    }

    fun resume() {
        if (!running || !paused) return
        resumeReal = SystemClock.elapsedRealtimeNanos()
        pausedTotal += resumeReal - pausedAt
        audioResync = true
        paused = false
    }

    /** Called on the GL thread for every camera frame: the time to give it in the file (ns), or -1 to leave it out. */
    fun gate(frameTimestampNs: Long): Long {
        if (!running || paused || stopping) return -1
        val real = if (cfg.cameraClockIsBoot) frameTimestampNs else SystemClock.elapsedRealtimeNanos()
        if (real < resumeReal || real < startReal) return -1 // taken before the start or while paused
        val elapsed = real - startReal - pausedTotal
        if (elapsed < 0) return -1
        val pts = when (cfg.style) {
            VideoStyle.NORMAL -> elapsed
            VideoStyle.SLOMO -> VideoRules.slowMoPts(elapsed, cfg.captureFps)
            VideoStyle.TIMELAPSE -> {
                if (!TimeLapse.keep(elapsed, kept, cfg.timeLapseSpeed)) return -1
                TimeLapse.pts(kept++)
            }
        }
        if (pts <= lastPts) return -1
        lastPts = pts
        return pts
    }

    /** Ends the recording and finishes the file in the background; [Listener.onFinished] follows on the main thread. */
    fun stop(renderer: PreviewRenderer?) {
        if (stopping) return
        stopping = true
        running = false
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { if (once.compareAndSet(false, true)) Thread({ finish() }, "video-finish").start() }
        if (renderer != null) {
            renderer.stopEncoding { finish() }
            // the GL thread may be paused (the app is leaving): finish the file anyway, its surface is freed when it wakes up
            main.postDelayed({ finish() }, 900)
        } else {
            finish()
        }
    }

    /** Gives up a recording that never started (the encoder surface could not be used). */
    fun cancel() { stopping = true; abort() }

    private fun finish() {
        var error: String? = null
        try {
            try { video?.signalEndOfInputStream() } catch (_: Exception) { }
            audioStop = true
            videoThread?.join(4000)
            audioThread?.join(4000)
            synchronized(muxLock) {
                if (muxStarted) {
                    try { muxer?.stop() } catch (e: Exception) { if (samples > 0) error = "The video may be damaged" }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "Could not finish the video"
        }
        releaseAll()
        val u = uri
        var keep: Uri? = null
        if (u != null) {
            if (samples > 0) {
                try {
                    resolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    keep = u
                } catch (_: Exception) {
                    runCatching { resolver.delete(u, null, null) }
                }
            } else {
                runCatching { resolver.delete(u, null, null) } // nothing was recorded: no empty file left behind
            }
        }
        if (keep == null && error == null) error = "Nothing was recorded (video frames: $framesOut)"
        main.post { listener.onFinished(keep, error) }
    }

    /** Throws everything away (a start that failed). */
    private fun abort() {
        releaseAll()
        uri?.let { runCatching { resolver.delete(it, null, null) } }
        uri = null
    }

    private fun releaseAll() {
        runCatching { video?.stop() }
        runCatching { video?.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        runCatching { record?.release() }
        runCatching { muxer?.release() }
        runCatching { pfd?.close() }
        runCatching { inputSurface?.release() }
        video = null; audioCodec = null; record = null; muxer = null; pfd = null; inputSurface = null
    }

    // ---- muxer ----

    private fun addTrack(format: MediaFormat): Int = synchronized(muxLock) {
        val m = muxer ?: return -1
        val index = m.addTrack(format)
        if (++tracksAdded == tracksExpected) {
            m.start()
            muxStarted = true
            muxLock.notifyAll()
        }
        index
    }

    private fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxLock) {
            val limit = SystemClock.uptimeMillis() + 3000
            while (!muxStarted && !muxFailed && SystemClock.uptimeMillis() < limit) muxLock.wait(100)
            if (!muxStarted || muxFailed) return
            try {
                muxer?.writeSampleData(track, buffer, info)
                samples++
            } catch (e: Exception) {
                muxFailed = true
                listener.onProblem(if (e is IllegalStateException || e is IOException) "Storage is full" else "Could not write the video")
            }
        }
    }

    // ---- drain loops ----

    private fun drainVideo() {
        val codec = video ?: return
        val info = MediaCodec.BufferInfo()
        var track = -1
        var firstUs = -1L
        val factor = if (cfg.style == VideoStyle.SLOMO) VideoRules.slowFactor(cfg.captureFps).toLong() else 1L
        try {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = addTrack(codec.outputFormat)
                    i >= 0 -> {
                        val buf = codec.getOutputBuffer(i)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) framesOut++
                        if (cfg.direct && info.size > 0) {
                            // camera time to file time: counted from the first frame (whatever clock the camera and encoder use), stretched for slo-mo
                            if (firstUs < 0) firstUs = info.presentationTimeUs
                            val rel = info.presentationTimeUs - firstUs
                            if (rel < 0 || !running && !stopping) info.size = 0 else info.presentationTimeUs = rel * factor
                        }
                        if (buf != null && info.size > 0 && track >= 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            write(track, buf, info)
                        }
                        codec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            listener.onProblem("The video encoder stopped")
        }
    }

    @Volatile private var audioStop = false

    private fun audioLoop() {
        val rec = record ?: return
        val codec = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        var track = -1
        val chunk = ByteArray(4096 * audioChannels)
        var counterNs = 0L
        var baseNs = 0L
        var lastUs = -1L
        var sawEos = false
        fun drain(untilEos: Boolean) {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
                when {
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = addTrack(codec.outputFormat)
                    i >= 0 -> {
                        val buf = codec.getOutputBuffer(i)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (buf != null && info.size > 0 && track >= 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            write(track, buf, info)
                        }
                        codec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { sawEos = true; return }
                    }
                    else -> if (!untilEos) return
                }
            }
        }
        try {
            rec.startRecording()
            while (!audioStop) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                level = maxOf(peak(chunk, n), level * 0.85f) // the Pro video meter: the loudest sample, falling back smoothly
                val frames = n / (2 * audioChannels)
                val chunkNs = frames * 1_000_000_000L / audioRate
                val now = SystemClock.elapsedRealtimeNanos()
                if (audioResync) { // first chunk, and after every pause: line the sound up with the picture's clock
                    baseNs = (now - chunkNs - startReal - pausedTotal) - counterNs
                    audioResync = false
                }
                val ptsUs = ((baseNs + counterNs).coerceAtLeast(0)) / 1000
                counterNs += chunkNs
                if (paused || !running) continue // heard but thrown away
                var offset = 0
                while (offset < n) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx < 0) { drain(false); continue }
                    val inBuf = codec.getInputBuffer(idx) ?: break
                    inBuf.clear()
                    val take = minOf(inBuf.capacity(), n - offset)
                    inBuf.put(chunk, offset, take)
                    val us = maxOf(ptsUs + (offset / (2 * audioChannels)) * 1_000_000L / audioRate, lastUs + 1)
                    lastUs = us
                    codec.queueInputBuffer(idx, 0, take, us, 0)
                    offset += take
                }
                drain(false)
            }
            // end of stream
            while (true) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) { codec.queueInputBuffer(idx, 0, 0, maxOf(lastUs + 1, 0), MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
            }
            drain(true)
        } catch (e: Exception) {
            if (!sawEos && !stopping) listener.onProblem("The microphone stopped")
        } finally {
            runCatching { rec.stop() }
        }
    }

    private companion object {
        const val AUDIO_RATE = 48_000

        /** Plain HDR10 static metadata (BT.2020 primaries, D65 white, 1000 nit peak) as MediaFormat.KEY_HDR_STATIC_INFO expects it. */
        fun hdr10StaticInfo(): ByteBuffer = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0)
            for (v in intArrayOf(35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450, 1000, 50, 1000, 400)) putShort(v.toShort())
            flip()
        }
    }
}
