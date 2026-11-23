package com.claudeborne.camera.camera

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.SurfaceTexture
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
import com.claudeborne.camera.dual.DualRules
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** How one camera stream of a multi-camera session is turned and sized, for cropping it into its tile. [front] marks the selfie camera. */
class SourceInfo(val sensor: Int, val width: Int, val height: Int, val front: Boolean)

/**
 * Dual and Quad view: the rear camera and the front camera streaming at the same time (the probe lists them as a concurrent pair).
 * [open] with one rear stream uses the logical rear camera (the normal 1x picture); with two or three it asks the logical rear camera for
 * its physical lenses (ultra wide, main, tele) as separate streams. Real simultaneous streams only: nothing here switches cameras to imitate it.
 * Any refusal ends in [open]'s callback with a message, never a crash and never a frozen picture.
 */
class MultiCameraEngine(private val context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera-multi").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private class Open(val id: String, val streams: List<Triple<SurfaceTexture, String?, Pair<Int, Int>>>) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var surfaces: List<Surface> = emptyList()
    }

    private var generation = 0
    private var opens: List<Open> = emptyList()

    /** The camera ids and what they can do, or null when this phone cannot stream the rear and the front camera together. */
    class Cameras(val rear: String, val front: String, val physical: List<String>)

    fun cameras(): Cameras? = try {
        val ids = manager.cameraIdList.toList()
        fun facing(id: String) = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        val rear = ids.filter { facing(it) == CameraCharacteristics.LENS_FACING_BACK }.maxByOrNull { manager.getCameraCharacteristics(it).physicalCameraIds.size }
        val front = ids.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_FRONT }
        if (rear == null || front == null) null else {
            // the phone must list them as a concurrent pair; a debuggable build tries anyway so the emulator can show the layouts
            val pairOk = manager.concurrentCameraIds.any { rear in it && front in it }
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            if (!pairOk && !debuggable) null else {
                val physical = manager.getCameraCharacteristics(rear).physicalCameraIds.sortedBy { focal(it) }
                Cameras(rear, front, physical)
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun focal(id: String): Float = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f

    /**
     * Opens [rearCount] rear streams (1 = the logical camera, 2 or 3 = that many physical lenses from the wide end) and the front camera.
     * [textures] holds [rearCount] + 1 textures: the rear streams in order, then the front. [done] gets the stream infos in the same order, or null and a message.
     * [lost] is called if a camera drops out later.
     */
    fun open(rearCount: Int, textures: List<SurfaceTexture>, done: (List<SourceInfo>?, String?) -> Unit, lost: (String) -> Unit) {
        handler.post {
            stop()
            val mine = ++generation
            var finished = false
            fun finish(infos: List<SourceInfo>?, message: String?) {
                if (finished || mine != generation) return
                finished = true
                if (infos == null) stop()
                done(infos, message)
            }
            try {
                val cams = cameras() ?: return@post finish(null, "This phone cannot use the front and rear cameras together")
                if (textures.size != rearCount + 1) return@post finish(null, "Wrong number of pictures")
                if (rearCount > 1 && cams.physical.size < rearCount) return@post finish(null, "This phone does not offer ${rearCount} rear lenses at once")
                val maxPixels = if (rearCount > 1) 1_000_000 else 1_600_000
                fun pick(id: String): Pair<Int, Int>? {
                    val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
                    return DualRules.chooseSize(map.getOutputSizes(SurfaceTexture::class.java).orEmpty().map { it.width to it.height }, maxPixels, 4f / 3f)
                }
                val infos = ArrayList<SourceInfo>()
                val rearStreams = ArrayList<Triple<SurfaceTexture, String?, Pair<Int, Int>>>()
                if (rearCount == 1) {
                    val size = pick(cams.rear) ?: return@post finish(null, "No picture size for the rear camera")
                    rearStreams += Triple(textures[0], null, size)
                    infos += SourceInfo(manager.getCameraCharacteristics(cams.rear).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, size.first, size.second, false)
                } else {
                    // two lenses: the ultra wide and the main; three: ultra wide, main and tele
                    val lenses = if (rearCount == 2) cams.physical.take(2) else cams.physical.take(3)
                    lenses.forEachIndexed { i, id ->
                        val size = pick(id) ?: return@post finish(null, "No picture size for lens $id")
                        rearStreams += Triple(textures[i], id, size)
                        infos += SourceInfo(manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, size.first, size.second, false)
                    }
                }
                val frontSize = pick(cams.front) ?: return@post finish(null, "No picture size for the front camera")
                infos += SourceInfo(manager.getCameraCharacteristics(cams.front).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270, frontSize.first, frontSize.second, true)
                opens = listOf(Open(cams.rear, rearStreams), Open(cams.front, listOf(Triple(textures.last(), null, frontSize))))

                var ready = 0
                handler.postDelayed({ if (mine == generation && !finished) finish(null, "The cameras did not answer") }, 8000)
                for (o in opens) {
                    o.streams.forEach { (t, _, size) -> t.setDefaultBufferSize(size.first, size.second) }
                    manager.openCamera(o.id, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if (mine != generation) return camera.close()
                            o.device = camera
                            o.surfaces = o.streams.map { Surface(it.first) }
                            val configs = o.streams.mapIndexed { i, s -> OutputConfiguration(o.surfaces[i]).also { c -> s.second?.let(c::setPhysicalCameraId) } }
                            try {
                                camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, configs, executor, object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        if (mine != generation) return session.close()
                                        o.session = session
                                        try {
                                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                                o.surfaces.forEach(::addTarget)
                                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                if (o.id == cams.rear) set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
                                            }
                                            session.setRepeatingRequest(request.build(), null, handler)
                                        } catch (e: Exception) {
                                            return finish(null, "A camera would not start (${e.message ?: e.javaClass.simpleName})")
                                        }
                                        if (++ready == opens.size) finish(infos, null)
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        if (mine == generation) finish(null, "The phone refused to stream these cameras together")
                                    }
                                }))
                            } catch (e: Exception) {
                                finish(null, "The cameras could not be set up (${e.message ?: e.javaClass.simpleName})")
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (mine != generation) return
                            if (finished) { stop(); lost("A camera stopped") } else finish(null, "A camera stopped")
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            if (mine != generation) return
                            val text = if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE || error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) "A camera is busy in another app" else "Camera error $error"
                            if (finished) { stop(); lost(text) } else finish(null, text)
                        }
                    }, handler)
                }
            } catch (e: Exception) {
                finish(null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun stop() {
        generation++
        for (o in opens) {
            runCatching { o.session?.stopRepeating() }
            runCatching { o.session?.close() }
            runCatching { o.device?.close() }
            o.surfaces.forEach { runCatching { it.release() } }
        }
        opens = emptyList()
    }

    /** Releases both cameras and waits (briefly) until they are really free. */
    fun close() {
        val latch = CountDownLatch(1)
        handler.post { stop(); latch.countDown() }
        latch.await(1500, TimeUnit.MILLISECONDS)
    }

    fun release() { thread.quitSafely() }
}
