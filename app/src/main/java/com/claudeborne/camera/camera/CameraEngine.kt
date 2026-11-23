package com.claudeborne.camera.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Opens one camera and streams it into a [SurfaceTexture]. All Camera2 work runs on its own thread, never the main one.
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

    /** What the screen needs to draw the picture and the zoom controls for the camera that is open. */
    class Info(val facing: Facing, val sensorOrientation: Int, val previewWidth: Int, val previewHeight: Int, val zoomMin: Float, val zoomMax: Float)

    var state: State by mutableStateOf(State.Idle)
        private set
    var info: Info? by mutableStateOf(null)
        private set

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    // everything below is touched on the camera thread only (except zoom)
    private var generation = 0 // callbacks of an older open() see a different number and are ignored
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var surface: Surface? = null
    private var wantFacing = Facing.BACK
    private var wantTexture: SurfaceTexture? = null
    private var cameraId: String? = null
    private var retries = 0
    @Volatile private var zoom = 1f

    /** Opens the camera for [facing] and streams into [texture]; closes whatever was open before. Returns at once. */
    fun open(facing: Facing, texture: SurfaceTexture) {
        handler.post {
            wantFacing = facing
            wantTexture = texture
            retries = 0
            start()
        }
    }

    /** Called by "TRY AGAIN" on an error screen. */
    fun retry() {
        handler.post { retries = 0; if (wantTexture != null) start() }
    }

    /** Releases the camera and waits (briefly) until it is really free. */
    fun close() {
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

    private fun start() {
        stop()
        val texture = wantTexture ?: return
        val mine = generation
        try {
            val id = pickCamera(wantFacing) ?: return fail(Kind.FAILED, "no ${wantFacing.name.lowercase()} camera")
            val chars = manager.getCameraCharacteristics(id)
            val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(SurfaceTexture::class.java).map { it.width to it.height }
            val (w, h) = choosePreviewSize(sizes) ?: return fail(Kind.FAILED, "no preview size")
            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            cameraId = id
            info = Info(wantFacing, chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, w, h, zoomRange?.lower ?: 1f, zoomRange?.upper ?: 1f)
            texture.setDefaultBufferSize(w, h)
            surface = Surface(texture)
            state = State.Opening
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
        val target = surface ?: return
        try {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(target)), executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (mine != generation) return s.close()
                            session = s
                            request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(target)
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
        val range = info ?: return
        r.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(range.zoomMin, range.zoomMax))
        try {
            s.setRepeatingRequest(r.build(), null, handler)
        } catch (_: Exception) {
            // the session is closing; the next open() starts fresh
        }
    }

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
        try { manager.unregisterAvailabilityCallback(availability) } catch (_: Exception) { }
        try { session?.close() } catch (_: Exception) { }
        try { device?.close() } catch (_: Exception) { }
        try { surface?.release() } catch (_: Exception) { }
        session = null
        device = null
        request = null
        surface = null
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
