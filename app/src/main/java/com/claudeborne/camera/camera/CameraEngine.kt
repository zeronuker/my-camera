package com.claudeborne.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.util.Range
import com.claudeborne.camera.video.Hdr
import com.claudeborne.camera.video.LensZone
import com.claudeborne.camera.video.VideoCaps
import android.hardware.camera2.params.SessionConfiguration
import android.location.Location
import android.hardware.camera2.DngCreator
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.media.ImageReader
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.claudeborne.camera.capture.Ev
import com.claudeborne.camera.capture.Flash
import com.claudeborne.camera.capture.PhotoRules
import com.claudeborne.camera.capture.StillFormat
import android.hardware.camera2.params.MeteringRectangle
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Opens one camera, streams it into a [SurfaceTexture] and takes photos. All Camera2 work runs on its own thread, never the main one.
 * [state] tells the screen what to show; every failure ends up there as an [State.Error], nothing throws into the UI.
 * The screen must call [close] when it pauses so other apps (the bank's QR scanner, say) can use the camera at once.
 */
class CameraEngine(context: Context) {
    enum class Facing { BACK, FRONT }
    enum class Kind { BUSY, DISCONNECTED, DISABLED, FAILED }

    sealed interface State {
        data object Idle : State
        data object Opening : State
        data object Streaming : State
        data class Error(val kind: Kind, val detail: String = "") : State
    }

    /** What the screen needs to draw the picture and the controls for the camera that is open. */
    class Info(
        val facing: Facing, val sensorOrientation: Int, val previewWidth: Int, val previewHeight: Int,
        val zoomMin: Float, val zoomMax: Float, val hasFlash: Boolean, val format: StillFormat,
        val activeWidth: Int, val activeHeight: Int, val evStep: Float, val evMinIndex: Int, val evMaxIndex: Int,
        val video: VideoCaps, val cameraClockIsBoot: Boolean, val hasAwbLock: Boolean,
        /** What the camera allows for the Pro controls; each control is hidden when its range is empty. */
        val pro: ProCaps = ProCaps(),
    )

    /**
     * A video preview session: the stream size, frame rate and the choices that change how the session itself is built.
     * 120 and 240 fps recording is a different session, built only while recording (see [startHighSpeed]).
     */
    data class VideoSetup(
        val width: Int, val height: Int, val fps: Int, val hdr: Hdr?,
        /** `CONTROL_VIDEO_STABILIZATION_MODE`: 0 off, 1 on, 2 preview stabilisation (Ultra Steady). */
        val stabilisation: Int, val aeRange: Pair<Int, Int>,
    )

    /** What the focus is doing right now, for the colour of the focus box. */
    enum class Focus { IDLE, SEARCHING, FOCUSED, FAILED }

    /** The user's format choices when the camera opens; the engine picks what this camera can really do. */
    class StillPrefs(val heif: Boolean, val ultraHdr: Boolean, val mirrorSelfie: Boolean, val raw: Boolean = false)

    /** One photo to take. [format] says whether Master wants the JPEG, the DNG or both. */
    class Shot(val flash: Flash, val jpegOrientation: Int, val location: Location?, val squareCrop: Boolean = false, val format: MasterFormat = MasterFormat.JPG)

    class Photo(val bytes: ByteArray, val format: StillFormat, val jpegOrientation: Int, val mirrored: Boolean, val squareCrop: Boolean)

    /** Called on the camera thread. */
    interface Listener {
        fun onShutter()
        fun onPhoto(photo: Photo)
        fun onFailed(message: String)

        /** A video choice the camera refused (10-bit HDR, Ultra Steady) was switched off so the viewfinder could start. */
        fun onVideoDowngraded(message: String) {}

        /** The camera could not run RAW together with the preview; Master went back to JPG so the viewfinder still starts. */
        fun onRawRefused() {}
    }

    var state: State by mutableStateOf(State.Idle)
        private set
    var info: Info? by mutableStateOf(null)
        private set
    /** A photo is being taken (the shutter ignores taps until it is done). */
    var capturing by mutableStateOf(false)
        private set
    var focus by mutableStateOf(Focus.IDLE)
        private set
    /** Focus and exposure are held where the user long-pressed. */
    var locked by mutableStateOf(false)
        private set
    /** The camera refused (or died in) a high-speed session this run: 120 and 240 fps choices are hidden until the app restarts. */
    var hsRefused by mutableStateOf(false)
        private set
    /** The ISO and exposure time (ns) the camera is using right now (updated a few times a second, for the Pro dials). */
    var liveIso by mutableIntStateOf(0)
        private set
    var liveNs by mutableLongStateOf(0L)
        private set
    @Volatile var listener: Listener? = null

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    // everything below is touched on the camera thread only (except zoom and flash)
    private var generation = 0 // callbacks of an older open() see a different number and are ignored
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var surface: Surface? = null
    private var reader: ImageReader? = null
    private var wantFacing = Facing.BACK
    private var wantTexture: SurfaceTexture? = null
    private var wantAspect = 2.2f
    private var wantPrefs = StillPrefs(heif = false, ultraHdr = true, mirrorSelfie = true)
    private var wantVideo: VideoSetup? = null
    @Volatile private var awbLock = false
    private var hsRecord: Surface? = null // the encoder surface while a high-speed recording runs
    private var hsFps = 0
    private var hsCameraId: String? = null // high-speed runs on the physical lens camera, a plain single-sensor session
    private var hsZoom = 1f // zoom relative to that lens
    private var hsReady: ((Boolean) -> Unit)? = null
    private var hsStopped: (() -> Unit)? = null
    private var cameraId: String? = null
    private var retries = 0
    private var hasFlash = false
    private var qualityNoise = false
    private var qualityEdge = false
    private var format = StillFormat.JPEG
    @Volatile private var zoom = 1f
    @Volatile private var flash = Flash.OFF
    @Volatile private var evStops = 0f
    private var focusRect: MeteringRectangle? = null
    private var afRegions = false
    private var aeRegions = false
    private var focusToken = 0
    private var done: CompletableDeferred<Unit>? = null

    // Pro controls (Master and Pro video): the manual choices, and what the camera reported last
    @Volatile private var pro = Pro()
    private var chars: CameraCharacteristics? = null
    private var ccm: android.hardware.camera2.params.ColorSpaceTransform? = null
    private var faceRect: MeteringRectangle? = null
    private var faceCentre = 0 to 0
    private var liveAt = 0L

    // RAW (Master): its own reader, and the two halves of a DNG (the picture and the result that describes it)
    private var rawReader: ImageReader? = null
    private var rawImage: Image? = null
    private var rawResult: TotalCaptureResult? = null
    private var needJpeg = false
    private var needRaw = false

    private enum class Phase { NONE, PRECAPTURE, WAIT_DONE }
    private var phase = Phase.NONE
    private var deadline = 0L
    private var pending: Shot? = null
    private var shotToken = 0

    /** Opens the camera for [facing] and streams into [texture]; closes whatever was open before. Returns at once. */
    fun open(facing: Facing, texture: SurfaceTexture, screenAspect: Float, prefs: StillPrefs, video: VideoSetup? = null) {
        handler.post {
            wantVideo = video
            wantFacing = facing
            wantTexture = texture
            wantAspect = screenAspect
            wantPrefs = prefs
            retries = 0
            start()
        }
    }

    /** Called by "TRY AGAIN" on an error screen. */
    fun retry() {
        handler.post { retries = 0; if (wantTexture != null) start() }
    }

    /**
     * Releases the camera and waits (briefly) until it is really free. A photo that is still on its way is given a moment to
     * arrive first, so leaving the screen right after the shutter does not lose it.
     */
    fun close() {
        val patience = SystemClock.uptimeMillis() + 2000
        while (capturing && SystemClock.uptimeMillis() < patience) Thread.sleep(25)
        val done = CountDownLatch(1)
        handler.post {
            stop()
            state = State.Idle
            done.countDown()
        }
        done.await(1500, TimeUnit.MILLISECONDS)
    }

    /** Ends the camera thread. Only after the last [close]. */
    fun release() { thread.quitSafely() }

    fun setZoom(ratio: Float) {
        zoom = ratio
        handler.post { applyRequest() }
    }

    fun setFlash(mode: Flash) {
        flash = mode
        handler.post { applyRequest() }
    }

    /** The manual choices of Master and Pro video; they apply to the live picture, to photos and to video at once. */
    fun setPro(value: Pro) {
        pro = value
        if (!value.faceMetering) faceRect = null
        handler.post { applyRequest() }
    }

    /** Exposure compensation in stops (EV); the camera rounds it to its own step. */
    fun setExposure(stops: Float) {
        evStops = stops
        handler.post { applyRequest() }
    }

    /**
     * Focuses and meters at a point of the upright picture ([x] from the left, [yUp] from the bottom, both 0..1). With [lock]
     * the focus and exposure then stay put until [clearFocus]; without it the camera goes back to following the scene after a moment.
     */
    fun focusAt(x: Float, yUp: Float, lock: Boolean) {
        handler.post {
            val i = info ?: return@post
            val r = Metering.region(x, yUp, i.sensorOrientation, i.activeWidth, i.activeHeight, zoom)
            focusRect = MeteringRectangle(r[0], r[1], r[2], r[3], MeteringRectangle.METERING_WEIGHT_MAX - 1)
            locked = lock
            applyRequest()
            triggerFocus()
            val token = ++focusToken
            if (!lock) handler.postDelayed({ if (token == focusToken && !locked) releaseFocus() }, 3000)
        }
    }

    /**
     * Reopens the camera for 120 or 240 fps: the physical lens camera that the current zoom is on, with a constrained high-speed session
     * that feeds [record] (the encoder surface) at [fps]. [ready] is called on the camera thread with true once frames flow, false when
     * the camera refused (the normal preview is back then).
     */
    fun startHighSpeed(record: Surface, fps: Int, ready: (Boolean) -> Unit) {
        handler.post {
            if (wantTexture == null || wantVideo == null) return@post ready(false)
            val z = zoom
            val zone = LensZone.of(z)
            hsCameraId = try { physicalFor(zone) } catch (_: Exception) { null }
            hsZoom = z / when (zone) { 0 -> info?.zoomMin ?: z; 1 -> 1f; else -> LensZone.MAIN_MAX }
            hsFps = fps
            hsRecord = record
            hsReady = ready
            retries = 0
            start(keepHs = true)
        }
    }

    /** Back to the normal preview camera; [done] runs once the camera is closed, so nothing writes into the encoder any more. */
    fun stopHighSpeed(done: () -> Unit) {
        handler.post {
            if (hsRecord == null) return@post done()
            hsStopped = done
            retries = 0
            start() // closes the high-speed camera (running [done]) and opens the normal one
        }
    }

    /** The physical camera behind the logical rear camera for a lens zone (0 ultra wide, 1 main, 2 telephoto), found by focal length. */
    private fun physicalFor(zone: Int): String? {
        val logical = pickCamera(Facing.BACK) ?: return null
        val ids = manager.getCameraCharacteristics(logical).physicalCameraIds.toList()
        if (ids.size != 3) return null
        val byFocal = ids.sortedBy { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f }
        return byFocal[zone.coerceIn(0, 2)]
    }

    /** Video "Focus lock": keeps the focus and exposure where they are now (no focus point), until [clearFocus]. */
    fun holdFocus(on: Boolean) {
        handler.post {
            focusRect = null
            locked = on
            focusToken++
            applyRequest()
        }
    }

    /** Video "Lock white balance": the colour balance stays as it is while on. */
    fun setAwbLock(on: Boolean) {
        awbLock = on
        handler.post { applyRequest() }
    }

    /** Back to following the scene by itself. */
    fun clearFocus() {
        handler.post {
            focusRect = null
            locked = false
            focusToken++
            applyRequest()
            releaseFocus()
        }
    }

    /**
     * Takes one photo and returns once it has arrived (or failed), so a burst can take the next one. For flash auto/on the camera
     * first measures the light (a short pre-flash).
     */
    suspend fun takePhotoAndWait(shot: Shot) {
        val finished = CompletableDeferred<Unit>()
        handler.post {
            if (pending != null || reader == null) finished.complete(Unit) else { done = finished; begin(shot) }
        }
        finished.await()
    }

    private fun triggerFocus() {
        val r = request ?: return
        val s = session ?: return
        try {
            r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(r.build(), previewCallback, handler)
            r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        } catch (_: Exception) { }
    }

    private fun releaseFocus() {
        val r = request ?: return
        val s = session ?: return
        try {
            focusRect = null
            r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(r.build(), previewCallback, handler)
            r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            applyRequest()
        } catch (_: Exception) { }
    }

    /** The settings a preview frame and a photo share. */
    private fun common(b: CaptureRequest.Builder) {
        val i = info ?: return
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(i.zoomMin, i.zoomMax))
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, Ev.toIndex(evStops, i.evStep, i.evMinIndex, i.evMaxIndex))
        b.set(CaptureRequest.CONTROL_AE_LOCK, locked)
        val follow = if (wantVideo != null) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        b.set(CaptureRequest.CONTROL_AF_MODE, if (locked) CaptureRequest.CONTROL_AF_MODE_AUTO else follow)
        val rect = (focusRect ?: faceRect)?.let { arrayOf(it) } // a tapped point beats the face metering
        if (afRegions) b.set(CaptureRequest.CONTROL_AF_REGIONS, rect)
        if (aeRegions) b.set(CaptureRequest.CONTROL_AE_REGIONS, rect)
    }

    /**
     * The manual choices on top of a request ([still]: the real exposure time of a photo; the live picture and video are capped, see
     * [ProRules.usableExposure]). Anything the camera cannot do is left alone.
     */
    private fun applyPro(b: CaptureRequest.Builder, still: Boolean) {
        val i = info ?: return
        val p = pro
        val caps = i.pro
        val fps = wantVideo?.fps
        if (p.manualExposure && caps.exposure) {
            val ns = ProRules.usableExposure(p.exposureNs, fps, still).coerceIn(caps.exposureMinNs, caps.exposureMaxNs)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, p.iso.coerceIn(caps.isoMin, caps.isoMax))
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (fps != null) 1_000_000_000L / fps else ProRules.frameDuration(ns))
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
        when (val w = p.wb) {
            Wb.Auto -> b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO) // the builder is reused: undo an earlier choice
            is Wb.Preset -> if (w.awbMode in caps.awbModes) b.set(CaptureRequest.CONTROL_AWB_MODE, w.awbMode)
            is Wb.Kelvin -> {
                val cal = caps.colorCal
                val matrix = ccm
                if (caps.kelvin && cal != null && matrix != null) {
                    val (red, blue) = ProRules.kelvinGains(w.k, cal)
                    b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                    b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(red, 1f, 1f, blue))
                    b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, matrix)
                }
            }
        }
        p.focusDiopters?.let {
            if (caps.focus) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, it.coerceIn(0f, caps.minFocusDiopters))
            }
        }
        if (caps.faceDetect) b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, if (p.faceMetering) CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE else CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
    }

    /** Called with every preview result: remembers what the camera is doing (for the Pro dials) and follows the biggest face. */
    private fun watch(result: TotalCaptureResult) {
        result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { if (pro.wb !is Wb.Kelvin) ccm = it }
        val now = SystemClock.uptimeMillis()
        if (now - liveAt > 250) {
            liveAt = now
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { if (it != liveIso) liveIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { if (it != liveNs) liveNs = it }
        }
        val p = pro
        if (!p.faceMetering || focusRect != null || locked || p.focusDiopters != null) {
            if (faceRect != null && !p.faceMetering) faceRect = null
            return
        }
        val faces = result.get(CaptureResult.STATISTICS_FACES).orEmpty().map { f -> f.bounds.let { intArrayOf(it.left, it.top, it.right, it.bottom) } }
        val big = ProRules.largestFace(faces)
        val i = info ?: return
        if (big == null) {
            if (faceRect != null) { faceRect = null; applyRequest() }
            return
        }
        val centre = (big[0] + big[2]) / 2 to (big[1] + big[3]) / 2
        // follow the face only when it has moved a fair way: re-sending the request on every frame would make the picture hunt
        if (faceRect == null || abs(centre.first - faceCentre.first) > i.activeWidth / 16 || abs(centre.second - faceCentre.second) > i.activeHeight / 16) {
            faceCentre = centre
            faceRect = MeteringRectangle(big[0], big[1], big[2] - big[0], big[3] - big[1], MeteringRectangle.METERING_WEIGHT_MAX - 1)
            applyRequest()
        }
    }

    private fun start(keepHs: Boolean = false) {
        stop(keepHs)
        val texture = wantTexture ?: return
        val mine = generation
        try {
            val id = hsCameraId ?: pickCamera(wantFacing)
            if (id == null) {
                // right after a camera crash the system lists no cameras for a few seconds: wait for them instead of giving up
                if (retries++ < 12) {
                    state = State.Opening
                    handler.postDelayed({ if (mine == generation) start(keepHs) }, 700)
                    return
                }
                return fail(Kind.FAILED, "no ${wantFacing.name.lowercase()} camera")
            }
            val chars = manager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val sizes = map.getOutputSizes(SurfaceTexture::class.java).map { it.width to it.height }
            val caps = videoCaps(chars, map, sizes)
            val video = wantVideo
            val (w, h) = (if (video != null) (video.width to video.height).takeIf { it in caps.sizes && (hsRecord == null || it in caps.highSpeed) } else choosePreviewSize(sizes))
                ?: return if (hsRecord != null) hsFailed("this lens cannot do that high-speed size", mine) else fail(Kind.FAILED, if (video != null) "this camera cannot record that video size" else "no preview size")
            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            qualityNoise = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true
            qualityEdge = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY) == true

            fun stillSizes(f: StillFormat) = if (map.isOutputSupportedFor(imageFormat(f))) map.getOutputSizes(imageFormat(f)).orEmpty().map { it.width to it.height } else emptyList()
            if (video == null) { // video sessions have no still stream: a photo while recording is taken from the GL frame
                val supported = StillFormat.entries.filter { stillSizes(it).isNotEmpty() }.toSet()
                format = PhotoRules.chooseFormat(wantFacing == Facing.FRONT, wantPrefs.mirrorSelfie, wantPrefs.heif, wantPrefs.ultraHdr, supported)
                val (sw, sh) = PhotoRules.chooseStillSize(stillSizes(format), wantAspect) ?: return fail(Kind.FAILED, "no photo size")
                reader = ImageReader.newInstance(sw, sh, imageFormat(format), 2).also { it.setOnImageAvailableListener(::onImage, handler) }
                val raw = rawSizeOf(chars, map)
                if (wantPrefs.raw && raw != null) rawReader = ImageReader.newInstance(raw.first, raw.second, ImageFormat.RAW_SENSOR, 2).also { it.setOnImageAvailableListener(::onRawImage, handler) }
            }
            this.chars = chars

            afRegions = (chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
            aeRegions = (chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
            val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            cameraId = id
            focusRect = null
            locked = false
            info = Info(
                wantFacing, chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, w, h, zoomRange?.lower ?: 1f, zoomRange?.upper ?: 1f, hasFlash, format,
                active.width(), active.height(), chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f, evRange?.lower ?: 0, evRange?.upper ?: 0,
                caps, chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
                proCaps(chars, map),
            )
            texture.setDefaultBufferSize(w, h)
            surface = Surface(texture)
            state = State.Opening
            // a camera that never answers must end in a message, not a frozen black viewfinder
            handler.postDelayed({
                if (mine == generation && state == State.Opening) {
                    if (hsRecord != null) hsFailed("the camera did not answer", mine) else fail(Kind.FAILED, "the camera did not answer")
                }
            }, 8000)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (mine != generation) return camera.close()
                    device = camera
                    configure(camera, mine)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (mine != generation) return
                    if (hsRecord != null || hsReady != null) return hsFailed("the camera stopped", mine)
                    fail(Kind.DISCONNECTED)
                    if (retries++ < 3) handler.postDelayed({ if (mine == generation) start() }, 700)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (mine != generation) return
                    if (hsRecord != null || hsReady != null) return hsFailed("camera error $error", mine)
                    when (error) {
                        ERROR_IN_USE, ERROR_MAX_IN_USE -> busy()
                        ERROR_DISABLED -> fail(Kind.DISABLED)
                        else -> fail(Kind.FAILED, "camera error $error")
                    }
                }
            }, handler)
        } catch (e: CameraAccessException) {
            when (e.reason) {
                CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE -> busy()
                CameraAccessException.CAMERA_DISABLED -> fail(Kind.DISABLED)
                CameraAccessException.CAMERA_DISCONNECTED -> fail(Kind.DISCONNECTED)
                else -> fail(Kind.FAILED, e.message.orEmpty())
            }
        } catch (e: Exception) {
            fail(Kind.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun configure(camera: CameraDevice, mine: Int) {
        val record = hsRecord
        val preview = surface ?: return
        val video = wantVideo
        val still = if (video == null) reader?.surface ?: return else null
        try {
            val outputs = when {
                record != null -> listOf(OutputConfiguration(preview), OutputConfiguration(record)) // high-speed: preview and recording surface
                video != null -> listOf(OutputConfiguration(preview).apply { video.hdr?.let { dynamicRangeProfile = it.camera } })
                else -> listOfNotNull(OutputConfiguration(preview), OutputConfiguration(still!!), rawReader?.let { OutputConfiguration(it.surface) })
            }
            camera.createCaptureSession(
                SessionConfiguration(
                    if (record != null) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR, outputs, executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (mine != generation) return s.close()
                            try {
                                session = s
                                request = camera.createCaptureRequest(if (video != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(preview)
                                    record?.let { addTarget(it) }
                                    set(CaptureRequest.CONTROL_AF_MODE, if (video != null) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                }
                                retries = 0
                                applyRequest()
                                if (record != null && hsRecord == null) return // applyRequest gave up on high-speed and restored the preview
                                state = State.Streaming
                                hsReady?.let { hsReady = null; it(true) }
                                hsStopped?.let { hsStopped = null; it() }
                            } catch (e: Exception) { // a camera that closed under us must end in a message, not an uncaught crash
                                if (record != null) hsFailed(e.message ?: e.javaClass.simpleName, mine)
                                else fail(Kind.FAILED, e.message ?: e.javaClass.simpleName)
                            }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            if (mine != generation) return
                            if (record != null) return hsFailed("the camera refused the high-speed session", mine)
                            if (rawReader != null) { // RAW next to the preview and the JPEG was refused: go on without it, never a dead viewfinder
                                wantPrefs = StillPrefs(wantPrefs.heif, wantPrefs.ultraHdr, wantPrefs.mirrorSelfie, raw = false)
                                listener?.onRawRefused()
                                return start()
                            }
                            // a refused 10-bit or Ultra Steady choice is dropped once, so the viewfinder still comes up
                            if (video != null && (video.hdr != null || video.stabilisation == 2)) {
                                val what = if (video.hdr != null) "10-bit HDR is not available at this quality" else "Ultra Steady is not available at this quality"
                                wantVideo = video.copy(hdr = null, stabilisation = if (video.stabilisation == 2) 1 else video.stabilisation)
                                listener?.onVideoDowngraded(what)
                                start()
                            } else {
                                fail(Kind.FAILED, "could not start the preview")
                            }
                        }
                    },
                ),
            )
        } catch (e: Exception) {
            if (record != null) hsFailed(e.message ?: e.javaClass.simpleName, mine)
            else fail(Kind.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * The camera could not do (or died in) a high-speed session: tell the screen, hide the 120/240 fps choices, and open the normal
     * preview again (after a pause, the camera service may be restarting). The detail goes into the toast so the owner can report it.
     */
    private fun hsFailed(detail: String, mine: Int) {
        hsRecord = null
        hsCameraId = null
        hsRefused = true
        hsReady?.let { hsReady = null; it(false) }
        hsStopped?.let { hsStopped = null; it() }
        listener?.onFailed("High-speed video failed: $detail")
        handler.postDelayed({ if (mine == generation) start() }, 400)
    }

    private fun applyRequest() {
        val r = request ?: return
        val s = session ?: return
        if (hsRecord != null) { // a constrained high-speed request carries only the frame rate (and the lens zoom): anything more can upset the camera
            try {
                r.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(hsFps, hsFps))
                val z = info
                if (z != null && hsZoom > 1.02f) r.set(CaptureRequest.CONTROL_ZOOM_RATIO, hsZoom.coerceIn(z.zoomMin, z.zoomMax))
                val burst = s as? CameraConstrainedHighSpeedCaptureSession ?: throw IllegalStateException("not a high-speed session")
                burst.setRepeatingBurst(burst.createHighSpeedRequestList(r.build()), previewCallback, handler)
            } catch (e: Exception) {
                hsFailed(e.message ?: e.javaClass.simpleName, generation)
            }
            return
        }
        common(r)
        r.set(CaptureRequest.CONTROL_AE_MODE, aeMode(flash))
        r.set(CaptureRequest.FLASH_MODE, if (flash == Flash.TORCH && hasFlash) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        wantVideo?.let {
            r.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.aeRange.first, it.aeRange.second))
            r.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it.stabilisation)
            if (info?.hasAwbLock == true) r.set(CaptureRequest.CONTROL_AWB_LOCK, awbLock)
        }
        applyPro(r, still = false)
        try {
            s.setRepeatingRequest(r.build(), previewCallback, handler)
        } catch (_: Exception) {
            // the session is closing; the next open() starts fresh
        }
    }

    private fun aeMode(f: Flash) = when {
        !hasFlash -> CaptureRequest.CONTROL_AE_MODE_ON
        f == Flash.AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
        f == Flash.ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        else -> CaptureRequest.CONTROL_AE_MODE_ON
    }

    // ---- photos ----

    private fun begin(shot: Shot) {
        val r = request
        val s = session
        if (r == null || s == null || reader == null || pending != null) {
            done?.complete(Unit) // nothing to wait for
            done = null
            return
        }
        pending = shot
        capturing = true
        val token = ++shotToken
        handler.postDelayed({ if (token == shotToken && pending != null) finishShot("The photo took too long") }, 10_000)
        if (hasFlash && (shot.flash == Flash.AUTO || shot.flash == Flash.ON)) {
            phase = Phase.PRECAPTURE
            deadline = SystemClock.uptimeMillis() + 2500
            try {
                r.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                s.capture(r.build(), previewCallback, handler)
                r.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
            } catch (_: Exception) {
                captureStill()
            }
        } else {
            captureStill()
        }
    }

    /** Watches the preview frames: while a flash photo waits for the metering to finish, this decides when to shoot. */
    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            watch(result)
            val seen = when (result.get(CaptureResult.CONTROL_AF_STATE)) {
                CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN, CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> Focus.SEARCHING
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> Focus.FOCUSED
                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> Focus.FAILED
                else -> Focus.IDLE
            }
            if (seen != focus) focus = seen
            if (pending == null || phase == Phase.NONE) return
            val ae = result.get(CaptureResult.CONTROL_AE_STATE)
            if (SystemClock.uptimeMillis() > deadline) return captureStill()
            when (phase) {
                Phase.PRECAPTURE -> when (ae) {
                    null, CaptureResult.CONTROL_AE_STATE_PRECAPTURE, CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> phase = Phase.WAIT_DONE
                    CaptureResult.CONTROL_AE_STATE_CONVERGED -> captureStill() // enough light: no pre-flash needed
                    else -> Unit
                }
                Phase.WAIT_DONE -> if (ae == null || ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) captureStill()
                Phase.NONE -> Unit
            }
        }
    }

    private fun captureStill() {
        phase = Phase.NONE
        val shot = pending ?: return
        val cam = device ?: return finishShot("The camera is not ready")
        val out = reader ?: return finishShot("The camera is not ready")
        val s = session ?: return finishShot("The camera is not ready")
        try {
            val rawOut = rawReader?.takeIf { shot.format.wantsRaw }
            needRaw = rawOut != null
            needJpeg = rawOut == null || shot.format.wantsJpeg
            rawResult = null
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                if (needJpeg) addTarget(out.surface)
                rawOut?.let { addTarget(it.surface) }
                common(this)
                set(CaptureRequest.CONTROL_AE_MODE, aeMode(shot.flash))
                set(CaptureRequest.FLASH_MODE, if (shot.flash == Flash.TORCH && hasFlash) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                set(CaptureRequest.JPEG_ORIENTATION, shot.jpegOrientation)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                if (qualityNoise) set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                if (qualityEdge) set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                shot.location?.let { set(CaptureRequest.JPEG_GPS_LOCATION, it) }
                applyPro(this, still = true)
            }
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    listener?.onShutter()
                }

                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (needRaw) { rawResult = result; tryDng() }
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    finishShot("The camera could not take the photo")
                }
            }, handler)
        } catch (e: Exception) {
            finishShot(e.message ?: "The camera could not take the photo")
        }
    }

    private fun onImage(r: ImageReader) {
        val image = try { r.acquireNextImage() } catch (_: Exception) { null } ?: return
        val shot = pending
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            if (shot != null && needJpeg) listener?.onPhoto(Photo(bytes, format, shot.jpegOrientation, mirrored = wantFacing == Facing.FRONT && wantPrefs.mirrorSelfie && format == StillFormat.JPEG, squareCrop = shot.squareCrop))
        } finally {
            image.close()
        }
        needJpeg = false
        if (!needRaw) finishShot(null)
    }

    /** The RAW picture arrived; it waits for the result that describes it (a DNG needs both). */
    private fun onRawImage(r: ImageReader) {
        val image = try { r.acquireNextImage() } catch (_: Exception) { null } ?: return
        if (!needRaw || pending == null) return image.close()
        rawImage?.close()
        rawImage = image
        tryDng()
    }

    /** Once the RAW picture and its capture result are both here, writes the DNG and hands it over like a photo. */
    private fun tryDng() {
        val image = rawImage ?: return
        val result = rawResult ?: return
        val shot = pending
        val characteristics = chars
        rawImage = null
        rawResult = null
        needRaw = false
        try {
            if (shot != null && characteristics != null) {
                val out = java.io.ByteArrayOutputStream(image.width * image.height * 2 + 65536)
                DngCreator(characteristics, result).use { dng ->
                    dng.setOrientation(PhotoRules.exifOrientation(shot.jpegOrientation, mirrored = wantFacing == Facing.FRONT && wantPrefs.mirrorSelfie))
                    shot.location?.let { dng.setLocation(it) }
                    dng.writeImage(out, image)
                }
                listener?.onPhoto(Photo(out.toByteArray(), StillFormat.DNG, shot.jpegOrientation, mirrored = false, squareCrop = false))
            }
        } catch (e: Exception) {
            listener?.onFailed("Could not write the RAW file")
        } finally {
            image.close()
        }
        if (!needJpeg) finishShot(null)
    }

    private fun finishShot(error: String?) {
        pending = null
        needJpeg = false
        needRaw = false
        try { rawImage?.close() } catch (_: Exception) { }
        rawImage = null
        rawResult = null
        phase = Phase.NONE
        capturing = false
        if (error != null) listener?.onFailed(error)
        done?.complete(Unit)
        done = null
    }

    private fun imageFormat(f: StillFormat) = when (f) {
        StillFormat.JPEG -> ImageFormat.JPEG
        StillFormat.JPEG_R -> ImageFormat.JPEG_R
        StillFormat.HEIC -> ImageFormat.HEIC
        StillFormat.DNG -> ImageFormat.RAW_SENSOR
    }

    // ---- errors and shutdown ----

    /** Waits for the other app to let go, then opens again by itself. */
    private fun busy() {
        state = State.Error(Kind.BUSY)
        try {
            manager.registerAvailabilityCallback(executor, availability)
        } catch (_: Exception) {
            // without the watcher the screen's TRY AGAIN still works
        }
    }

    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(id: String) {
            val s = state
            if (id == cameraId && s is State.Error && s.kind == Kind.BUSY) start()
        }
    }

    private fun fail(kind: Kind, detail: String = "") { state = State.Error(kind, detail) }

    private fun stop(keepHs: Boolean = false) {
        generation++
        pending = null
        phase = Phase.NONE
        capturing = false
        done?.complete(Unit)
        done = null
        focus = Focus.IDLE
        try { manager.unregisterAvailabilityCallback(availability) } catch (_: Exception) { }
        try { session?.close() } catch (_: Exception) { }
        try { device?.close() } catch (_: Exception) { }
        try { surface?.release() } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
        try { rawImage?.close() } catch (_: Exception) { }
        try { rawReader?.close() } catch (_: Exception) { }
        rawImage = null
        rawResult = null
        rawReader = null
        if (!keepHs) {
            hsRecord = null
            hsCameraId = null
            hsReady?.let { hsReady = null; it(false) }
        }
        hsStopped?.let { hsStopped = null; it() } // the camera is closed: nothing writes into the encoder any more
        session = null
        device = null
        request = null
        surface = null
        reader = null
    }

    /** The largest RAW_SENSOR size, or null when the camera has no RAW. */
    private fun rawSizeOf(chars: CameraCharacteristics, map: android.hardware.camera2.params.StreamConfigurationMap): Pair<Int, Int>? {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps || !map.isOutputSupportedFor(ImageFormat.RAW_SENSOR)) return null
        return map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }?.let { it.width to it.height }
    }

    /** What the camera allows for manual control (read when the camera opens). A control the camera cannot do gets an empty range. */
    private fun proCaps(chars: CameraCharacteristics, map: android.hardware.camera2.params.StreamConfigurationMap): ProCaps {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val manual = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val iso = if (manual) chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) else null
        val exposure = if (manual) chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null
        val post = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps
        val m1 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        val m2 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2) ?: m1
        fun rows(t: android.hardware.camera2.params.ColorSpaceTransform) = List(9) { t.getElement(it % 3, it / 3).toFloat() }
        val ill1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
        val ill2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: ill1
        val cal = if (m1 != null && m2 != null && ill1 != null && ill2 != null) ProRules.ColorCal(rows(m1), rows(m2), ill1, ill2) else null
        return ProCaps(
            isoMin = iso?.lower ?: 0, isoMax = iso?.upper ?: 0, exposureMinNs = exposure?.lower ?: 0, exposureMaxNs = exposure?.upper ?: 0,
            minFocusDiopters = if (manual) chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f else 0f,
            awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty(), manualPost = post,
            rawSize = rawSizeOf(chars, map),
            faceDetect = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) == true,
            colorCal = cal,
        )
    }

    private val capsCache = HashMap<Facing, VideoCaps>()

    /** What the camera of [facing] can do for video; asked from the system once, so the quality picker can be built before the camera opens. */
    fun videoCapsFor(facing: Facing): VideoCaps? = synchronized(capsCache) {
        capsCache[facing] ?: try {
            val chars = manager.getCameraCharacteristics(pickCamera(facing) ?: return null)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            videoCaps(chars, map, map.getOutputSizes(SurfaceTexture::class.java).map { it.width to it.height }).also { capsCache[facing] = it }
        } catch (_: Exception) {
            null
        }
    }

    /** What this camera can do for video, from its characteristics (read on the camera thread when the camera opens). */
    private fun videoCaps(chars: CameraCharacteristics, map: android.hardware.camera2.params.StreamConfigurationMap, sizes: List<Pair<Int, Int>>): VideoCaps {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().map { it.lower to it.upper }
        val fast = HashMap<Pair<Int, Int>, Set<Int>>()
        for (size in map.highSpeedVideoSizes.orEmpty()) {
            // a fixed range (120..120) is what a high-speed request uses
            fast[size.width to size.height] = map.getHighSpeedVideoFpsRangesFor(size).filter { it.lower == it.upper }.map { it.upper }.toSet()
        }
        val stab = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toSet().orEmpty()
        val tenBit = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles
            ?.filter { it == Hdr.HLG10.camera || it == Hdr.HDR10.camera }?.toSet().orEmpty()
        return VideoCaps(sizes.toSet(), ranges, fast, stab, tenBit)
    }

    private fun pickCamera(facing: Facing): String? {
        val want = if (facing == Facing.BACK) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        val ids = manager.cameraIdList.filter { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
        // the rear logical camera is the one that merges all rear lenses (zoom 0.67 to 20 on the OnePlus 15)
        return if (facing == Facing.BACK) ids.maxByOrNull { manager.getCameraCharacteristics(it).physicalCameraIds.size } else ids.firstOrNull()
    }

    private companion object {
        const val ERROR_IN_USE = CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
        const val ERROR_MAX_IN_USE = CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
        const val ERROR_DISABLED = CameraDevice.StateCallback.ERROR_CAMERA_DISABLED
    }
}
