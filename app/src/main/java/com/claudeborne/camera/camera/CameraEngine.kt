package com.claudeborne.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.location.Location
import android.media.ImageReader
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
    )

    /** What the focus is doing right now, for the colour of the focus box. */
    enum class Focus { IDLE, SEARCHING, FOCUSED, FAILED }

    /** The user's format choices when the camera opens; the engine picks what this camera can really do. */
    class StillPrefs(val heif: Boolean, val ultraHdr: Boolean, val mirrorSelfie: Boolean)

    /** One photo to take. */
    class Shot(val flash: Flash, val jpegOrientation: Int, val location: Location?, val squareCrop: Boolean = false)

    class Photo(val bytes: ByteArray, val format: StillFormat, val jpegOrientation: Int, val mirrored: Boolean, val squareCrop: Boolean)

    /** Called on the camera thread. */
    interface Listener {
        fun onShutter()
        fun onPhoto(photo: Photo)
        fun onFailed(message: String)
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

    private enum class Phase { NONE, PRECAPTURE, WAIT_DONE }
    private var phase = Phase.NONE
    private var deadline = 0L
    private var pending: Shot? = null
    private var shotToken = 0

    /** Opens the camera for [facing] and streams into [texture]; closes whatever was open before. Returns at once. */
    fun open(facing: Facing, texture: SurfaceTexture, screenAspect: Float, prefs: StillPrefs) {
        handler.post {
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
        b.set(CaptureRequest.CONTROL_AF_MODE, if (locked) CaptureRequest.CONTROL_AF_MODE_AUTO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        val rect = focusRect?.let { arrayOf(it) }
        if (afRegions) b.set(CaptureRequest.CONTROL_AF_REGIONS, rect)
        if (aeRegions) b.set(CaptureRequest.CONTROL_AE_REGIONS, rect)
    }

    private fun start() {
        stop()
        val texture = wantTexture ?: return
        val mine = generation
        try {
            val id = pickCamera(wantFacing) ?: return fail(Kind.FAILED, "no ${wantFacing.name.lowercase()} camera")
            val chars = manager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val sizes = map.getOutputSizes(SurfaceTexture::class.java).map { it.width to it.height }
            val (w, h) = choosePreviewSize(sizes) ?: return fail(Kind.FAILED, "no preview size")
            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            qualityNoise = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true
            qualityEdge = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY) == true

            fun stillSizes(f: StillFormat) = if (map.isOutputSupportedFor(imageFormat(f))) map.getOutputSizes(imageFormat(f)).orEmpty().map { it.width to it.height } else emptyList()
            val supported = StillFormat.entries.filter { stillSizes(it).isNotEmpty() }.toSet()
            format = PhotoRules.chooseFormat(wantFacing == Facing.FRONT, wantPrefs.mirrorSelfie, wantPrefs.heif, wantPrefs.ultraHdr, supported)
            val (sw, sh) = PhotoRules.chooseStillSize(stillSizes(format), wantAspect) ?: return fail(Kind.FAILED, "no photo size")
            reader = ImageReader.newInstance(sw, sh, imageFormat(format), 2).also { it.setOnImageAvailableListener(::onImage, handler) }

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
            )
            texture.setDefaultBufferSize(w, h)
            surface = Surface(texture)
            state = State.Opening
            // a camera that never answers must end in a message, not a frozen black viewfinder
            handler.postDelayed({ if (mine == generation && state == State.Opening) fail(Kind.FAILED, "the camera did not answer") }, 8000)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (mine != generation) return camera.close()
                    device = camera
                    configure(camera, mine)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (mine != generation) return
                    fail(Kind.DISCONNECTED)
                    if (retries++ < 3) handler.postDelayed({ if (mine == generation) start() }, 700)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (mine != generation) return
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
        val preview = surface ?: return
        val still = reader?.surface ?: return
        try {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(preview), OutputConfiguration(still)), executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (mine != generation) return s.close()
                            session = s
                            request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(preview)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                            retries = 0
                            applyRequest()
                            state = State.Streaming
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            if (mine == generation) fail(Kind.FAILED, "could not start the preview")
                        }
                    },
                ),
            )
        } catch (e: Exception) {
            fail(Kind.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun applyRequest() {
        val r = request ?: return
        val s = session ?: return
        common(r)
        r.set(CaptureRequest.CONTROL_AE_MODE, aeMode(flash))
        r.set(CaptureRequest.FLASH_MODE, if (flash == Flash.TORCH && hasFlash) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
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
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(out.surface)
                common(this)
                set(CaptureRequest.CONTROL_AE_MODE, aeMode(shot.flash))
                set(CaptureRequest.FLASH_MODE, if (shot.flash == Flash.TORCH && hasFlash) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                set(CaptureRequest.JPEG_ORIENTATION, shot.jpegOrientation)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                if (qualityNoise) set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                if (qualityEdge) set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                shot.location?.let { set(CaptureRequest.JPEG_GPS_LOCATION, it) }
            }
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    listener?.onShutter()
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
            if (shot != null) listener?.onPhoto(Photo(bytes, format, shot.jpegOrientation, mirrored = wantFacing == Facing.FRONT && wantPrefs.mirrorSelfie && format == StillFormat.JPEG, squareCrop = shot.squareCrop))
        } finally {
            image.close()
        }
        finishShot(null)
    }

    private fun finishShot(error: String?) {
        pending = null
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

    private fun stop() {
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
        session = null
        device = null
        request = null
        surface = null
        reader = null
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
