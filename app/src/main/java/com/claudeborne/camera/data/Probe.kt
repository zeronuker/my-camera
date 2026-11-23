package com.claudeborne.camera.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** What the probe found: the JSON that is saved, and short lines for the Diagnostics screen. */
class ProbeReport(val json: JSONObject, val lines: List<String>)

/**
 * Hardware probe (PLAN.md, Phase 0): asks the phone what its cameras, codecs and camera apps can do.
 * Every section is guarded on its own, so one failing API shows up as an error entry and never stops the rest.
 */
object Probe {
    private val FORMATS = listOf(
        "JPEG" to ImageFormat.JPEG, "HEIC" to ImageFormat.HEIC, "JPEG_R" to ImageFormat.JPEG_R,
        "RAW_SENSOR" to ImageFormat.RAW_SENSOR, "YUV_420_888" to ImageFormat.YUV_420_888, "PRIVATE" to ImageFormat.PRIVATE,
    )

    /** Runs everything. Call off the main thread. [live] also opens cameras (needs the camera permission). */
    fun collect(context: Context, live: Boolean, progress: (String) -> Unit): ProbeReport {
        val lines = mutableListOf<String>()
        val root = JSONObject()
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        guard(root, "device") { device(context) }
        lines += "DEVICE ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        progress("CAMERAS")
        guard(root, "cameras") {
            val listed = cm.cameraIdList.toList()
            val all = LinkedHashSet(listed)
            listed.forEach { all += cm.getCameraCharacteristics(it).physicalCameraIds }
            val arr = JSONArray()
            for (id in all) {
                val o = JSONObject()
                guard(o, "info") { camera(cm, id, id in listed) }
                arr.put(o.opt("info"))
                val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull()
                lines += "CAMERA $id ${ProbeNames.facing(c?.get(CameraCharacteristics.LENS_FACING))}" +
                    (if (id !in listed) " (physical, hidden)" else "") +
                    " f=${c?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString("/")}mm" +
                    " zoom=${c?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)}"
            }
            arr
        }

        progress("EXTENSIONS")
        guard(root, "extensions") {
            val o = JSONObject()
            for (id in cm.cameraIdList) {
                val e = extensions(cm, id)
                o.put(id, e)
                lines += "EXTENSIONS camera $id: ${e.optJSONArray("supported")?.let { a -> (0 until a.length()).joinToString { a.getString(it) } }.orEmpty().ifEmpty { "none" }}"
            }
            o
        }

        progress("CONCURRENT")
        guard(root, "concurrentCameraIds") {
            val sets = cm.concurrentCameraIds
            lines += "CONCURRENT SETS ${sets.size}: " + sets.joinToString(" ") { it.sorted().joinToString(",", "[", "]") }
            JSONArray(sets.map { JSONArray(it.sorted()) })
        }

        if (live) {
            guard(root, "liveTests") {
                val o = LiveTests(cm).run(progress)
                for (k in o.keys()) lines += "LIVE $k: ${o.getString(k)}"
                o
            }
        } else {
            root.put("liveTests", "skipped: camera permission not granted")
            lines += "LIVE TESTS skipped (camera permission not granted)"
        }

        progress("ENCODERS")
        guard(root, "videoEncoders") {
            val a = encoders()
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                lines += "ENCODER ${e.getString("name")} ${e.getString("type")} max ${e.getString("max")} 8K30=${e.optBoolean("8k30")} 4K120=${e.optBoolean("4k120")} 10bit=${e.optBoolean("tenBit")}"
            }
            a
        }
        // Dolby Vision is only reported, never offered (PLAN.md Phase 2): is there an encoder for video/dolby-vision at all?
        guard(root, "dolbyVisionEncoders") {
            val names = com.claudeborne.camera.video.Encoders.dolbyVisionEncoders()
            lines += "DOLBY VISION ENCODERS ${if (names.isEmpty()) "none" else names.joinToString()}"
            JSONArray(names)
        }

        progress("APPS")
        guard(root, "intentHandlers") {
            val o = intents(context)
            for (k in o.keys()) lines += "INTENT ${k.substringAfterLast('.')} default=${o.getJSONObject(k).optString("default")}"
            o
        }
        return ProbeReport(root, lines)
    }

    private fun device(context: Context): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("device", Build.DEVICE)
        .put("androidRelease", Build.VERSION.RELEASE).put("sdkInt", Build.VERSION.SDK_INT)
        .put("securityPatch", Build.VERSION.SECURITY_PATCH).put("buildDisplay", Build.DISPLAY)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        .put("app", context.packageName)
        .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" })
        .put("time", Date().toString())

    private fun sizeText(s: Size) = "${s.width}x${s.height}"

    private fun camera(cm: CameraManager, id: String, listed: Boolean): JSONObject {
        val c = cm.getCameraCharacteristics(id)
        val o = JSONObject().put("id", id).put("listedByCameraIdList", listed)
        o.put("facing", ProbeNames.facing(c.get(CameraCharacteristics.LENS_FACING)))
        o.put("physicalIds", JSONArray(c.physicalCameraIds.sorted()))
        o.put("focalLengthsMm", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let { a -> JSONArray(a.toList()) })
        o.put("apertures", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.let { a -> JSONArray(a.toList()) })
        o.put("sensorSizeMm", c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.toString())
        o.put("pixelArray", c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let(::sizeText))
        o.put("activeArray", c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toShortString())
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        o.put("minFocusDistanceDiopters", minFocus)
        o.put("minFocusDistanceCm", if (minFocus != null && minFocus > 0f) 100.0 / minFocus else null)
        o.put("hardwareLevel", ProbeNames.hardwareLevel(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))
        o.put("capabilities", JSONArray(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.map(ProbeNames::capability).orEmpty()))
        o.put("zoomRatioRange", c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.toString())
        o.put("videoStabilizationModes", c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.let { a -> JSONArray(a.map { stab(it) }) })
        o.put("oisModes", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.let { a -> JSONArray(a.map { if (it == 1) "ON" else "OFF" }) })
        o.put("aeFpsRanges", JSONArray(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.toString() }.orEmpty()))
        o.put("tonemapModes", c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.let { a -> JSONArray(a.toList()) })
        o.put("faceDetectModes", c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.let { a -> JSONArray(a.toList()) })
        o.put("afModes", c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.let { a -> JSONArray(a.toList()) })
        o.put("flashUnit", c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
        o.put("dynamicRangeProfiles", JSONArray(c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.sorted()?.map(ProbeNames::dynamicRange).orEmpty()))

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            val sizes = JSONObject()
            for ((name, fmt) in FORMATS) {
                if (map.isOutputSupportedFor(fmt)) sizes.put(name, JSONArray(map.getOutputSizes(fmt).orEmpty().sortedByDescending { it.width.toLong() * it.height }.map(::sizeText)))
            }
            o.put("outputSizes", sizes)
            o.put("highResolutionJpeg", JSONArray(map.getHighResolutionOutputSizes(ImageFormat.JPEG).orEmpty().map(::sizeText)))
            val hs = JSONObject()
            for (s in map.highSpeedVideoSizes) hs.put(sizeText(s), JSONArray(map.getHighSpeedVideoFpsRangesFor(s).map { it.toString() }))
            o.put("highSpeedVideo", hs)
        }
        val max = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        if (max != null) {
            val sizes = JSONObject()
            for ((name, fmt) in FORMATS) {
                if (max.isOutputSupportedFor(fmt)) sizes.put(name, JSONArray(max.getOutputSizes(fmt).orEmpty().sortedByDescending { it.width.toLong() * it.height }.map(::sizeText)))
            }
            o.put("maxResolutionOutputSizes", sizes)
        }
        return o
    }

    private fun stab(mode: Int) = when (mode) { 0 -> "OFF"; 1 -> "ON"; 2 -> "PREVIEW_STABILIZATION"; else -> "UNKNOWN_$mode" }

    private fun extensions(cm: CameraManager, id: String): JSONObject {
        val ext = cm.getCameraExtensionCharacteristics(id)
        val o = JSONObject()
        val supported = ext.supportedExtensions
        o.put("supported", JSONArray(supported.map(ProbeNames::extension)))
        for (e in supported) {
            val d = JSONObject()
            guard(d, "captureJpegSizes") { JSONArray(ext.getExtensionSupportedSizes(e, ImageFormat.JPEG).map(::sizeText)) }
            guard(d, "previewSizes") { JSONArray(ext.getExtensionSupportedSizes(e, SurfaceTexture::class.java).map(::sizeText)) }
            o.put(ProbeNames.extension(e), d)
        }
        return o
    }

    private fun encoders(): JSONArray {
        val out = JSONArray()
        val checks = listOf("1080p60" to Triple(1920, 1080, 60.0), "4k30" to Triple(3840, 2160, 30.0), "4k60" to Triple(3840, 2160, 60.0),
            "4k120" to Triple(3840, 2160, 120.0), "8k30" to Triple(7680, 4320, 30.0), "8k60" to Triple(7680, 4320, 60.0))
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes.filter { it == "video/hevc" || it == "video/avc" }) {
                val caps = info.getCapabilitiesForType(type)
                val v = caps.videoCapabilities ?: continue
                val o = JSONObject().put("name", info.name).put("type", type).put("hardware", info.isHardwareAccelerated)
                o.put("max", "${v.supportedWidths.upper}x${v.supportedHeights.upper}")
                o.put("bitrateRange", v.bitrateRange.toString())
                for ((k, t) in checks) o.put(k, v.areSizeAndRateSupported(t.first, t.second, t.third))
                val profiles = caps.profileLevels.map { it.profile }.distinct().sorted()
                o.put("profiles", JSONArray(profiles))
                // HEVC Main10 = 2, Main10 HDR10 = 4096, Main10 HDR10+ = 8192
                o.put("tenBit", type == "video/hevc" && profiles.any { it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 })
                out.put(o)
            }
        }
        return out
    }

    private fun intents(context: Context): JSONObject {
        val pm = context.packageManager
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        val o = JSONObject()
        for (action in listOf(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE,
            MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.INTENT_ACTION_VIDEO_CAMERA, MediaStore.ACTION_VIDEO_CAPTURE)) {
            val i = Intent(action)
            val e = JSONObject()
            e.put("handlers", JSONArray(pm.queryIntentActivities(i, flags).map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }))
            e.put("default", pm.resolveActivity(i, flags)?.activityInfo?.let { "${it.packageName}/${it.name}" })
            o.put(action, e)
        }
        return o
    }

    private inline fun guard(into: JSONObject, name: String, f: () -> Any?) {
        try { into.put(name, f()) } catch (e: Throwable) { into.put(name, JSONObject().put("error", e.toString())) }
    }

    /** Saves the report as `Download/camera-probe.json` and keeps a copy in the cache for sharing. Returns the cache copy. */
    fun save(context: Context, report: ProbeReport): File {
        val text = report.json.toString(2)
        val copy = File(context.cacheDir, "share").apply { mkdirs() }.resolve("camera-probe.json").also { it.writeText(text) }
        val resolver = context.contentResolver
        val where = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, where, arrayOf("camera-probe.json", "Download/")) // replace an older report of this app
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "camera-probe.json")
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("could not create the file in Download")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: throw java.io.IOException("could not write the file")
        return copy
    }
}

/** Opens real cameras and tries the stream combinations the later phases need. Each result is "OK" or the exact error. */
private class LiveTests(private val cm: CameraManager) {
    private val thread = HandlerThread("probe").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val readers = mutableListOf<ImageReader>()
    private val sessions = mutableListOf<CameraCaptureSession>()
    private val devices = mutableListOf<CameraDevice>()

    fun run(progress: (String) -> Unit): JSONObject {
        val o = JSONObject()
        try {
            val ids = cm.cameraIdList.toList()
            fun facing(id: String) = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            val front = ids.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_FRONT }
            val rearLogical = ids.filter { facing(it) == CameraCharacteristics.LENS_FACING_BACK }
                .maxByOrNull { cm.getCameraCharacteristics(it).physicalCameraIds.size }
            val physical = rearLogical?.let { cm.getCameraCharacteristics(it).physicalCameraIds.sorted() }.orEmpty()
            o.put("rearLogicalCamera", "$rearLogical physical=$physical front=$front")

            if (rearLogical != null && physical.size >= 2) {
                progress("LIVE: rear, 2 physical streams")
                o.put("rear_2_physical_streams", single(rearLogical) { configure(it, physicalOutputs(physical.take(2))) })
            } else o.put("rear_2_physical_streams", "not applicable: no rear logical camera with 2+ physical cameras")
            if (rearLogical != null && physical.size >= 3) {
                progress("LIVE: rear, 3 physical streams")
                o.put("rear_3_physical_streams", single(rearLogical) { configure(it, physicalOutputs(physical.take(3))) })
            } else o.put("rear_3_physical_streams", "not applicable: fewer than 3 physical rear cameras")

            if (front != null && rearLogical != null) {
                progress("LIVE: front + rear together")
                o.put("front_plus_rear", both(front, rearLogical) { listOf(plainOutput(it)) })
                if (physical.size >= 3) {
                    progress("LIVE: front + 3 rear")
                    o.put("front_plus_3_rear", both(front, rearLogical) { physicalOutputs(physical.take(3)) })
                }
            } else o.put("front_plus_rear", "not applicable: need a front and a rear camera")
        } finally {
            release()
            thread.quitSafely()
        }
        return o
    }

    private fun plainOutput(id: String): OutputConfiguration = OutputConfiguration(reader(smallest(id)).surface)

    private fun physicalOutputs(ids: List<String>) = ids.map { pid ->
        OutputConfiguration(reader(smallest(pid)).surface).apply { setPhysicalCameraId(pid) }
    }

    private fun smallest(id: String): Size =
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.YUV_420_888).minByOrNull { it.width.toLong() * it.height }!!

    private fun reader(s: Size) = ImageReader.newInstance(s.width, s.height, ImageFormat.YUV_420_888, 2).also { readers += it }

    /** Opens one camera, runs [test] on it, closes everything again. */
    private fun single(id: String, test: (CameraDevice) -> String): String {
        val (dev, err) = open(id)
        if (dev == null) return "open failed: $err"
        val r = test(dev)
        release()
        return r
    }

    /** Opens [a] and [b] together, then holds one session on each at the same time. [outputsForB] gives b's streams. */
    private fun both(a: String, b: String, outputsForB: (String) -> List<OutputConfiguration>): String {
        val (da, ea) = open(a)
        if (da == null) return "open $a failed: $ea"
        val (db, eb) = open(b)
        if (db == null) { release(); return "open $b failed while $a was open: $eb" }
        val ra = configure(da, listOf(plainOutput(a)))
        val rb = configure(db, outputsForB(b))
        release()
        return if (ra == "OK" && rb == "OK") "OK" else "front/first: $ra; rear/second: $rb"
    }

    private fun open(id: String): Pair<CameraDevice?, String?> {
        val latch = CountDownLatch(1)
        val dev = AtomicReference<CameraDevice?>()
        val err = AtomicReference<String?>()
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { dev.set(camera); latch.countDown() }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); err.set("disconnected"); latch.countDown() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); err.set(ProbeNames.deviceError(error)); latch.countDown() }
            }, handler)
        } catch (e: Exception) {
            return null to e.toString()
        }
        if (!latch.await(6, TimeUnit.SECONDS)) return null to "open timed out"
        dev.get()?.let { devices += it }
        return dev.get() to err.get()
    }

    /** Creates a session with [outs] and keeps it open (closed by [release]). */
    private fun configure(dev: CameraDevice, outs: List<OutputConfiguration>): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference("timed out")
        try {
            dev.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { sessions += session; result.set("OK"); latch.countDown() }
                override fun onConfigureFailed(session: CameraCaptureSession) { result.set("CONFIGURE_FAILED"); latch.countDown() }
            }))
        } catch (e: Exception) {
            return e.toString()
        }
        latch.await(6, TimeUnit.SECONDS)
        return result.get()
    }

    private fun release() {
        sessions.forEach { runCatching { it.close() } }; sessions.clear()
        devices.forEach { runCatching { it.close() } }; devices.clear()
        readers.forEach { runCatching { it.close() } }; readers.clear()
    }
}
