package com.claudeborne.camera.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.MediaActionSound
import android.net.Uri
import android.opengl.GLSurfaceView
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import com.claudeborne.camera.LaunchKind
import com.claudeborne.camera.LaunchRequest
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.claudeborne.camera.camera.CameraEngine
import com.claudeborne.camera.camera.CameraEngine.Facing
import com.claudeborne.camera.camera.Heat
import com.claudeborne.camera.camera.LevelMath
import com.claudeborne.camera.camera.MasterFormat
import com.claudeborne.camera.camera.Pro
import com.claudeborne.camera.camera.ProRules
import com.claudeborne.camera.camera.Wb
import com.claudeborne.camera.camera.ZoomStops
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.claudeborne.camera.capture.Aspect
import com.claudeborne.camera.capture.AspectMath
import com.claudeborne.camera.capture.Ev
import com.claudeborne.camera.capture.Flash
import com.claudeborne.camera.capture.LastLocation
import com.claudeborne.camera.capture.PhotoRules
import com.claudeborne.camera.capture.PhotoSaver
import com.claudeborne.camera.data.Settings as AppSettings
import com.claudeborne.camera.gl.PreviewMath
import com.claudeborne.camera.gl.PreviewRenderer
import com.claudeborne.camera.capture.StillFormat
import com.claudeborne.camera.video.Encoders
import com.claudeborne.camera.video.Hdr
import com.claudeborne.camera.video.LensZone
import com.claudeborne.camera.video.Res
import com.claudeborne.camera.video.TimeLapse
import com.claudeborne.camera.video.VideoOption
import com.claudeborne.camera.video.VideoQuality
import com.claudeborne.camera.video.VideoRecorder
import com.claudeborne.camera.video.VideoRules
import com.claudeborne.camera.video.VideoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun hasCamera(context: Context) =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun hasMic(context: Context) =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private const val GALLERY = "com.claudeborne.gallerymanager"

/** The volume keys, handed over by the activity while the viewfinder is on show. Returns true when it used the key. */
internal object CameraKeys {
    var handler: ((up: Boolean, first: Boolean) -> Boolean)? = null
    fun handles() = handler != null && AppSettings.volumeAction != 0
}

/**
 * The viewfinder: live preview, flash, focus and exposure, aspect ratio, timer, interval shooting, burst, zoom, and the camera's
 * error screens. The camera is opened only while this screen is on show and released the moment it pauses.
 */
@Composable
fun CameraScreen(launch: LaunchRequest, onResult: (Uri?) -> Unit, onSettings: () -> Unit) {
    SystemBarIcons(darkIcons = false)
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val engine = remember { CameraEngine(context.applicationContext) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    val renderer = remember { PreviewRenderer(requestRender = { glView?.requestRender() }, onTexture = { texture = it }, post = { block -> glView?.queueEvent { block() } }) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var permitted by remember { mutableStateOf(hasCamera(context)) }
    var refused by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(true) }
    var facing by remember { mutableStateOf(if (launch.wantsFront) Facing.FRONT else Facing.BACK) }
    var zoom by remember { mutableFloatStateOf(if (launch.wantsFront) 1f else ZoomStops.wideRatio(AppSettings.defaultWide)) }
    var pinching by remember { mutableStateOf(false) }
    var showedPicture by remember { mutableStateOf(false) }
    var zoomJob by remember { mutableStateOf<Job?>(null) }
    var flash by remember { mutableStateOf(Flash.OFF) }
    var aspect by remember { mutableStateOf(Aspect.FULL) }
    var timerSec by remember { mutableIntStateOf(0) }
    var intervalSec by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(Menu.NONE) }
    var evOpen by remember { mutableStateOf(false) }
    var ev by remember { mutableFloatStateOf(0f) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var runCount by remember { mutableIntStateOf(0) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var bursting by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<Pair<String, Int>?>(null) } // text and a counter, so the same text can show twice in a row
    var screenFlash by remember { mutableStateOf(false) }
    var shutterTick by remember { mutableIntStateOf(0) }
    var deviceDegrees by remember { mutableIntStateOf(0) }
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var heat by remember { mutableStateOf(Heat.OK) }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK); load(MediaActionSound.START_VIDEO_RECORDING); load(MediaActionSound.STOP_VIDEO_RECORDING) } }
    var mode by remember { mutableStateOf(if (launch.wantsVideo) Mode.VIDEO else Mode.PHOTO) }
    val launchNow by rememberUpdatedState(launch)
    val onResultNow by rememberUpdatedState(onResult)
    val sessionShots = remember { mutableStateListOf<SessionShot>() } // lock-screen launch: the only pictures its viewer may show
    var viewerOpen by remember { mutableStateOf(false) }
    val isVideo = mode.isVideo
    val isMaster = mode.isMaster
    val plainVideo = mode == Mode.VIDEO || mode == Mode.PROVIDEO // normal frame rates (no 120/240 fps, no time-lapse, no slo-mo)
    val proMode = (isMaster && AppSettings.proOn) || mode == Mode.PROVIDEO // the manual controls are on show
    var pro by remember { mutableStateOf(Pro()) }
    var proOpen by remember { mutableStateOf<ProParam?>(null) }
    var formatOpen by remember { mutableStateOf(false) }
    var histogramBins by remember { mutableStateOf<IntArray?>(null) }
    var roll by remember { mutableFloatStateOf(0f) }
    var micLevel by remember { mutableFloatStateOf(0f) }
    val masterFormat = MasterFormat.of(AppSettings.masterFormat)
    val wantRaw = isMaster && masterFormat.wantsRaw
    val effectivePro = (if (proMode) pro else Pro()).copy(faceMetering = isMaster && AppSettings.faceMetering)
    var recorder by remember { mutableStateOf<VideoRecorder?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recPaused by remember { mutableStateOf(false) }
    var recMs by remember { mutableLongStateOf(0L) }
    var savingVideo by remember { mutableStateOf(false) }
    var recTurn by remember { mutableIntStateOf(0) }
    var recSize by remember { mutableStateOf(1080 to 1920) }
    var recOption by remember { mutableStateOf<VideoOption?>(null) }
    var recHdr by remember { mutableStateOf(false) }
    var recDirect by remember { mutableStateOf(false) } // high-speed: the camera feeds the encoder directly
    var recLens by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    var focusHeld by remember { mutableStateOf(false) }
    var qualityOpen by remember { mutableStateOf(false) }
    var micAsk by remember { mutableStateOf<Boolean?>(null) } // null: closed, false: ask, true: Android no longer asks (settings)
    val leaveHooks = remember { arrayOf<() -> Unit>({}) } // 0: finish a recording when the screen goes
    fun say(text: String) { toast = text to ((toast?.second ?: 0) + 1) }

    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permitted = ok
        refused = !ok
    }
    LaunchedEffect(Unit) { if (!permitted) askCamera.launch(Manifest.permission.CAMERA) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { micAsk = null; say("Microphone allowed. Tap record.") } else { // the camera was closed while the question was open, so the screen waits for a new tap
            val a = context as? Activity
            micAsk = a != null && !a.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) // true: Android will not ask again
        }
    }

    // the viewfinder band: the whole screen for "Full", otherwise a box under the top buttons that glides between shapes
    val topInset = WindowInsets.safeDrawing.getTop(density)
    val reserve = with(density) { 64.dp.roundToPx() }
    val shape = if (isVideo) Aspect.R16_9 else aspect // video is always the 16:9 shape, as in the stock app
    val targetTop = if (shape == Aspect.FULL) 0 else topInset + reserve
    val targetHeight = if (shape == Aspect.FULL) viewSize.height else AspectMath.boxHeight(shape, viewSize.width, (viewSize.height - targetTop).coerceAtLeast(1))
    val boxTop by animateIntAsState(targetTop, tween(MOTION_MS), label = "box top")
    val boxHeight by animateIntAsState(targetHeight, tween(MOTION_MS), label = "box height")
    LaunchedEffect(boxTop, boxHeight) {
        renderer.boxTop = boxTop
        renderer.boxHeight = boxHeight
        glView?.requestRender()
    }

    // release the camera when the screen pauses (before onPause returns), reopen when it is back
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { permitted = hasCamera(context); resumed = true; glView?.onResume() }
                Lifecycle.Event.ON_PAUSE -> { leaveHooks[0](); resumed = false; showedPicture = false; runJob?.cancel(); engine.close(); texture = null; glView?.onPause() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            leaveHooks[0]() // a recording in progress is finished and saved before the camera goes
            engine.close() // waits for a photo still on its way; the listener below must still be set until then
            engine.listener = null
            engine.release()
            sound.release()
        }
    }
    // the phone's heat: warn, stop bursts when hot, refuse photos when much too hot
    DisposableEffect(Unit) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        heat = Heat.of(power.currentThermalStatus)
        val watch = PowerManager.OnThermalStatusChangedListener { heat = Heat.of(it) }
        power.addThermalStatusListener(ContextCompat.getMainExecutor(context), watch)
        onDispose { power.removeThermalStatusListener(watch) }
    }
    LaunchedEffect(heat) {
        if (heat != Heat.OK && runJob != null) { runJob?.cancel(); say("Stopped: the phone is hot") }
    }
    // video selection: what this camera and lens can do, and what the user picked (kept as it was while recording)
    val front = facing == Facing.FRONT
    val caps = remember(facing, engine) { engine.videoCapsFor(facing) }
    val wantHevc = AppSettings.hevc
    val mainLens = front || LensZone.isMain(zoom)
    val hsOk = !engine.hsRefused
    val options = remember(caps, front, mainLens, mode, wantHevc, hsOk) {
        val ok = { res: Res, fps: Int -> Encoders.codecFor(wantHevc, res, fps) != null }
        when {
            caps == null || !isVideo -> emptyList()
            mode == Mode.SLOMO -> VideoQuality.sloMoOptions(caps, mainLens, hsOk, ok)
            mode == Mode.TIMELAPSE -> VideoQuality.timeLapseOptions(VideoQuality.options(caps, front, mainLens, hsOk, ok))
            mode == Mode.PROVIDEO -> VideoQuality.options(caps, front, mainLens, hsOk, ok).filter { !it.highSpeed } // manual exposure cannot ride a 120/240 fps session
            else -> VideoQuality.options(caps, front, mainLens, hsOk, ok)
        }
    }
    val wanted = when (mode) {
        Mode.SLOMO -> VideoOption(Res.entries[AppSettings.sloRes.coerceIn(0, 3)], AppSettings.sloFps)
        Mode.TIMELAPSE -> VideoOption(Res.entries[AppSettings.videoRes.coerceIn(0, 3)], 30)
        else -> VideoOption(Res.entries[AppSettings.videoRes.coerceIn(0, 3)], AppSettings.videoFps)
    }
    val picked: VideoOption? = if (recording) recOption else VideoQuality.resolve(wanted, options)
    // slo-mo needs the rear camera's high-speed sizes; a phone without them simply has no SLO-MO in the strip
    val rearCaps = remember(engine) { engine.videoCapsFor(Facing.BACK) }
    val shownModes = remember(rearCaps, wantHevc, hsOk, launch.kind) {
        val slo = rearCaps != null && VideoQuality.sloMoOptions(rearCaps, true, hsOk) { res, fps -> Encoders.codecFor(wantHevc, res, fps) != null }.isNotEmpty()
        Mode.entries.filter { it != Mode.SLOMO || slo }.filter { !launch.secure || (it != Mode.MASTER && it != Mode.PROVIDEO) }.filter { // DNG files and manual controls are not for the lock screen
            when (launch.kind) { LaunchKind.IMAGE_CAPTURE -> it == Mode.PHOTO; LaunchKind.VIDEO_CAPTURE -> it == Mode.VIDEO; else -> true }
        }
    }
    LaunchedEffect(hsOk) { if (!hsOk && mode == Mode.SLOMO && !recording) mode = Mode.VIDEO } // the camera refused high-speed: slo-mo goes away
    val hdrChoices = remember(caps) {
        if (caps == null || !Encoders.hasTenBit()) emptyList()
        else Hdr.entries.filter { it.camera in caps.tenBitProfiles }.map { it.label to (it.ordinal + 1) }
    }
    val hdrNow = if (plainVideo && picked?.highSpeed != true) hdrChoices.firstOrNull { it.second == AppSettings.videoHdr }?.let { Hdr.entries[it.second - 1] } else null
    // the viewfinder always runs as a normal session; 120 and 240 fps switch to a high-speed session only while recording
    val setup = if (isVideo && caps != null && picked != null) {
        val fps = if (picked.highSpeed) 30 else picked.fps
        CameraEngine.VideoSetup(
            picked.res.width, picked.res.height, fps, hdrNow, VideoQuality.stabilisation(caps, AppSettings.ultraSteady, false),
            VideoQuality.aeRange(caps, fps, VideoQuality.adaptive(AppSettings.adaptiveFps, fps)) ?: (fps to fps),
        )
    } else null
    LaunchedEffect(caps, isVideo, picked == null) { if (isVideo && caps != null && picked == null) say("This camera cannot record video") }
    LaunchedEffect(permitted, resumed, texture, facing, if (isVideo) null else aspect, setup, wantRaw) {
        val st = texture
        val view = glView
        if (permitted && resumed && st != null && view != null && view.height > 0 && (!isVideo || setup != null)) {
            // camera sizes are listed landscape, so the screen shape is its long side over its short side (about 2.2 on this phone)
            renderer.freeze() // every reopen (mode, quality, camera, HDR) keeps the last picture until the new one streams
            engine.open(facing, st, AspectMath.captureAspect(aspect, view.height.toFloat() / view.width), CameraEngine.StillPrefs(AppSettings.heif, AppSettings.ultraHdr, AppSettings.mirrorSelfie, raw = wantRaw), setup)
        }
    }
    // Pro: the manual choices go to the camera; the assistants go to the picture (screen only, never into a photo or video)
    LaunchedEffect(effectivePro) { engine.setPro(effectivePro) }
    val mintGl = remember { floatArrayOf(DarkCb.mint.red, DarkCb.mint.green, DarkCb.mint.blue) }
    LaunchedEffect(proMode, AppSettings.peaking, AppSettings.zebra, mode, AppSettings.logProfile, AppSettings.lutPreview) {
        renderer.peaking = proMode && AppSettings.peaking
        renderer.zebra = proMode && AppSettings.zebra
        renderer.viewLog = mode == Mode.PROVIDEO && AppSettings.logProfile && !AppSettings.lutPreview
        renderer.assistColor = mintGl
        glView?.requestRender()
    }
    val histogramOn = isMaster && AppSettings.showHistogram
    DisposableEffect(histogramOn) {
        renderer.histogram = if (histogramOn) { bins -> histogramBins = bins } else null
        onDispose { renderer.histogram = null }
    }
    // the level line: the phone's gravity, only while the line is on show
    val levelOn = proMode && AppSettings.levelLine
    DisposableEffect(levelOn, resumed) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val gravity = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) { roll = LevelMath.roll(e.values[0], e.values[1]) }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, accuracy: Int) {}
        }
        if (levelOn && resumed && gravity != null) sensors.registerListener(listener, gravity, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors.unregisterListener(listener) }
    }
    LaunchedEffect(Unit) { snapshotFlow { zoom }.collect { engine.setZoom(it) } }
    val info = engine.info
    LaunchedEffect(info) {
        info?.let {
            renderer.sensorOrientation = it.sensorOrientation
            renderer.mirror = it.facing == Facing.FRONT
            renderer.bufferWidth = it.previewWidth
            renderer.bufferHeight = it.previewHeight
            engine.setExposure(ev) // the camera was reopened: keep the exposure the user set
            engine.setFlash(flash)
            focusHeld = false
        }
    }
    val state = engine.state
    LaunchedEffect(state) {
        if (state == CameraEngine.State.Streaming) { renderer.unfreeze(); showedPicture = true }
    }
    LaunchedEffect(zoom, pinching) { if (pinching) { delay(900); pinching = false } }
    LaunchedEffect(focusTick) { if (focusPoint != null) { delay(3500); if (!engine.locked) focusPoint = null } }
    LaunchedEffect(toast) { if (toast != null) { delay(1600); toast = null } }

    // photos arrive on the camera thread; saving continues on its own thread even if this screen goes away
    DisposableEffect(engine) {
        engine.listener = object : CameraEngine.Listener {
            override fun onShutter() {
                if (AppSettings.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
                shutterTick++
            }

            override fun onPhoto(photo: CameraEngine.Photo) {
                screenFlash = false
                val exif = if (photo.mirrored) PhotoRules.exifOrientation(photo.jpegOrientation, mirrored = true) else null
                PhotoSaver.save(context, photo.bytes, photo.format, exif, photo.squareCrop, System.currentTimeMillis()) { result ->
                    result.onSuccess { uri ->
                        AppSettings.rememberLastPhoto(uri.toString())
                        if (launchNow.secure) sessionShots.add(SessionShot(uri, false))
                        scope.launch { thumb = withContext(Dispatchers.IO) { PhotoSaver.thumbnail(context, uri) }?.asImageBitmap() }
                        if (launchNow.returnsResult) onResultNow(uri) // another app asked for this one photo
                    }.onFailure { say(if (it is java.io.IOException && !PhotoSaver.hasRoom()) "Storage is full" else "Could not save the photo") }
                }
            }

            override fun onFailed(message: String) {
                screenFlash = false
                say(message)
            }

            override fun onRawRefused() {
                AppSettings.masterFormat = 0
                say("RAW is not available here. Master saves JPG.")
            }

            override fun onVideoDowngraded(message: String) {
                if (message.startsWith("10-bit")) AppSettings.videoHdr = 0 else AppSettings.ultraSteady = false
                say(message)
            }
        }
        onDispose { } // the listener stays until the engine has closed (see above), so a last photo is still saved
    }
    // the last-shot button: after a restart and on every new launch. A lock-screen launch (also one that reaches a camera that was already
    // open) forgets every earlier picture, so nothing taken before it can show; a capture for another app shows none either.
    LaunchedEffect(launch.serial) {
        if (launch.secure || launch.returnsResult) {
            thumb = null
            sessionShots.clear()
            viewerOpen = false
            menu = Menu.NONE
        } else {
            val saved = AppSettings.lastPhotoUri
            if (saved.isNotEmpty()) thumb = withContext(Dispatchers.IO) { PhotoSaver.thumbnail(context, Uri.parse(saved)) }?.asImageBitmap()
        }
    }
    // "Auto-rotate": photos follow how the phone is held, though the screen stays portrait; "Follow system" keeps them upright for portrait
    DisposableEffect(AppSettings.photoAutoRotate, resumed) {
        deviceDegrees = 0
        val tilt = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) deviceDegrees = PhotoRules.deviceDegrees(orientation)
            }
        }
        if (AppSettings.photoAutoRotate && resumed) tilt.enable()
        onDispose { tilt.disable() }
    }
    // front "flash" is the screen: full white at full brightness while the photo is taken
    DisposableEffect(screenFlash) {
        val window = (context as? Activity)?.window
        val before = window?.attributes?.screenBrightness
        if (screenFlash && window != null) window.attributes = window.attributes.also { it.screenBrightness = 1f }
        onDispose { if (window != null && before != null) window.attributes = window.attributes.also { it.screenBrightness = before } }
    }

    val zoomMin = info?.zoomMin ?: 1f
    val zoomMax = info?.zoomMax ?: 1f
    val wide = ZoomStops.wideRatio(AppSettings.defaultWide)
    val stops = ZoomStops.stops(wide)
    fun setEv(stops: Float) {
        ev = stops.coerceIn(-Ev.LIMIT, Ev.LIMIT)
        engine.setExposure(ev)
    }
    // Pro controls: what the open camera allows, and the lists the dials step through
    val proCaps = info?.pro
    val isoStops = remember(proCaps) { proCaps?.let { ProRules.isoStops(it.isoMin, it.isoMax) }.orEmpty() }
    val shutterStops = remember(proCaps) { proCaps?.let { ProRules.shutterStops(it.exposureMinNs, it.exposureMaxNs) }.orEmpty() }
    val wbItems = remember(proCaps) { proCaps?.let { ProRules.wbChoices(it.awbModes, it.kelvin).drop(1) }.orEmpty() }
    val focusSteps = remember(proCaps) { proCaps?.let { ProRules.focusSteps(it.minFocusDiopters) }.orEmpty() }
    val videoFpsNow = (recOption ?: picked)?.fps ?: 30
    fun shutterText(ns: Long) = if (mode == Mode.PROVIDEO && AppSettings.shutterAngle) "${ProRules.shutterAngle(ns, videoFpsNow)}°" else ProRules.shutterLabel(ns)
    val manualExposure = proMode && pro.manualExposure
    fun seeded(): Pro = proCaps?.let { ProRules.seedExposure(pro, it, engine.liveIso, engine.liveNs) } ?: pro
    fun toggleProParam(p: ProParam) { proOpen = if (proOpen == p) null else p; evOpen = false; menu = Menu.NONE; qualityOpen = false; formatOpen = false }
    fun flip() {
        flash = Flash.OFF
        focusPoint = null
        facing = if (facing == Facing.BACK) Facing.FRONT else Facing.BACK
        zoom = if (facing == Facing.BACK) wide else 1f
    }
    fun cycleFlash() {
        if (mode.isVideo) { // in video the flash is the torch
            flash = if (flash == Flash.TORCH) Flash.OFF else Flash.TORCH
            engine.setFlash(flash)
            say("Torch: ${if (flash == Flash.TORCH) "On" else "Off"}")
            return
        }
        flash = flash.next(rear = facing == Facing.BACK)
        engine.setFlash(flash)
        say("Flash: ${flash.label}")
    }
    /** Zoom limits: the camera's range, or while recording with "Lock lens" only the lens that was in use. */
    fun clampZoom(z: Float): Float {
        val lock = recLens
        val i = engine.info
        return if (recording && lock != null) z.coerceIn(lock.start, lock.endInclusive) else z.coerceIn(i?.zoomMin ?: 1f, i?.zoomMax ?: 1f)
    }
    fun goTo(ratio: Float) {
        zoomJob?.cancel()
        zoomJob = scope.launch { Animatable(zoom).animateTo(clampZoom(ratio), tween(MOTION_MS)) { zoom = value } }
    }
    fun openLast() {
        if (launch.secure) { if (sessionShots.isNotEmpty()) viewerOpen = true; return } // locked: only this launch's own pictures
        val type = if (AppSettings.lastIsVideo) "video/*" else "image/*"
        val uri = Uri.parse(AppSettings.lastPhotoUri)
        // the gallery manager opens its viewer on the camera roll at this item (swipe through DCIM/Camera); an older gallery just views the one file
        val review = Intent("com.android.camera.action.REVIEW").setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setPackage(GALLERY)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(review)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(view).setPackage(GALLERY))
            } catch (_: ActivityNotFoundException) {
                runCatching { context.startActivity(Intent.createChooser(view, "Open with")) }
            }
        }
    }

    /** Takes one photo and waits for it. Returns false when it could not (camera not ready, storage full). */
    suspend fun captureOnce(useFlash: Boolean): Boolean {
        val i = engine.info ?: return false
        if (engine.state != CameraEngine.State.Streaming) return false
        if (heat == Heat.TOO_HOT) { say("The phone is too hot. Let it cool down."); return false }
        if (!PhotoSaver.hasRoom()) { say("Storage is full"); return false }
        val front = facing == Facing.FRONT
        val flashMode = if (useFlash) flash else Flash.OFF
        if (front && flashMode == Flash.ON) { screenFlash = true; delay(350) } // let the screen light the face first
        val place = if (AppSettings.locationTags) LastLocation.get(context) else null
        // read the mode and format as they are now: a photo run started from an older composition must not shoot an old choice
        val format = if (mode.isMaster) MasterFormat.of(AppSettings.masterFormat) else MasterFormat.JPG
        engine.takePhotoAndWait(CameraEngine.Shot(flashMode, PhotoRules.jpegOrientation(i.sensorOrientation, deviceDegrees, front), place, squareCrop = aspect == Aspect.R1_1, format = format))
        screenFlash = false
        return true
    }

    fun stopRecording(note: String? = null) {
        val r = recorder
        if (!recording || r == null) return
        recording = false
        recPaused = false
        recLens = null
        savingVideo = true // the bar on the thumbnail moves until the file is complete
        engine.setAwbLock(false)
        if (AppSettings.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        if (recDirect) {
            renderer.freeze()
            engine.stopHighSpeed { r.stop(null) } // the camera first stops writing into the encoder
            renderer.unfreeze()
        } else {
            r.stop(renderer)
        }
        note?.let(::say)
    }
    leaveHooks[0] = { stopRecording("Recording stopped. Your video is saved.") }

    fun beginRecording() {
        val i = engine.info ?: return
        val s = setup ?: return
        val o = recOption ?: picked ?: return
        val style = mode.style ?: return
        if (engine.state != CameraEngine.State.Streaming || recording || savingVideo) return
        if (heat == Heat.TOO_HOT) return say("The phone is too hot. Let it cool down.")
        if (!PhotoSaver.hasRoom(120L * 1024 * 1024)) return say("Storage is full")
        val isFront = facing == Facing.FRONT
        val turn = if (AppSettings.photoAutoRotate) VideoRules.turn(deviceDegrees, isFront) else 0
        val (fw, fh) = VideoRules.frameSize(o.res, VideoRules.portrait(turn))
        val hevc = s.hdr != null || Encoders.codecFor(AppSettings.hevc, o.res, o.fps) == true
        val playFps = if (style == VideoStyle.NORMAL) o.fps else 30
        val bitrate = VideoRules.bitrate(fw, fh, playFps, hevc, AppSettings.bitrateHigh, s.hdr != null, Encoders.maxBitrate(hevc))
        val place = if (AppSettings.locationTags) LastLocation.get(context) else null
        val direct = o.highSpeed
        // high-speed frames go straight from the camera to the encoder in the camera's own (landscape) shape and are turned on playback
        val hint = PhotoRules.jpegOrientation(i.sensorOrientation, deviceDegrees.takeIf { AppSettings.photoAutoRotate } ?: 0, false)
        val cfg = if (direct) {
            VideoRecorder.Config(o.res.width, o.res.height, if (style == VideoStyle.SLOMO) 30 else o.fps, o.fps, hevc, null, bitrate, style == VideoStyle.NORMAL, style, AppSettings.lapseSpeed, i.cameraClockIsBoot, place, direct = true, orientationHint = hint)
        } else {
            VideoRecorder.Config(fw, fh, playFps, o.fps, hevc, s.hdr, bitrate, style == VideoStyle.NORMAL, style, AppSettings.lapseSpeed, i.cameraClockIsBoot, place)
        }
        val rec = VideoRecorder(context.applicationContext, cfg, object : VideoRecorder.Listener {
            override fun onFinished(uri: Uri?, error: String?) {
                savingVideo = false
                recorder = null
                if (uri != null) {
                    AppSettings.rememberLast(uri.toString(), true)
                    if (launchNow.secure) sessionShots.add(SessionShot(uri, true))
                    scope.launch { thumb = withContext(Dispatchers.IO) { PhotoSaver.thumbnail(context, uri) }?.asImageBitmap() }
                    say(error ?: "Video saved")
                    if (launchNow.returnsResult) onResultNow(uri)
                } else if (error != null) {
                    say(error)
                }
            }

            override fun onProblem(message: String) { scope.launch { stopRecording("$message. Your video is saved.") } }
        })
        recorder = rec
        recOption = o
        recTurn = turn
        recSize = fw to fh
        recHdr = s.hdr != null
        recDirect = direct
        recMs = 0
        savingVideo = true // creating the file and the encoders takes a moment
        scope.launch {
            val made = withContext(Dispatchers.IO) { rec.prepare() }
            val surface = made.getOrNull()
            if (surface == null) {
                savingVideo = false
                recorder = null
                recOption = null
                say(if (made.exceptionOrNull()?.message?.contains("microphone") == true) "The microphone could not start" else "The video could not start")
                return@launch
            }
            val begin = { ok: Boolean ->
                if (ok) {
                    rec.start()
                    if (AppSettings.lockWb) engine.setAwbLock(true)
                    recLens = if (AppSettings.lockLens && !isFront) LensZone.range(zoom, i.zoomMin, i.zoomMax) else null
                    if (AppSettings.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
                    savingVideo = false
                    recording = true
                } else {
                    rec.cancel()
                    savingVideo = false
                    recorder = null
                    recOption = null
                    if (!direct) say(if (s.hdr != null) "10-bit HDR could not start. Turn it off in the video menu." else "The video encoder could not start")
                }
            }
            if (direct) {
                renderer.freeze() // the picture stays while the camera switches to its high-speed mode
                engine.startHighSpeed(surface, o.fps) { ok -> renderer.unfreeze(); begin(ok) }
            } else {
                renderer.startEncoding(PreviewRenderer.Encoding(surface, fw, fh, turn, isFront && AppSettings.mirrorSelfie, s.hdr != null, rec::gate, log = mode == Mode.PROVIDEO && AppSettings.logProfile), begin)
            }
        }
    }

    /** Record button: asks for the microphone first when the video has sound, then starts or stops the recording. */
    fun recordTap() {
        menu = Menu.NONE
        evOpen = false
        qualityOpen = false
        if (recording) return stopRecording()
        if (savingVideo || engine.state != CameraEngine.State.Streaming) return
        recOption = null
        if (mode.style == VideoStyle.NORMAL && !hasMic(context)) { askMic.launch(Manifest.permission.RECORD_AUDIO); return }
        recOption = picked
        beginRecording()
    }

    fun togglePause() {
        val r = recorder ?: return
        if (!recording) return
        if (recPaused) { r.resume(); recPaused = false } else { r.pause(); recPaused = true }
    }

    /** The photo button during a recording: one picture of the video frame, saved as a JPEG next to the video. */
    fun snapshot() {
        if (!recording || recHdr) return
        shutterTick++
        if (AppSettings.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        renderer.snapshot(recSize.first, recSize.second, recTurn, facing == Facing.FRONT && AppSettings.mirrorSelfie) { bitmap ->
            if (bitmap == null) { say("Could not take the photo"); return@snapshot }
            scope.launch(Dispatchers.IO) {
                val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
                bitmap.recycle()
                withContext(Dispatchers.Main) {
                    PhotoSaver.save(context, bytes, StillFormat.JPEG, null, false, System.currentTimeMillis()) { result ->
                        result.onSuccess { uri ->
                            if (launchNow.secure) sessionShots.add(SessionShot(uri, false))
                            if (!recording && !savingVideo) { AppSettings.rememberLastPhoto(uri.toString()); scope.launch { thumb = withContext(Dispatchers.IO) { PhotoSaver.thumbnail(context, uri) }?.asImageBitmap() } }
                        }.onFailure { say(if (it is java.io.IOException && !PhotoSaver.hasRoom()) "Storage is full" else "Could not save the photo") }
                    }
                }
            }
        }
    }

    fun selectMode(m: Mode) {
        if (m == mode || recording || savingVideo) return
        menu = Menu.NONE
        evOpen = false
        qualityOpen = false
        formatOpen = false
        proOpen = null
        focusPoint = null
        focusHeld = false
        if (m == Mode.SLOMO && facing == Facing.FRONT) { facing = Facing.BACK; zoom = wide }
        if (m.isVideo && flash != Flash.TORCH) flash = Flash.OFF
        mode = m
        say(m.label)
    }

    LaunchedEffect(launch.serial) {
        if (recording || savingVideo) return@LaunchedEffect
        val want = if (launch.wantsVideo) Mode.VIDEO else if (launch.wantsPhoto) Mode.PHOTO else null
        if (want != null) {
            if (want != mode) selectMode(want)
            if ((facing == Facing.FRONT) != launch.wantsFront) flip()
        }
    }
    LaunchedEffect(recording) {
        while (recording) {
            recMs = recorder?.activeMs() ?: 0
            if (!PhotoSaver.hasRoom(40L * 1024 * 1024)) stopRecording("Storage is full. Your video is saved.")
            delay(200)
        }
    }
    LaunchedEffect(recording, mode) {
        while (recording && mode == Mode.PROVIDEO) { micLevel = recorder?.level ?: 0f; delay(60) }
        micLevel = 0f
    }
    LaunchedEffect(heat) { if (recording && heat == Heat.TOO_HOT) stopRecording("The phone is too hot. Your video is saved.") }
    LaunchedEffect(state) { if (recording && state is CameraEngine.State.Error) stopRecording("The camera stopped. Your video is saved.") }

    /** Shutter tap: a timer run, an interval run or a single photo; a second tap while one is running stops it. */
    fun shutterTap() {
        if (mode.isVideo) return recordTap()
        menu = Menu.NONE
        evOpen = false
        if (runJob != null) { runJob?.cancel(); say("Stopped"); return }
        if (engine.state != CameraEngine.State.Streaming || engine.capturing) return
        if (heat == Heat.TOO_HOT) return say("The phone is too hot. Let it cool down.")
        runJob = scope.launch {
            try {
                if (timerSec > 0) {
                    for (s in timerSec downTo 1) { countdown = s; delay(1000) }
                    countdown = null
                }
                if (intervalSec > 0) {
                    var n = 0
                    while (isActive) {
                        if (!captureOnce(useFlash = true)) break
                        runCount = ++n
                        delay(intervalSec * 1000L)
                    }
                } else {
                    captureOnce(useFlash = true)
                }
            } finally {
                countdown = null
                runCount = 0
                runJob = null
            }
        }
    }
    fun holdStart() {
        if (mode.isVideo || !AppSettings.holdForBurst || runJob != null || timerSec > 0 || intervalSec > 0) return
        if (heat != Heat.OK) return say("No burst while the phone is hot")
        menu = Menu.NONE
        bursting = true
        runJob = scope.launch {
            try {
                var n = 0
                while (isActive) {
                    if (!captureOnce(useFlash = false)) break // a burst never uses the flash: it would wait for the light every time
                    runCount = ++n
                }
            } finally {
                runCount = 0
                runJob = null
                bursting = false
            }
        }
    }
    fun holdEnd() { if (bursting) runJob?.cancel() }

    /** A point on the screen as a point of the upright picture, or null when it is outside the viewfinder. */
    fun toPicture(pos: Offset): Pair<Float, Float>? {
        val i = info ?: return null
        val y = pos.y - boxTop
        if (viewSize.width == 0 || boxHeight == 0 || y < 0 || y > boxHeight) return null
        return PreviewMath.map(pos.x / viewSize.width, 1f - y / boxHeight, i.sensorOrientation, facing == Facing.FRONT, i.previewWidth, i.previewHeight, viewSize.width, boxHeight)
    }
    fun focus(pos: Offset, lock: Boolean) {
        val picture = toPicture(pos) ?: return
        engine.focusAt(picture.first, picture.second, lock)
        focusPoint = pos
        focusTick++
        if (lock) say("AE/AF lock")
    }

    // volume keys: shutter, zoom, or the phone's volume
    DisposableEffect(Unit) {
        CameraKeys.handler = handler@{ up, first ->
            when (AppSettings.volumeAction) {
                1 -> { if (first) shutterTap(); true }
                2 -> { goTo(zoom + if (up) 0.5f else -0.5f); true }
                else -> false
            }
        }
        onDispose { CameraKeys.handler = null }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewSize = it }) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    glView = this
                }
            },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                cameraGestures(
                    onTap = { pos ->
                        if (menu != Menu.NONE || evOpen || qualityOpen) { menu = Menu.NONE; evOpen = false; qualityOpen = false }
                        else if (AppSettings.tapToCapture && !mode.isVideo) shutterTap()
                        else focus(pos, lock = false)
                    },
                    onSwipe = { d -> if (menu == Menu.NONE && !recording && !savingVideo) mode.step(d, shownModes)?.let(::selectMode) },
                    onLongPress = { pos -> if (menu == Menu.NONE) focus(pos, lock = true) },
                    onZoom = { change -> zoomJob?.cancel(); zoom = clampZoom(zoom * change); pinching = true },
                    onExposureDrag = { dy -> setEv(ev + dy / 150f) },
                    dragEnabled = { focusPoint != null },
                )
            },
        )
        // black until the first picture, then it fades away; a camera switch keeps the old picture instead (see PreviewRenderer)
        val cover by animateFloatAsState(if (showedPicture) 0f else 1f, tween(MOTION_MS), label = "cover")
        if (cover > 0f) Box(Modifier.fillMaxSize().alpha(cover).background(Color.Black))

        // the shutter's own blink and the front screen flash (allowed to be instant: they are capture feedback)
        val blink = remember { Animatable(0f) }
        LaunchedEffect(shutterTick) { if (shutterTick > 0) { blink.snapTo(0.35f); blink.animateTo(0f, tween(160)) } }
        if (blink.value > 0f) Box(Modifier.fillMaxSize().alpha(blink.value).background(Color.White))
        if (screenFlash) Box(Modifier.fillMaxSize().background(Color.White))

        if (permitted) {
            focusPoint?.let { FocusBox(it, focusTick, engine.focus, engine.locked, ev, viewSize.width) }
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopPill(
                    flash, ev, evOpen, ::cycleFlash,
                    { if (manualExposure) say("EV is off while ISO and shutter are set by hand") else { evOpen = !evOpen; menu = Menu.NONE; qualityOpen = false; formatOpen = false; proOpen = null } },
                    Modifier.align(Alignment.TopStart).padding(16.dp), showFlash = !(isVideo && front),
                )
                Visible(histogramOn, Modifier.align(Alignment.TopStart).padding(top = 72.dp, start = 16.dp), enter = popEnter, exit = popExit) { HistogramView(histogramBins) }
                Visible(levelOn, Modifier.align(Alignment.Center), enter = popEnter, exit = popExit) { LevelLine(roll) }
                Row(Modifier.align(Alignment.TopEnd).padding(16.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                    Visible(isMaster && proCaps?.raw == true, enter = popEnter, exit = popExit) {
                        QualityPill(masterFormat.label, formatOpen) { formatOpen = !formatOpen; menu = Menu.NONE; evOpen = false; proOpen = null }
                    }
                    Visible(isVideo && picked != null && !recording && !savingVideo, enter = popEnter, exit = popExit) {
                        QualityPill(
                            if (mode == Mode.TIMELAPSE) TimeLapse.label(AppSettings.lapseSpeed) else picked?.label.orEmpty(), qualityOpen,
                        ) { qualityOpen = !qualityOpen; menu = Menu.NONE; evOpen = false }
                    }
                    RoundButton(onClick = { menu = if (menu == Menu.NONE) Menu.MAIN else Menu.NONE; evOpen = false; qualityOpen = false; formatOpen = false; proOpen = null }) {
                        CbIcon(Icon.SIX_DOT, if (menu != Menu.NONE) DarkCb.mint else Color.White, size = 24.dp)
                    }
                }
                Column(Modifier.align(Alignment.TopCenter).padding(top = 74.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Visible(recording, enter = popEnter, exit = popExit) {
                        val extra = if (mode == Mode.TIMELAPSE) "CLIP " + VideoRules.timer((TimeLapse.clipSeconds(recMs / 1000, AppSettings.lapseSpeed) * 1000).toLong()) else null
                        RecordTimer(recMs, recPaused, extra)
                    }
                    Toast(toast)
                    HeatChip(heat, video = mode.isVideo)
                    Visible(mode == Mode.PROVIDEO && recording, enter = popEnter, exit = popExit) { AudioMeter(micLevel) }
                }
                Visible(formatOpen && isMaster, Modifier.align(Alignment.TopEnd).padding(top = 72.dp, start = 16.dp, end = 16.dp), enter = popEnter, exit = popExit) {
                    QualityPanel(listOf(ChipRow("PHOTO FORMAT", MasterFormat.entries.map { f -> Chip(f.label, f == masterFormat) { AppSettings.masterFormat = f.ordinal; formatOpen = false; say("Format: ${f.label}") } })))
                }
                Visible(qualityOpen && isVideo && !recording, Modifier.align(Alignment.TopEnd).padding(top = 72.dp, start = 16.dp, end = 16.dp), enter = popEnter, exit = popExit) {
                    val rows = mutableListOf<ChipRow>()
                    if (mode == Mode.TIMELAPSE) rows += ChipRow("SPEED", TimeLapse.SPEEDS.map { sp -> Chip(TimeLapse.label(sp), sp == AppSettings.lapseSpeed) { AppSettings.lapseSpeed = sp; qualityOpen = false } })
                    rows += ChipRow(
                        if (mode == Mode.TIMELAPSE) "QUALITY" else "RESOLUTION AND FRAME RATE",
                        options.map { o ->
                            Chip(if (mode == Mode.TIMELAPSE) o.res.label else o.label, o == picked) {
                                if (mode == Mode.SLOMO) { AppSettings.sloRes = o.res.ordinal; AppSettings.sloFps = o.fps }
                                else { AppSettings.videoRes = o.res.ordinal; if (plainVideo) AppSettings.videoFps = o.fps }
                                qualityOpen = false
                            }
                        },
                    )
                    QualityPanel(rows)
                }
                Box(Modifier.align(Alignment.Center)) { Countdown(countdown) }

                Visible(menu == Menu.NONE, Modifier.align(Alignment.BottomCenter), enter = popEnter, exit = popExit) {
                    Column(Modifier.padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Visible(proMode && proCaps != null, enter = popEnter, exit = popExit) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val toggles = buildList {
                                    add(Toggle("PEAK", AppSettings.peaking) { AppSettings.peaking = !AppSettings.peaking })
                                    add(Toggle("ZEBRA", AppSettings.zebra) { AppSettings.zebra = !AppSettings.zebra })
                                    add(Toggle("LEVEL", AppSettings.levelLine) { AppSettings.levelLine = !AppSettings.levelLine })
                                    if (mode == Mode.PROVIDEO) {
                                        add(Toggle("LOG", AppSettings.logProfile) { if (!recording) AppSettings.logProfile = !AppSettings.logProfile })
                                        if (AppSettings.logProfile) add(Toggle("LUT", AppSettings.lutPreview) { AppSettings.lutPreview = !AppSettings.lutPreview })
                                        add(Toggle("ANGLE", AppSettings.shutterAngle) { AppSettings.shutterAngle = !AppSettings.shutterAngle })
                                    }
                                }
                                AssistRow(toggles)
                                val chips = buildList {
                                    if (proCaps?.exposure == true) {
                                        add(ProChip(ProParam.ISO, "ISO", if (manualExposure) "${pro.iso}" else "AUTO", manualExposure))
                                        add(ProChip(ProParam.SHUTTER, "S", if (manualExposure) shutterText(pro.exposureNs) else "AUTO", manualExposure))
                                    }
                                    if (wbItems.isNotEmpty()) add(ProChip(ProParam.WB, "WB", ProRules.wbLabel(pro.wb), pro.wb != Wb.Auto))
                                    if (focusSteps.isNotEmpty()) add(ProChip(ProParam.FOCUS, "MF", pro.focusDiopters?.let { ProRules.focusLabel(it) } ?: "AUTO", pro.focusDiopters != null))
                                }
                                ProBar(chips, proOpen, ::toggleProParam)
                            }
                        }
                        Box(Modifier.height(64.dp), Alignment.BottomCenter) {
                            Visible(evOpen, enter = popEnter, exit = popExit) { EvDial(ev, ::setEv) { setEv(0f) } }
                            Visible(!evOpen && proMode && proOpen != null, enter = popEnter, exit = popExit) {
                                when (proOpen) {
                                    ProParam.ISO -> StepDial("ISO", isoStops.map { "$it" }, if (manualExposure) ProRules.nearest(isoStops, pro.iso) else null, { i -> pro = seeded().copy(iso = isoStops[i]) }, { pro = pro.copy(manualExposure = false) })
                                    ProParam.SHUTTER -> StepDial("SHUTTER", shutterStops.map(::shutterText), if (manualExposure) ProRules.nearest(shutterStops, pro.exposureNs) else null, { i -> pro = seeded().copy(exposureNs = shutterStops[i]) }, { pro = pro.copy(manualExposure = false) })
                                    ProParam.WB -> StepDial("WB", wbItems.map(ProRules::wbLabel), wbItems.indexOf(pro.wb).takeIf { it >= 0 }, { i -> pro = pro.copy(wb = wbItems[i]) }, { pro = pro.copy(wb = Wb.Auto) })
                                    ProParam.FOCUS -> StepDial(
                                        "FOCUS", focusSteps.map(ProRules::focusLabel),
                                        pro.focusDiopters?.let { d -> focusSteps.indices.minByOrNull { kotlin.math.abs(focusSteps[it] - d) } },
                                        { i -> pro = pro.copy(focusDiopters = focusSteps[i]) }, { pro = pro.copy(focusDiopters = null) },
                                    )
                                    null -> Unit
                                }
                            }
                            Visible(!evOpen && pinching && proOpen == null, enter = popEnter, exit = popExit) { ZoomRuler(zoom, zoomMin, zoomMax, stops) }
                            Visible(!evOpen && !pinching && proOpen == null && (facing == Facing.BACK || isMaster), Modifier.fillMaxWidth().padding(horizontal = 16.dp), enter = popEnter, exit = popExit) {
                                Row(Modifier.fillMaxWidth(), if (isMaster) Arrangement.SpaceBetween else Arrangement.Center, Alignment.CenterVertically) {
                                    if (isMaster) ProSwitch(AppSettings.proOn, { AppSettings.proOn = it; say(if (it) "Pro" else "Auto") })
                                    if (facing == Facing.BACK) LensPills(zoom, stops) { pill ->
                                        // the active button steps through the extra focal lengths (28 / 35 mm, 243 mm); the others jump
                                        if (pill.selected) ZoomStops.next(pill.index, zoom, wide, AppSettings.extra28, AppSettings.extra35, AppSettings.extra243)?.let(::goTo)
                                        else goTo(pill.stop)
                                    }
                                    if (isMaster) Spacer(Modifier.size(44.dp)) // where the styles button of a later phase goes; keeps the lens pills centred
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            val normal = mode.style == VideoStyle.NORMAL
                            Crossfade(recording && normal && !recHdr, animationSpec = tween(MOTION_FAST_MS), label = "left slot") { snap ->
                                if (snap) RoundButton(size = 52, onClick = ::snapshot) { CbIcon(Icon.CAMERA, Color.White, size = 26.dp) }
                                else Thumbnail(thumb, PhotoSaver.saving > 0 || savingVideo, ::openLast)
                            }
                            Box(contentAlignment = Alignment.Center) {
                                if (isVideo) {
                                    RecordButton(recording, enabled = state == CameraEngine.State.Streaming && !savingVideo && heat != Heat.TOO_HOT, onClick = ::recordTap)
                                } else {
                                    Shutter(
                                        enabled = state == CameraEngine.State.Streaming && !engine.capturing && heat != Heat.TOO_HOT, running = runJob != null,
                                        onTap = ::shutterTap, onHoldStart = ::holdStart, onHoldEnd = ::holdEnd,
                                    )
                                }
                                Box(Modifier.align(Alignment.TopEnd).padding(end = 0.dp)) { CountBadge(runCount) }
                            }
                            Crossfade(if (recording) (if (normal && !recDirect) 1 else 2) else if (mode == Mode.SLOMO) 2 else 0, animationSpec = tween(MOTION_FAST_MS), label = "right slot") { slot ->
                                when (slot) {
                                    0 -> RoundButton(size = 52, onClick = ::flip) { CbIcon(Icon.REFRESH, Color.White, size = 26.dp) }
                                    1 -> RoundButton(size = 52, onClick = ::togglePause) { CbIcon(if (recPaused) Icon.PLAY else Icon.PAUSE, if (recPaused) DarkCb.mint else Color.White, size = 26.dp) }
                                    else -> Spacer(Modifier.size(52.dp))
                                }
                            }
                        }
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (shownModes.size > 1) ModeStrip(mode, shownModes, enabled = !recording && !savingVideo, onSelect = ::selectMode) else Spacer(Modifier.height(36.dp))
                        }
                    }
                }
                Visible(menu != Menu.NONE, Modifier.align(Alignment.BottomCenter), enter = popEnter, exit = popExit) {
                    if (isVideo) VideoMenuPanel(
                        menu, showHdr = plainVideo && hdrChoices.isNotEmpty() && picked?.highSpeed != true && !recording, hdr = AppSettings.videoHdr,
                        showSteady = caps?.previewStabilisation == true && picked?.highSpeed != true && !recording, steady = AppSettings.ultraSteady, focusHeld = focusHeld,
                        onPage = { menu = it },
                        onHdr = { v -> AppSettings.videoHdr = v; menu = Menu.NONE; say(if (v == 0) "10-bit HDR: Off" else "10-bit HDR: ${Hdr.entries[v - 1].label}") },
                        onSteady = { AppSettings.ultraSteady = !AppSettings.ultraSteady; menu = Menu.NONE; say("Ultra Steady: ${if (AppSettings.ultraSteady) "On" else "Off"}") },
                        onFocus = { focusHeld = !focusHeld; engine.holdFocus(focusHeld); menu = Menu.NONE; say("Focus lock: ${if (focusHeld) "On" else "Off"}") },
                        onSettings = { menu = Menu.NONE; onSettings() },
                        hdrChoices = listOf("Off" to 0) + hdrChoices,
                        modifier = Modifier.padding(bottom = 16.dp), showSettings = !launch.secure,
                    ) else MenuPanel(
                        menu, aspect, timerSec, intervalSec,
                        onPage = { menu = it },
                        onAspect = { aspect = it; menu = Menu.NONE; say("Aspect ratio: ${it.label}") },
                        onTimer = { timerSec = it; menu = Menu.NONE; say(if (it == 0) "Timer: Off" else "Timer: $it s") },
                        onInterval = { intervalSec = it; menu = Menu.NONE; say(if (it == 0) "Interval shooting: Off" else "Interval: every $it s") },
                        onSettings = { menu = Menu.NONE; onSettings() },
                        modifier = Modifier.padding(bottom = 16.dp), showSettings = !launch.secure,
                        master = isMaster, histogram = AppSettings.showHistogram, faceOk = proCaps?.faceDetect == true, face = AppSettings.faceMetering,
                        onHistogram = { AppSettings.showHistogram = !AppSettings.showHistogram; menu = Menu.NONE; say("Histogram: ${if (AppSettings.showHistogram) "On" else "Off"}") },
                        onFace = { AppSettings.faceMetering = !AppSettings.faceMetering; menu = Menu.NONE; say("Face metering: ${if (AppSettings.faceMetering) "On" else "Off"}") },
                    )
                }
            }
            ErrorOverlay(state, onRetry = engine::retry)
            Visible(viewerOpen, Modifier.fillMaxSize(), enter = overlayEnter, exit = overlayExit) {
                SessionViewer(sessionShots.toList()) { viewerOpen = false }
            }
            micAsk?.let { blocked ->
                MicDialog(
                    blocked, onAllow = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                    onSettings = { micAsk = null; context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                    onCancel = { micAsk = null },
                )
            }
        } else {
            PermissionScreen(refused, context) { askCamera.launch(Manifest.permission.CAMERA) }
        }
    }
}

@Composable
private fun ErrorOverlay(state: CameraEngine.State, onRetry: () -> Unit) {
    val error = state as? CameraEngine.State.Error
    var last by remember { mutableStateOf(error) }
    if (error != null) last = error
    Visible(error != null, Modifier.fillMaxSize(), enter = popEnter, exit = popExit) {
        val e = last ?: return@Visible
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), Alignment.Center) {
            Column(Modifier.padding(24.dp).fillMaxWidth().doubleChamferFrame(DarkCb, DarkCb.surface1).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (e.kind) {
                    CameraEngine.Kind.BUSY -> {
                        Text("CAMERA IN USE", style = monoStyle(Warn, 2.6.sp))
                        Text("Another app is using the camera. This screen opens by itself when it lets go.", style = bodyText())
                        WaitingLine("WAITING FOR THE CAMERA")
                    }
                    CameraEngine.Kind.DISCONNECTED -> {
                        Text("CAMERA DISCONNECTED", style = monoStyle(Warn, 2.6.sp))
                        Text("The camera stopped. Trying to open it again.", style = bodyText())
                        WaitingLine("REOPENING")
                        BrandButton("TRY AGAIN", Modifier.fillMaxWidth(), cb = DarkCb, onClick = onRetry)
                    }
                    CameraEngine.Kind.DISABLED -> {
                        Text("CAMERA TURNED OFF", style = monoStyle(Warn, 2.6.sp))
                        Text("Android says the camera is switched off for apps (a device policy or a privacy switch). Turn it on, then try again.", style = bodyText())
                        BrandButton("TRY AGAIN", Modifier.fillMaxWidth(), primary = true, cb = DarkCb, onClick = onRetry)
                    }
                    CameraEngine.Kind.FAILED -> {
                        Text("CAMERA PROBLEM", style = monoStyle(Warn, 2.6.sp))
                        Text("The camera could not start" + if (e.detail.isNotEmpty()) " (${e.detail})." else ".", style = bodyText())
                        BrandButton("TRY AGAIN", Modifier.fillMaxWidth(), primary = true, cb = DarkCb, onClick = onRetry)
                    }
                }
            }
        }
    }
}

/** Camera permission refused: say why it is needed and offer the way out (ask again, or the app's system settings). */
@Composable
private fun PermissionScreen(refused: Boolean, context: Context, onAsk: () -> Unit) {
    val activity = context as? Activity
    // after "don't ask again" Android no longer shows the question, so the only way is the system settings
    val blocked = refused && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    Box(Modifier.fillMaxSize().cbGrid(DarkCb), Alignment.Center) {
        Column(Modifier.padding(24.dp).fillMaxWidth().doubleChamferFrame(DarkCb, DarkCb.surface1).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("CAMERA ACCESS", style = monoStyle(DarkCb.mint, 2.6.sp))
            Text("This app needs the camera to show the viewfinder and take photos. Nothing leaves your phone.", style = bodyText())
            if (blocked) {
                Text("Camera access is blocked. Open the app's settings, choose Permissions, then Camera, and allow it.", style = bodyText().copy(color = Warn))
                BrandButton("OPEN SETTINGS", Modifier.fillMaxWidth(), primary = true, cb = DarkCb) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
            } else {
                BrandButton("ALLOW CAMERA", Modifier.fillMaxWidth(), primary = true, cb = DarkCb, onClick = onAsk)
            }
        }
    }
}

private fun bodyText() = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = DarkCb.ink2, textAlign = TextAlign.Start)
