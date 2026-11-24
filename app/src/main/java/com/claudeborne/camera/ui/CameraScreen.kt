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
import androidx.compose.foundation.layout.offset
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
import com.claudeborne.camera.capture.PhotoEdit
import com.claudeborne.camera.capture.Watermark
import com.claudeborne.camera.capture.WatermarkSpec
import com.claudeborne.camera.look.GridKind
import com.claudeborne.camera.look.Look
import com.claudeborne.camera.look.Scene
import com.claudeborne.camera.look.Tilt
import com.claudeborne.camera.look.TiltRules
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
import com.claudeborne.camera.data.Keep
import kotlin.math.roundToInt
import com.claudeborne.camera.data.Settings as AppSettings
import com.claudeborne.camera.gl.PreviewMath
import com.claudeborne.camera.scan.DocRules
import com.claudeborne.camera.scan.DocScanner
import com.claudeborne.camera.scan.Enhance
import com.claudeborne.camera.scan.Quad
import com.claudeborne.camera.scan.ScanSaver
import com.claudeborne.camera.scan.Frac
import com.claudeborne.camera.proc.AI_INPUT_MAX
import com.claudeborne.camera.proc.NightStack
import com.claudeborne.camera.proc.Portrait
import com.claudeborne.camera.proc.PortraitRules
import com.claudeborne.camera.proc.upscale4x
import com.claudeborne.camera.video.ClipRing
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import com.claudeborne.camera.proc.FrameStack
import com.claudeborne.camera.proc.HdrMerge
import com.claudeborne.camera.proc.Panorama
import com.claudeborne.camera.proc.SweepRules
import kotlinx.coroutines.currentCoroutineContext
import android.hardware.camera2.CameraExtensionCharacteristics
import com.claudeborne.camera.scan.LensShare
import com.claudeborne.camera.scan.FrameScanner
import com.claudeborne.camera.camera.MultiCameraEngine
import com.claudeborne.camera.dual.DualRules
import com.claudeborne.camera.dual.Layout
import com.claudeborne.camera.dual.Pip
import com.claudeborne.camera.scan.QrRules
import com.claudeborne.camera.scan.TextRules
import androidx.activity.compose.BackHandler
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
    /** Underwater mode: wet fingers cannot touch the screen, so the volume keys are always the shutter. */
    var forceShutter = false
    fun handles() = handler != null && (forceShutter || AppSettings.volumeAction != 0)
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
    var moreOpen by remember { mutableStateOf(false) } // the More grid of modes
    var moreEditing by remember { mutableStateOf(false) } // its pencil: moving modes
    var evOpen by remember { mutableStateOf(false) }
    var ev by remember { mutableFloatStateOf(AppSettings.evSaved / 100f) } // kept between launches while the Exposure setting switch is on
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
    var mode by remember { mutableStateOf(if (launch.wantsVideo) Mode.VIDEO else if (launch.wantsPhoto || !AppSettings.keep(Keep.MODE)) Mode.PHOTO else Mode.entries.firstOrNull { it.name == AppSettings.lastMode } ?: Mode.PHOTO) }
    val launchNow by rememberUpdatedState(launch)
    val onResultNow by rememberUpdatedState(onResult)
    val sessionShots = remember { mutableStateListOf<SessionShot>() } // lock-screen launch: the only pictures its viewer may show
    var viewerOpen by remember { mutableStateOf(false) }
    val isVideo = mode.isVideo
    val multi = mode.multi
    val isMaster = mode.isMaster
    val plainVideo = mode == Mode.VIDEO || mode == Mode.PROVIDEO // normal frame rates (no 120/240 fps, no time-lapse, no slo-mo)
    val proMode = (isMaster && AppSettings.proOn) || mode == Mode.PROVIDEO // the manual controls are on show
    var pro by remember { mutableStateOf(Pro()) }
    var proOpen by remember { mutableStateOf<ProParam?>(null) }
    var formatOpen by remember { mutableStateOf(false) }
    var histogramBins by remember { mutableStateOf<IntArray?>(null) }
    val scanner = remember { FrameScanner(context.applicationContext) }
    // Dual and Quad view: the rear and the front camera (and more lenses) streaming together, drawn as one picture
    val multiEngine = remember { MultiCameraEngine(context.applicationContext) }
    val multiCams = remember { multiEngine.cameras() }
    var dualLayout by remember { mutableStateOf(Layout.entries[AppSettings.dualLayout.coerceIn(0, Layout.entries.lastIndex)]) }
    var dualSwap by remember { mutableStateOf(false) }
    var pip by remember { mutableStateOf(Pip()) }
    var pipDragging by remember { mutableStateOf(false) }
    // the OnePlus 15 refuses 3 rear lenses + the front together (camera error 4, tested on the phone): the multi-lens view is 2 rear lenses + the front
    var quadRear by remember { mutableIntStateOf(minOf(2, multiCams?.physical?.size ?: 0)) }
    var multiReady by remember { mutableStateOf(false) }
    var dualStills by remember { mutableStateOf(true) } // Dual Photo asks for full-size photos; false for the rest of the run once the phone refuses
    var dualBusy by remember { mutableStateOf(false) } // a Dual Photo is being taken and saved
    var quadNote by remember { mutableStateOf<String?>(null) } // why Quad view shows fewer cameras than the four it tries first, kept on screen
    var quadOk by remember { mutableStateOf(true) } // Quad view drops out of the strip for this run once even 2 rear cameras and the front are too slow
    var recorderB by remember { mutableStateOf<VideoRecorder?>(null) } // Dual view with separate files: the front camera's recorder
    var collect by remember { mutableStateOf<((CameraEngine.Photo) -> Unit)?>(null) } // a multi-picture feature takes the next photos instead of the saver
    var ring by remember { mutableStateOf<ClipRing?>(null) } // Live photo: the clip encoder that runs while it is on
    var liveClip by remember { mutableStateOf<Deferred<ByteArray?>?>(null) } // the clip for the photo being taken
    var tooClose by remember { mutableStateOf(false) } // Portrait: the person fills too much of the frame
    var aiProgress by remember { mutableStateOf<Float?>(null) } // AI Telescope Zoom: progress of the enhancement running now
    val skipPortrait = remember { booleanArrayOf(false) }
    var busyText by remember { mutableStateOf("READING TEXT") }
    var finishFlag by remember { mutableStateOf(false) } // second tap in Pano / Long exposure: stop taking pictures and finish
    var sweepProgress by remember { mutableStateOf<Float?>(null) }
    var macro by remember { mutableStateOf(false) } // the ultra wide lens is on for a close-up
    var action by remember { mutableStateOf(false) } // Action scene mode
    val palmHook = remember { arrayOf<() -> Unit>({}) } // 0: the palm was seen long enough: start the 3 second timer
    var qr by remember { mutableStateOf<String?>(null) } // the last QR code seen; it goes away when it has not been seen for a while or is dismissed
    var qrSeenAt by remember { mutableLongStateOf(0L) }
    var textOutline by remember { mutableStateOf<Frac?>(null) }
    var suggestText by remember { mutableStateOf(false) }
    var readingText by remember { mutableStateOf(false) }
    var textResult by remember { mutableStateOf<String?>(null) }
    var docQuad by remember { mutableStateOf<Quad?>(null) } // the page outline found live in Scan Docs
    val scanPages = remember { mutableStateListOf<ByteArray>() } // pages kept so far (finished JPEGs)
    var scanDraft by remember { mutableStateOf<ScanDraft?>(null) } // the photo being edited
    var scanBusy by remember { mutableStateOf(false) }
    var scanSaveOpen by remember { mutableStateOf(false) }
    var scanSaving by remember { mutableStateOf<Float?>(null) } // null: not saving; otherwise the progress 0..1
    val scanModeNow by rememberUpdatedState(mode == Mode.SCAN)
    val textModeNow by rememberUpdatedState(mode == Mode.TEXT) // read inside the camera listener, which lives longer than this composition
    var roll by remember { mutableFloatStateOf(0f) }
    var micLevel by remember { mutableFloatStateOf(0f) }
    val masterFormat = MasterFormat.of(AppSettings.masterFormat)
    val wantRaw = isMaster && masterFormat.wantsRaw
    val effectivePro = (if (proMode) pro else Pro()).copy(faceMetering = isMaster && AppSettings.faceMetering)
    var tilt by remember { mutableStateOf(Tilt()) }
    val scene = if (mode == Mode.VIDEO) Scene.all[AppSettings.scene.coerceIn(0, Scene.all.lastIndex)] else Scene.all[0]
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
    /** The watermark for a photo or video taken now, or null when it is off or does not apply. */
    fun watermark(video: Boolean): WatermarkSpec? {
        if (!AppSettings.wmOn || !Watermark.appliesTo(AppSettings.wmTarget, video)) return null
        val place = if (AppSettings.wmLocation) LastLocation.get(context)?.let { Watermark.coordinates(it.latitude, it.longitude) } else null
        val text = Watermark.text(AppSettings.wmShotOn, Watermark.modelName(android.os.Build.MANUFACTURER, android.os.Build.MODEL), place, if (AppSettings.wmDate) System.currentTimeMillis() else null, AppSettings.wmText)
        return if (text.isEmpty()) null else WatermarkSpec(text, AppSettings.wmSize, AppSettings.wmPosition)
    }
    val watermarkNow by rememberUpdatedState(::watermark)

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
    val shape = if (isVideo || multi) Aspect.R16_9 else aspect // video is always the 16:9 shape, as in the stock app
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
                Lifecycle.Event.ON_PAUSE -> { leaveHooks[0](); resumed = false; showedPicture = false; runJob?.cancel(); engine.close(); multiEngine.close(); multiReady = false; texture = null; glView?.onPause() }
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
            multiEngine.close()
            multiEngine.release()
            sound.release()
            scanner.close()
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
    val hasGyro = remember { (context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager).getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null }
    val nightOk = engine.extensionSupported(facing, CameraExtensionCharacteristics.EXTENSION_NIGHT) && !engine.extRefused
    val bokehOk = engine.extensionSupported(facing, CameraExtensionCharacteristics.EXTENSION_BOKEH) && !engine.extRefused
    val extWanted = when {
        isVideo || wantRaw -> -1
        nightOk && ((mode == Mode.NIGHT && !AppSettings.nightLong) || (mode == Mode.PHOTO && AppSettings.nightAlways)) -> CameraExtensionCharacteristics.EXTENSION_NIGHT
        bokehOk && mode == Mode.PORTRAIT -> CameraExtensionCharacteristics.EXTENSION_BOKEH // the phone's own portrait blur (rear camera)
        else -> -1
    }
    val ownNight = mode == Mode.NIGHT && (AppSettings.nightLong || !nightOk) && DocScanner.available // our own multi-frame night
    val portraitOwnNow by rememberUpdatedState(mode == Mode.PORTRAIT && extWanted < 0) // our own cut-out and blur: front camera, or no BOKEH
    LaunchedEffect(nightOk) { if (!nightOk && mode == Mode.NIGHT && !DocScanner.available) mode = Mode.PHOTO }
    // Live photo: while on (Photo, rear camera), the GL pipeline feeds a small encoder that remembers the last moments
    val liveOn = mode == Mode.PHOTO && AppSettings.livePhoto && facing == Facing.BACK && extWanted < 0 && !launch.returnsResult
    // the clip encoder also runs during a recording when "Motion Photo" is on for video (the recorder keeps the main encoder slot)
    val clipOn = liveOn || (recording && mode.style == VideoStyle.NORMAL && !recHdr && !recDirect && recTurn == 0 && facing == Facing.BACK && AppSettings.videoMotion)
    DisposableEffect(clipOn, resumed) {
        var r: ClipRing? = null
        if (clipOn && resumed) {
            r = runCatching { ClipRing(960, 1280) }.getOrNull()
            val made = r
            if (made != null) {
                renderer.startClip(PreviewRenderer.Encoding(made.surface, 960, 1280, 0, false, false, { ts -> ts })) { ok -> if (!ok) say("Live photo could not start") }
                ring = made
            } else say("Live photo is not available here")
        }
        onDispose { val old = r; if (old != null) { ring = null; renderer.stopClip { old.release() } } }
    }
    val shownModes = remember(rearCaps, wantHevc, hsOk, launch.kind, nightOk, quadOk, AppSettings.modeOrder) {
        val slo = rearCaps != null && VideoQuality.sloMoOptions(rearCaps, true, hsOk) { res, fps -> Encoders.codecFor(wantHevc, res, fps) != null }.isNotEmpty()
        ModeOrder.parse(AppSettings.modeOrder).filter { !it.multi || multiCams != null }.filter { it != Mode.QUAD || (quadOk && (multiCams?.physical?.size ?: 0) >= 2) }.filter { it != Mode.SLOMO || slo }.filter { it != Mode.SCAN || DocScanner.available }.filter { it != Mode.NIGHT || nightOk || DocScanner.available }.filter { (it != Mode.PANO && it != Mode.LONGEXP) || DocScanner.available }.filter { it != Mode.PANO || hasGyro }.filter { !launch.secure || (it != Mode.MASTER && it != Mode.PROVIDEO) }.filter { // DNG files and manual controls are not for the lock screen
            when (launch.kind) { LaunchKind.IMAGE_CAPTURE -> it == Mode.PHOTO; LaunchKind.VIDEO_CAPTURE -> it == Mode.VIDEO; else -> true }
        }
    }
    LaunchedEffect(hsOk) { if (!hsOk && mode == Mode.SLOMO && !recording) mode = Mode.VIDEO } // the camera refused high-speed: slo-mo goes away
    // what the Preserve switches keep (Settings > General > Preserve settings): the mode, the exposure value and the Portrait zoom
    LaunchedEffect(mode) { if (!launch.secure && !launch.returnsResult) AppSettings.lastMode = mode.name }
    LaunchedEffect(shownModes) { if (mode !in shownModes && !recording && !savingVideo) mode = Mode.PHOTO } // a kept mode the phone cannot do right now
    LaunchedEffect(ev) { delay(300); AppSettings.evSaved = (ev * 100).roundToInt() }
    LaunchedEffect(mode, zoom, pinching, facing) { if (mode == Mode.PORTRAIT && facing == Facing.BACK && !pinching) { delay(400); AppSettings.portraitZoom = (zoom * 1000).roundToInt() } }
    val hdrChoices = remember(caps) {
        if (caps == null || !Encoders.hasTenBit()) emptyList()
        else Hdr.entries.filter { it.camera in caps.tenBitProfiles }.map { it.label to (it.ordinal + 1) }
    }
    // the look: filter, Soft light and styles (Master), scenes (Video), Underwater and Tilt-shift; 120 and 240 fps video bypasses the GL pipeline, so it gets none
    val lookFilterOk = !isMaster && !mode.oneShot && !multi && mode != Mode.SLOMO && !(isVideo && picked?.highSpeed == true)
    // Retouch (faces smoothed and reshaped): Photo, Portrait and normal video; the straightening of ultra wide faces needs the rear camera below 1x
    val retouchOk = lookFilterOk && (mode == Mode.PHOTO || mode == Mode.PORTRAIT || mode == Mode.VIDEO)
    val retouchBase = com.claudeborne.camera.face.RetouchStore.decode(AppSettings.retouch)
    val retouchNow = if (!retouchOk) null else retouchBase.copy(
        correct = if (AppSettings.distortionFix && facing == Facing.BACK && !isVideo) com.claudeborne.camera.face.RetouchOps.edgeAngle(zoom) else 0f,
    ).takeIf { it.active }
    val look = if (isVideo && picked?.highSpeed == true) Look.NONE else Look(
        filter = if (lookFilterOk) AppSettings.filter.coerceIn(0, com.claudeborne.camera.look.Filters.all.lastIndex) else 0,
        soft = if (mode == Mode.PHOTO || mode == Mode.PORTRAIT || isMaster) AppSettings.softLight else 0,
        tone = if (isMaster) AppSettings.styleTone else 0, sat = if (isMaster) AppSettings.styleSat else 0, warm = if (isMaster) AppSettings.styleWarm else 0,
        tint = if (isMaster) AppSettings.styleTint else 0, sharp = if (isMaster) AppSettings.styleSharp else 0, vignette = isMaster && AppSettings.styleVignette,
        underwater = mode == Mode.UNDERWATER, tilt = if (mode == Mode.TILTSHIFT) tilt else null, retouch = retouchNow,
    ).withScene(scene)
    val lookNow by rememberUpdatedState(look)
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
    LaunchedEffect(permitted, resumed, texture, facing, if (isVideo) null else aspect, setup, wantRaw, extWanted, liveOn, multi) {
        val st = texture
        val view = glView
        if (permitted && resumed && st != null && view != null && view.height > 0 && (!isVideo || setup != null) && !multi) {
            // camera sizes are listed landscape, so the screen shape is its long side over its short side (about 2.2 on this phone)
            renderer.freeze() // every reopen (mode, quality, camera, HDR) keeps the last picture until the new one streams
            engine.open(facing, st, AspectMath.captureAspect(aspect, view.height.toFloat() / view.width), CameraEngine.StillPrefs(AppSettings.heif && !liveOn, AppSettings.ultraHdr && !liveOn, AppSettings.mirrorSelfie, raw = wantRaw), setup, extWanted)
        }
    }
    // Dual and Quad view: the normal camera is closed and the multi-camera engine streams into the renderer's textures; a refusal always ends in a working camera
    LaunchedEffect(permitted, resumed, texture, mode, quadRear, dualStills) {
        val st = texture
        if (!multi || !permitted || !resumed || st == null || glView == null) return@LaunchedEffect
        if (multiCams == null) { say("This phone cannot use two cameras at once"); mode = Mode.PHOTO; return@LaunchedEffect }
        multiReady = false
        renderer.freeze()
        withContext(Dispatchers.IO) { engine.close() }
        val rear = if (mode == Mode.QUAD) quadRear else 1
        renderer.makeSources(rear) { extra ->
            val stills = mode == Mode.DUALPHOTO && dualStills
            multiEngine.open(rear, listOf(st) + extra, stills, { infos, message ->
                scope.launch {
                    if (infos == null) {
                        val lower = if (mode == Mode.QUAD) DualRules.stepDown(quadRear) else null
                        val restarted = message == "The camera service restarted"
                        if (mode == Mode.QUAD) quadNote = if (restarted) "CAMERA SERVICE RESTARTED" else "CAMERA REFUSED"
                        if (restarted && mode == Mode.QUAD) delay(6000) // the phone needs a few seconds to bring its cameras back before they can be opened again
                        if (stills) { // the phone cannot stream both cameras with full-size photos: open again with the screen picture size
                            say("Full-size photos are not possible with both cameras at once. The photos use the screen picture size.")
                            dualStills = false
                        } else if (lower != null) { say("${message ?: "Not possible"}. Trying $lower rear cameras and the front."); quadRear = lower }
                        else { say("${message ?: "Not possible"}. The normal camera is back."); mode = Mode.PHOTO }
                    } else {
                        renderer.sensorOrientation = infos[0].sensor
                        renderer.bufferWidth = infos[0].width
                        renderer.bufferHeight = infos[0].height
                        renderer.mirror = false
                        for (i in 1 until infos.size) renderer.setSource(i, infos[i].sensor, infos[i].width, infos[i].height)
                        renderer.resetFrameCounts()
                        multiReady = true
                        renderer.unfreeze()
                        showedPicture = true
                    }
                }
            }, { text ->
                scope.launch {
                    val lower = if (mode == Mode.QUAD) DualRules.stepDown(quadRear) else null
                    if (mode == Mode.QUAD) { quadNote = "CAMERA LOST"; delay(4000) }
                    if (lower != null) { say("$text. Trying $lower rear cameras and the front."); quadRear = lower }
                    else { say("$text. The normal camera is back."); mode = Mode.PHOTO }
                }
            })
        }
    }
    // leaving Quad view: next time it tries all the cameras again, with no old reason on screen
    LaunchedEffect(mode) { if (mode != Mode.QUAD) { quadRear = minOf(2, multiCams?.physical?.size ?: 0); quadNote = null } }
    // Quad view must stream smoothly (about 30 fps in every tile) and stay cool: watch the real frame rate of each camera and the phone heat, and step down
    // (3 rear cameras and the front, then 2 and the front, then not at all) rather than show a stuttering or hot picture
    LaunchedEffect(multiReady, mode, quadRear) {
        if (mode != Mode.QUAD || !multiReady) return@LaunchedEffect
        delay(2000) // the cameras need a moment to settle (exposure, focus) before their speed means anything
        renderer.resetFrameCounts()
        val begin = android.os.SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            val seconds = (android.os.SystemClock.elapsedRealtime() - begin) / 1000f
            val counts = renderer.frameCounts().take(quadRear + 1)
            val slow = DualRules.tooSlow(counts, seconds, 20f)
            val hot = heat != Heat.OK
            if (!slow && !hot) { if (seconds > 20f) break else continue } // smooth for 20 seconds: stop looking, the heat check goes on below
            if (recording) continue // never reopen the cameras under a recording
            val lower = DualRules.stepDown(quadRear)
            val why = if (hot) "The phone is getting hot" else "Quad view runs slowly here"
            android.util.Log.w("QuadWatch", "stepping down: heat=$heat frames=$counts after ${"%.1f".format(seconds)} s, quadRear=$quadRear")
            quadNote = if (hot) "PHONE HOT" else "TOO SLOW"
            if (lower != null) { say("$why. Showing $lower rear cameras and the front."); quadRear = lower }
            else { say("$why. Quad view is switched off."); quadOk = false; mode = Mode.PHOTO }
            return@LaunchedEffect
        }
        while (true) { // after the first seconds only the heat matters
            delay(3000)
            if (heat == Heat.OK || recording) continue
            val lower = DualRules.stepDown(quadRear)
            if (lower != null) { say("The phone is getting hot. Showing $lower rear cameras and the front."); quadRear = lower }
            else { say("The phone is getting hot. Quad view is switched off."); quadOk = false; mode = Mode.PHOTO }
            return@LaunchedEffect
        }
    }
    // leaving Dual / Quad view: the cameras are released and the extra textures dropped; the normal camera reopens by itself
    LaunchedEffect(multi) {
        if (!multi) {
            renderer.composite = null
            multiReady = false
            withContext(Dispatchers.IO) { multiEngine.close() }
            renderer.makeSources(0) { }
        }
    }
    val dualOutAspect = if (targetHeight > 0) viewSize.width.toFloat() / targetHeight else 0.5625f
    val dualTarget = if (!multi) emptyList() else if (mode == Mode.QUAD) DualRules.quad(quadRear + 1, quadRear, AppSettings.mirrorSelfie)
        else DualRules.two(dualLayout, dualSwap, pip, dualOutAspect, AppSettings.mirrorSelfie)
    val dualTiles = animateTiles(dualTarget, animate = !pipDragging)
    LaunchedEffect(dualBusy) { if (dualBusy) { delay(15000); dualBusy = false } } // never leave the shutter stuck
    LaunchedEffect(dualTiles, multi, multiReady) { renderer.composite = if (multi && multiReady) dualTiles else null; glView?.requestRender() }
    // Pro: the manual choices go to the camera; the assistants go to the picture (screen only, never into a photo or video)
    LaunchedEffect(effectivePro) { engine.setPro(effectivePro) }
    LaunchedEffect(look) { renderer.look = look; glView?.requestRender() }
    LaunchedEffect(action, engine.info, mode) { engine.setAction(action && mode == Mode.PHOTO) }
    LaunchedEffect(scene) { engine.setExposure(ev + scene.ev) }
    LaunchedEffect(mode) { CameraKeys.forceShutter = mode == Mode.UNDERWATER }
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
    // QR codes (Photo) and text (Text mode outline, Photo suggestion): small copies of the live picture go to the scanner, never to a file
    val scanQr = mode == Mode.PHOTO && AppSettings.qrScan
    val scanText = (mode == Mode.TEXT && textResult == null && !readingText) || (mode == Mode.PHOTO && AppSettings.recommendText)
    val scanDoc = mode == Mode.SCAN && scanDraft == null && !scanSaveOpen
    val scanPalm = mode == Mode.PHOTO && AppSettings.showPalm && com.claudeborne.camera.scan.HandScanner.supported && !launch.returnsResult
    val hintsOn = AppSettings.smartSuggestions
    val scanCover = mode == Mode.PORTRAIT && hintsOn
    DisposableEffect(scanQr, scanText, scanDoc, scanPalm, scanCover, mode) {
        val inText = mode == Mode.TEXT
        var pass = 0
        var palmStreak = 0
        renderer.analysisEveryMs = if (inText) 500L else 450L
        renderer.analysis = if (scanQr || scanText || scanDoc || scanPalm || scanCover) { rgba, w, h ->
            val wantText = scanText && (inText || pass++ % 3 == 0) // in Photo, text is only looked for now and then
            scanner.look(rgba, w, h, scanQr, wantText, scanDoc, scanPalm && runJob == null && countdown == null, scanCover) { hit ->
                if (scanCover) tooClose = PortraitRules.tooClose(hit.cover)
                if (scanDoc) docQuad = hit.quad
                if (scanPalm) { // the palm has to stay in view for a few looks in a row, so a passing hand does not take a photo
                    palmStreak = if (hit.palm) palmStreak + 1 else if (palmStreak < 0) palmStreak + 1 else 0
                    if (palmStreak >= 3) { palmStreak = -8; palmHook[0]() } // then a pause, so one palm gives one photo
                }
                if (hit.qr != null) { qr = hit.qr; qrSeenAt = android.os.SystemClock.elapsedRealtime() }
                if (wantText) { if (inText) textOutline = TextRules.outline(hit.lines, hit.chars) else suggestText = hintsOn && TextRules.recommend(hit.lines, hit.chars) }
            }
            Unit
        } else null
        onDispose { renderer.analysis = null }
    }
    LaunchedEffect(mode) { qr = null; textOutline = null; suggestText = false; docQuad = null; tooClose = false }
    LaunchedEffect(qr) { while (qr != null) { delay(500); if (android.os.SystemClock.elapsedRealtime() - qrSeenAt > 6000) qr = null } }
    BackHandler(textResult != null) { textResult = null }
    BackHandler(scanDraft != null && !scanBusy) { scanDraft = null }
    // video depth of field: the renderer blurs where the person mask says background; the mask comes from the scanner thread
    val dofOn = AppSettings.videoDof && mode.style == VideoStyle.NORMAL && facing == Facing.BACK && picked?.highSpeed != true && resumed
    DisposableEffect(dofOn) {
        renderer.dofRadius = 0.03f // about f/2.8
        renderer.dof = dofOn
        renderer.maskSink = if (dofOn) { rgba, w, h -> scanner.mask(rgba, w, h) { bytes, mw, mh -> renderer.setMask(bytes, mw, mh) }; Unit } else null
        glView?.requestRender()
        onDispose { renderer.dof = false; renderer.maskSink = null }
    }
    // Retouch: while a face slider is on (or the ultra wide correction), small copies of the frame go to the face finder; the shader reads its faces
    val faceOn = retouchNow != null && resumed
    DisposableEffect(faceOn, facing) {
        val track = com.claudeborne.camera.face.FaceTrack()
        renderer.faceSink = if (faceOn) { rgba, w, h -> scanner.faces(rgba, w, h) { found -> renderer.faces = track.update(found); glView?.requestRender() }; Unit } else null
        onDispose { renderer.faceSink = null; renderer.faces = emptyList() }
    }
    // the level line: the phone's gravity, only while the line is on show
    val levelOn = (proMode && AppSettings.levelLine) || AppSettings.composeLevel
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
            engine.setExposure(ev + scene.ev) // the camera was reopened: keep the exposure the user set
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

    /** AI Telescope Zoom: the long-zoom photo is redrawn by the Real-ESRGAN model and saved as a second picture. Slow: a bar shows the progress. */
    fun startAi(photo: CameraEngine.Photo) {
        aiProgress = 0f
        scope.launch(Dispatchers.Default) {
            val result = runCatching {
                val src = com.claudeborne.camera.proc.Frames.decode(photo.bytes, photo.jpegOrientation, AI_INPUT_MAX) ?: error("unreadable photo")
                val up = upscale4x(context, src) { p -> aiProgress = p }
                src.recycle()
                com.claudeborne.camera.proc.Frames.jpeg(up, 95).also { up.recycle() }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                aiProgress = null
                if (result == null) say("AI zoom could not finish")
                else PhotoSaver.save(context, result, StillFormat.JPEG, null, false, System.currentTimeMillis()) { r ->
                    r.onSuccess { uri -> say("AI zoom picture saved"); AppSettings.rememberLastPhoto(uri.toString()); scope.launch { thumb = withContext(Dispatchers.IO) { PhotoSaver.thumbnail(context, uri) }?.asImageBitmap() } }
                        .onFailure { say("Could not save the AI zoom picture") }
                }
            }
        }
    }

    // photos arrive on the camera thread; saving continues on its own thread even if this screen goes away
    DisposableEffect(engine) {
        engine.listener = object : CameraEngine.Listener {
            override fun onShutter() {
                if (AppSettings.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
                shutterTick++
            }

            override fun onPhoto(photo: CameraEngine.Photo) {
                screenFlash = false
                collect?.let { it(photo); return }
                if (textModeNow) { // Text mode keeps no photo: it reads the words and opens the editor
                    readingText = true
                    scope.launch(Dispatchers.IO) {
                        val text = scanner.readPhoto(photo.bytes, photo.jpegOrientation)
                        withContext(Dispatchers.Main) { readingText = false; if (text.isBlank()) say("No text found") else textResult = text }
                    }
                    return
                }
                if (portraitOwnNow && !skipPortrait[0]) { // our own portrait: cut the person out and blur the rest, then save it like any photo
                    readingText = true
                    busyText = "BLURRING THE BACKGROUND"
                    scope.launch(Dispatchers.Default) {
                        val out = runCatching { Portrait.apply(photo.bytes, photo.jpegOrientation, photo.mirrored, AppSettings.portraitF) }.getOrNull()
                        withContext(Dispatchers.Main) {
                            readingText = false
                            skipPortrait[0] = true
                            if (out == null) { say("No person found. Saved without blur."); engine.listener?.onPhoto(photo) } else engine.listener?.onPhoto(CameraEngine.Photo(out, StillFormat.JPEG, 0, false, false))
                        }
                    }
                    return
                }
                skipPortrait[0] = false
                if (AppSettings.aiZoom && aiProgress == null && photo.format != StillFormat.DNG && facing == Facing.BACK && zoom >= 9.5f && !launchNow.secure) startAi(photo)
                if (scanModeNow) { // Scan Docs keeps no photo either: the picture goes to the page editor
                    readingText = true
                    scope.launch(Dispatchers.Default) {
                        val draft = runCatching { makeDraft(photo.bytes, photo.jpegOrientation) }.getOrNull()
                        withContext(Dispatchers.Main) { readingText = false; if (draft == null) say("Could not read the page") else scanDraft = draft }
                    }
                    return
                }
                val exif = if (photo.mirrored) PhotoRules.exifOrientation(photo.jpegOrientation, mirrored = true) else null
                val mark = if (photo.format == StillFormat.DNG) null else watermarkNow(false)
                val edit = if (photo.format == StillFormat.DNG || (lookNow.isIdentity && mark == null)) null else PhotoEdit(lookNow, mark?.let { m -> { c, w, h -> Watermark.draw(c, w, h, m) } })
                val clip = liveClip.also { liveClip = null }
                val saveWith = { mp4: ByteArray? ->
                  PhotoSaver.save(context, photo.bytes, photo.format, exif, photo.squareCrop, System.currentTimeMillis(), edit, mp4) { result ->
                    result.onSuccess { uri ->
                        AppSettings.rememberLastPhoto(uri.toString())
                        if (launchNow.secure) sessionShots.add(SessionShot(uri, false))
                        scope.launch { thumb = withContext(Dispatchers.IO) { PhotoSaver.thumbnail(context, uri) }?.asImageBitmap() }
                        if (launchNow.returnsResult) onResultNow(uri) // another app asked for this one photo
                    }.onFailure { say(if (it is java.io.IOException && !PhotoSaver.hasRoom()) "Storage is full" else "Could not save the photo") }
                  }
                }
                if (clip != null) scope.launch { saveWith(runCatching { clip.await() }.getOrNull()) } else saveWith(null)
            }

            override fun onFailed(message: String) {
                screenFlash = false
                say(message)
            }

            override fun onExtensionRefused(message: String) {
                AppSettings.nightAlways = false
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
        engine.setExposure(ev + scene.ev)
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
    val closeUp = !front && (engine.info?.zoomMin ?: 1f) < 0.95f
    fun enterMacro() { macro = true; engine.info?.let { goTo(it.zoomMin) }; say("Macro") }
    fun leaveMacro() { macro = false; goTo(wide); say("Macro: Off") }
    // auto macro: the main lens cannot focus closer than about 10 cm; a subject that close and out of focus for a moment switches to the ultra wide lens
    LaunchedEffect(mode, facing, resumed) {
        var close = 0
        while (true) {
            delay(300)
            val near = mode == Mode.PHOTO && AppSettings.autoMacro && closeUp && !macro && zoom in 0.95f..2.5f && !recording &&
                engine.state == CameraEngine.State.Streaming && engine.liveFocus >= 9f && engine.focus != CameraEngine.Focus.FOCUSED
            close = if (near) close + 1 else 0
            if (close >= 4) { close = 0; enterMacro() }
        }
    }
    LaunchedEffect(zoom) { if (macro && zoom > 1.0f) macro = false }
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
        val live = ring
        liveClip = if (live != null && format == MasterFormat.JPG && !front) scope.async(Dispatchers.Default) { delay(1500); live.mp4(java.io.File(context.cacheDir, "share").apply { mkdirs() }) } else null
        engine.takePhotoAndWait(CameraEngine.Shot(flashMode, PhotoRules.jpegOrientation(i.sensorOrientation, deviceDegrees, front), place, squareCrop = aspect == Aspect.R1_1, format = format))
        screenFlash = false
        return true
    }

    /** The result of a multi-picture feature goes through the normal photo path (look, watermark, saving, thumbnail). Its pixels are already upright. */
    fun saveMerged(jpeg: ByteArray) { engine.listener?.onPhoto(CameraEngine.Photo(jpeg, StillFormat.JPEG, 0, mirrored = false, squareCrop = false)) }

    /** Auto HDR: three exposures (normal, dark, bright) fused into one picture. */
    suspend fun hdrShot() {
        val frames = mutableListOf<ByteArray>()
        var turn = 0
        collect = { p -> synchronized(frames) { frames.add(p.bytes) }; turn = p.jpegOrientation }
        val base = ev
        try {
            for (d in listOf(0f, -2f, 2f)) {
                engine.setExposure(base + d + scene.ev)
                if (d != 0f) delay(350) // the camera needs a few frames to settle on the new exposure
                if (!captureOnce(useFlash = false)) break
            }
        } finally {
            collect = null
            engine.setExposure(base + scene.ev)
        }
        if (frames.size < 2) return say("HDR could not take its pictures")
        busyText = "MERGING HDR"; readingText = true
        val merged = withContext(Dispatchers.Default) { runCatching { HdrMerge.merge(frames, turn) }.getOrNull() }
        readingText = false
        if (merged != null) saveMerged(merged)
        else { say("HDR failed. Saving the normal picture."); engine.listener?.onPhoto(CameraEngine.Photo(frames[0], StillFormat.JPEG, turn, false, false)) }
    }

    /** Long exposure: pictures are taken for the chosen time and stacked (average, or brightest pixel for light trails). Tap again to finish early. */
    suspend fun longExpShot() {
        val stack = FrameStack(AppSettings.lexpTrails)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        var turn = 0
        collect = { p -> turn = p.jpegOrientation; val bytes = p.bytes; val rot = p.jpegOrientation; pool.execute { runCatching { stack.add(bytes, rot) } } }
        val total = AppSettings.lexpSec * 1000L
        val start = android.os.SystemClock.elapsedRealtime()
        var n = 0
        try {
            while (currentCoroutineContext().isActive && !finishFlag) {
                val used = android.os.SystemClock.elapsedRealtime() - start
                sweepProgress = (used / total.toFloat()).coerceAtMost(1f)
                if (used >= total && n >= 2) break
                if (!captureOnce(useFlash = false)) break
                n++; runCount = n
            }
        } finally {
            collect = null
            sweepProgress = null
        }
        if (n < 2) { pool.shutdown(); return say("Too few pictures. Try a longer time.") }
        busyText = "STACKING"; readingText = true
        val result = withContext(Dispatchers.Default) { pool.submit {}.get(); pool.shutdown(); runCatching { stack.result() }.getOrNull() }
        readingText = false
        if (result != null) saveMerged(result) else say("Could not stack the pictures")
    }

    /** Long night: about eight frames, a little brighter than the camera would choose, lined up and averaged. Tap again to finish early. */
    suspend fun nightLongShot() {
        val stack = NightStack()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        collect = { p -> val bytes = p.bytes; val rot = p.jpegOrientation; pool.execute { runCatching { stack.add(bytes, rot) } } }
        val base = ev
        var n = 0
        try {
            engine.setExposure(base + 1.5f)
            delay(400) // the camera settles on the brighter exposure
            while (currentCoroutineContext().isActive && !finishFlag && n < 8) {
                sweepProgress = n / 8f
                if (!captureOnce(useFlash = false)) break
                n++; runCount = n
            }
        } finally {
            collect = null
            sweepProgress = null
            engine.setExposure(base + scene.ev)
        }
        if (n < 3) { pool.shutdown(); return say("Too few pictures. Hold still and try again.") }
        busyText = "MERGING THE NIGHT SHOT"; readingText = true
        val result = withContext(Dispatchers.Default) { pool.submit {}.get(); pool.shutdown(); runCatching { stack.result() }.getOrNull() }
        readingText = false
        if (result != null) saveMerged(result) else say("Could not merge the pictures")
    }

    /** Panorama: the gyroscope says when the phone has turned a step; a picture is taken each step. Tap again to stitch. */
    suspend fun panoShot() {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val gyro = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) ?: return say("This phone has no gyroscope")
        var since = 0f
        var tooFast = false
        var lastNs = 0L
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                if (lastNs != 0L) since += SweepRules.turned(e.values[0], e.values[1], (e.timestamp - lastNs) / 1e9f)
                lastNs = e.timestamp
                tooFast = SweepRules.tooFast(e.values[0], e.values[1])
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, accuracy: Int) {}
        }
        val frames = mutableListOf<ByteArray>()
        var turn = 0
        collect = { p -> synchronized(frames) { frames.add(p.bytes) }; turn = p.jpegOrientation }
        sensors.registerListener(listener, gyro, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        try {
            if (!captureOnce(useFlash = false)) return say("The camera is not ready")
            since = 0f
            runCount = 1
            var warned = 0L
            while (currentCoroutineContext().isActive && !finishFlag && frames.size < SweepRules.MAX_FRAMES) {
                delay(40)
                sweepProgress = frames.size / SweepRules.MAX_FRAMES.toFloat()
                if (tooFast && android.os.SystemClock.elapsedRealtime() - warned > 1500) { warned = android.os.SystemClock.elapsedRealtime(); say("Slower") }
                if (SweepRules.takeNext(since) && !tooFast) {
                    if (!captureOnce(useFlash = false)) break
                    since = 0f
                    runCount = frames.size
                }
            }
        } finally {
            sensors.unregisterListener(listener)
            collect = null
            sweepProgress = null
        }
        if (frames.size < 2) return say("Turn the phone slowly while the bar moves, then tap again")
        busyText = "STITCHING"; readingText = true
        val result = withContext(Dispatchers.Default) { Panorama.stitch(frames, turn) }
        readingText = false
        when (result) {
            is Panorama.Result.Done -> saveMerged(result.jpeg)
            is Panorama.Result.Failed -> say(result.reason)
        }
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
        recorderB?.let { b -> recorderB = null; renderer.stopClip { }; b.stop(null) }
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
                renderer.startEncoding(PreviewRenderer.Encoding(surface, fw, fh, turn, isFront && AppSettings.mirrorSelfie, s.hdr != null, rec::gate, log = mode == Mode.PROVIDEO && AppSettings.logProfile, overlay = watermark(true)?.let { Watermark.overlay(fw, fh, it) }), begin)
            }
        }
    }

    /** Dual view: records the composite as one file, or the rear and the front camera as two files, from the same GL frames. */
    fun beginDualRecording() {
        if (!multiReady || recording || savingVideo) return
        if (heat == Heat.TOO_HOT) return say("The phone is too hot. Let it cool down.")
        if (!PhotoSaver.hasRoom(200L * 1024 * 1024)) return say("Storage is full")
        val separate = AppSettings.dualSeparate && mode == Mode.DUAL
        // 1080 wide when an encoder can do it, else 720 wide
        // (a debuggable build, i.e. the emulator with its software GL, only tries 720 so a test finishes)
        val tries = if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) listOf(com.claudeborne.camera.video.Res.R720) else listOf(com.claudeborne.camera.video.Res.R1080, com.claudeborne.camera.video.Res.R720)
        val res = tries.firstOrNull { Encoders.codecFor(AppSettings.hevc, it, 30) != null }
            ?: return say("This phone cannot record this video size")
        val hevc = Encoders.codecFor(AppSettings.hevc, res, 30) == true
        val place = if (AppSettings.locationTags) LastLocation.get(context) else null
        // combined: the whole picture, 9:16; separate: each camera whole, 3:4
        val fw = res.height
        val fh = if (separate) fw * 4 / 3 else res.width
        val bitrate = VideoRules.bitrate(fw, fh, 30, hevc, AppSettings.bitrateHigh, false, Encoders.maxBitrate(hevc))
        fun config(suffix: String) = VideoRecorder.Config(fw, fh, 30, 30, hevc, null, bitrate, true, VideoStyle.NORMAL, AppSettings.lapseSpeed, false, place, nameSuffix = suffix)
        val a = VideoRecorder(context.applicationContext, config(if (separate) "_REAR" else ""), object : VideoRecorder.Listener {
            override fun onFinished(uri: Uri?, error: String?) {
                savingVideo = false
                recorder = null
                if (uri != null) {
                    AppSettings.rememberLast(uri.toString(), true)
                    if (launchNow.secure) sessionShots.add(SessionShot(uri, true))
                    scope.launch { thumb = withContext(Dispatchers.IO) { PhotoSaver.thumbnail(context, uri) }?.asImageBitmap() }
                    say(error ?: if (separate) "Two videos saved" else "Video saved")
                } else if (error != null) say(error)
            }

            override fun onProblem(message: String) { scope.launch { stopRecording("$message. Your video is saved.") } }
        })
        val b = if (separate) VideoRecorder(context.applicationContext, config("_FRONT"), object : VideoRecorder.Listener {
            override fun onFinished(uri: Uri?, error: String?) { if (uri == null && error != null) say(error) }
            override fun onProblem(message: String) { scope.launch { stopRecording("$message. Your videos are saved.") } }
        }) else null
        recorder = a
        recOption = null
        recTurn = 0
        recSize = fw to fh
        recHdr = false
        recDirect = false
        recMs = 0
        savingVideo = true
        scope.launch {
            val madeA = withContext(Dispatchers.IO) { a.prepare() }
            val madeB = if (b != null) withContext(Dispatchers.IO) { b.prepare() } else null
            val surfaceA = madeA.getOrNull()
            val surfaceB = madeB?.getOrNull()
            if (surfaceA == null || (b != null && surfaceB == null)) {
                a.cancel(); b?.cancel()
                savingVideo = false; recorder = null
                val why = (madeA.exceptionOrNull() ?: madeB?.exceptionOrNull())?.let { it.toString() + " " + it.stackTraceToString().take(600) }.orEmpty()
                android.util.Log.w("DualRecord", "prepare failed: $why")
                return@launch say(if (why.contains("microphone")) "The microphone could not start" else "The video could not start")
            }
            val begin = { ok: Boolean ->
                if (ok) {
                    a.start(); b?.start()
                    recorderB = b
                    if (AppSettings.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
                    savingVideo = false
                    recording = true
                } else {
                    a.cancel(); b?.cancel()
                    savingVideo = false; recorder = null
                    say("The video encoder could not start")
                }
            }
            val mark = watermark(true)?.let { Watermark.overlay(fw, fh, it) }
            renderer.startEncoding(PreviewRenderer.Encoding(surfaceA, fw, fh, 0, false, false, a::gate, overlay = mark, source = if (separate) 0 else -1)) { ok ->
                if (!ok || b == null || surfaceB == null) begin(ok)
                else renderer.startClip(PreviewRenderer.Encoding(surfaceB, fw, fh, 0, AppSettings.mirrorSelfie, false, b::gate, source = 1)) { okB ->
                    if (!okB) { renderer.stopEncoding { }; begin(false) } else begin(true)
                }
            }
        }
    }

    /** Record button: asks for the microphone first when the video has sound, then starts or stops the recording. */
    fun recordTap() {
        menu = Menu.NONE
        evOpen = false
        qualityOpen = false
        if (recording) return stopRecording()
        if (multi) {
            if (!multiReady || savingVideo) return
            if (!hasMic(context)) { askMic.launch(Manifest.permission.RECORD_AUDIO); return }
            return beginDualRecording()
        }
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
                val motion = ring.takeIf { AppSettings.videoMotion }
                val mp4 = if (motion != null) { delay(1500); motion.mp4(java.io.File(context.cacheDir, "share").apply { mkdirs() }) } else null // the moments after the press
                withContext(Dispatchers.Main) {
                    PhotoSaver.save(context, bytes, StillFormat.JPEG, null, false, System.currentTimeMillis(), null, mp4) { result ->
                        result.onSuccess { uri ->
                            if (launchNow.secure) sessionShots.add(SessionShot(uri, false))
                            if (!recording && !savingVideo) { AppSettings.rememberLastPhoto(uri.toString()); scope.launch { thumb = withContext(Dispatchers.IO) { PhotoSaver.thumbnail(context, uri) }?.asImageBitmap() } }
                        }.onFailure { say(if (it is java.io.IOException && !PhotoSaver.hasRoom()) "Storage is full" else "Could not save the photo") }
                    }
                }
            }
        }
    }

    /** Saves one JPEG of Dual Photo (composite or one camera) with its file name suffix; [exif] is for the mirrored selfie. */
    fun saveDualJpeg(bytes: ByteArray, exif: Int?, time: Long, suffix: String, last: Boolean) {
        PhotoSaver.save(context, bytes, StillFormat.JPEG, exif, false, time, null, null, suffix) { result ->
            result.onSuccess { uri ->
                if (launchNow.secure) sessionShots.add(SessionShot(uri, false))
                if (last) {
                    AppSettings.rememberLastPhoto(uri.toString())
                    scope.launch { thumb = withContext(Dispatchers.IO) { PhotoSaver.thumbnail(context, uri) }?.asImageBitmap() }
                }
            }.onFailure { say(if (it is java.io.IOException && !PhotoSaver.hasRoom()) "Storage is full" else "Could not save the photo") }
        }
    }

    /** Dual Photo: the composite as on screen plus one photo from each camera (full size when the phone allows it), all saved in DCIM/Camera. */
    fun dualCapture() {
        if (!multiReady || viewSize.width == 0 || dualBusy) return
        if (!PhotoSaver.hasRoom(60L * 1024 * 1024)) return say("Storage is full")
        dualBusy = true
        shutterTick++
        if (AppSettings.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        val time = System.currentTimeMillis()
        val mirror = AppSettings.mirrorSelfie
        fun jpeg(bitmap: android.graphics.Bitmap) = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray().also { bitmap.recycle() }
        // 1. the composite (1080 x 1920)
        renderer.snapshot(1080, 1920, 0, false) { composite ->
            scope.launch {
                val bytes = composite?.let { c -> withContext(Dispatchers.IO) { jpeg(c) } }
                if (bytes != null) saveDualJpeg(bytes, null, time, "", last = false) else say("Could not take the photo")
                // 2. one photo from each camera: full size from the cameras' own photo streams, or the screen picture of each camera
                val turn = if (AppSettings.photoAutoRotate) deviceDegrees else 0
                val sensors = renderer.sourceSensors()
                if (multiEngine.stillsActive) {
                    val rearTurn = PhotoRules.jpegOrientation(sensors.getOrElse(0) { 90 }, turn, false)
                    val frontTurn = PhotoRules.jpegOrientation(sensors.getOrElse(1) { 270 }, turn, true)
                    multiEngine.captureStills(rearTurn, frontTurn) { rear, front ->
                        scope.launch {
                            if (rear != null) saveDualJpeg(rear, null, time + 1, "_REAR", last = false)
                            if (front != null) saveDualJpeg(front, if (mirror) PhotoRules.exifOrientation(frontTurn, true) else null, time + 2, "_FRONT", last = true)
                            if (rear == null && front == null) say("The full-size photos could not be taken")
                            dualBusy = false
                        }
                    }
                } else {
                    renderer.snapshot(1080, 1440, 0, false, source = 0) { rearBitmap ->
                        scope.launch {
                            rearBitmap?.let { b -> saveDualJpeg(withContext(Dispatchers.IO) { jpeg(b) }, null, time + 1, "_REAR", last = false) }
                            renderer.snapshot(1080, 1440, 0, mirror, source = 1) { frontBitmap ->
                                scope.launch {
                                    frontBitmap?.let { b -> saveDualJpeg(withContext(Dispatchers.IO) { jpeg(b) }, null, time + 2, "_FRONT", last = true) }
                                    dualBusy = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Dual and Quad view: one picture of the composite exactly as it is on screen, saved as a JPEG in DCIM/Camera. */
    fun dualPhoto() {
        if (mode == Mode.DUALPHOTO) return dualCapture()
        if (!multiReady || viewSize.width == 0) return
        shutterTick++
        if (AppSettings.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        renderer.snapshot(1080, 1920, 0, false) { bitmap ->
            if (bitmap == null) { say("Could not take the photo"); return@snapshot }
            scope.launch(Dispatchers.IO) {
                val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
                bitmap.recycle()
                withContext(Dispatchers.Main) {
                    PhotoSaver.save(context, bytes, StillFormat.JPEG, null, false, System.currentTimeMillis(), null, null) { result ->
                        result.onSuccess { uri ->
                            if (launchNow.secure) sessionShots.add(SessionShot(uri, false))
                            AppSettings.rememberLastPhoto(uri.toString())
                            scope.launch { thumb = withContext(Dispatchers.IO) { PhotoSaver.thumbnail(context, uri) }?.asImageBitmap() }
                        }.onFailure { say(if (it is java.io.IOException && !PhotoSaver.hasRoom()) "Storage is full" else "Could not save the photo") }
                    }
                }
            }
        }
    }

    // Google Lens: the current frame goes to the Google app; the button exists only where the Google app can take it
    val lensOk = remember { LensShare.available(context) }
    fun openLens() {
        if (viewSize.width == 0) return
        val h = (1080f * (if (boxHeight > 0) boxHeight else viewSize.height) / viewSize.width).toInt().coerceIn(64, 3000)
        renderer.snapshot(1080, h, 0, false) { bitmap ->
            if (bitmap == null) { say("Could not take the picture"); return@snapshot }
            scope.launch(Dispatchers.IO) {
                val uri = runCatching {
                    val file = java.io.File(context.cacheDir, "share").apply { mkdirs() }.resolve("lens.jpg")
                    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                    bitmap.recycle()
                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (uri == null) say("Could not take the picture")
                    else try { context.startActivity(LensShare.intent(uri)) } catch (_: Exception) { say("Google Lens could not open") }
                }
            }
        }
    }

    fun selectMode(m: Mode) {
        if (m == mode || recording || savingVideo) return
        menu = Menu.NONE
        moreOpen = false; moreEditing = false
        evOpen = false
        qualityOpen = false
        formatOpen = false
        proOpen = null
        focusPoint = null
        focusHeld = false
        if (m == Mode.SLOMO && facing == Facing.FRONT) { facing = Facing.BACK; zoom = wide }
        if (m == Mode.PORTRAIT && facing == Facing.BACK && AppSettings.keep(Keep.PORTRAIT_ZOOM) && AppSettings.portraitZoom > 0) zoom = clampZoom(AppSettings.portraitZoom / 1000f)
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
    fun shutterTap(timer: Int? = null) {
        if (multi) return dualPhoto()
        if (mode.isVideo) return recordTap()
        menu = Menu.NONE
        evOpen = false
        if (runJob != null) { if ((mode == Mode.PANO || mode == Mode.LONGEXP || ownNight) && !finishFlag) { finishFlag = true; return }; runJob?.cancel(); say("Stopped"); return }
        if (engine.state != CameraEngine.State.Streaming || engine.capturing || readingText) return
        if (heat == Heat.TOO_HOT) return say("The phone is too hot. Let it cool down.")
        val timerNow = if (mode.oneShot) 0 else timer ?: timerSec // Text and Scan Docs take one picture at once
        val intervalNow = if (mode.oneShot) 0 else intervalSec
        val multi: (suspend () -> Unit)? = when {
            mode == Mode.PANO -> { { panoShot() } }
            mode == Mode.LONGEXP -> { { longExpShot() } }
            ownNight -> { { nightLongShot() } }
            mode == Mode.PHOTO && AppSettings.autoHdr && extWanted < 0 && facing == Facing.BACK && DocScanner.available && flash == Flash.OFF -> { { hdrShot() } }
            else -> null
        }
        finishFlag = false
        runJob = scope.launch {
            try {
                if (timerNow > 0) {
                    for (s in timerNow downTo 1) { countdown = s; delay(1000) }
                    countdown = null
                }
                if (multi != null) multi()
                else if (intervalNow > 0) {
                    var n = 0
                    while (isActive) {
                        if (!captureOnce(useFlash = true)) break
                        runCount = ++n
                        delay(intervalNow * 1000L)
                    }
                } else {
                    captureOnce(useFlash = true)
                }
            } finally {
                countdown = null
                collect = null
                sweepProgress = null
                finishFlag = false
                runCount = 0
                runJob = null
            }
        }
    }
    palmHook[0] = { if (mode == Mode.PHOTO && runJob == null) { say("Palm seen: 3 s"); shutterTap(3) } }
    fun holdStart() {
        if (mode.isVideo || mode.oneShot || !AppSettings.holdForBurst || runJob != null || timerSec > 0 || intervalSec > 0) return
        if (heat != Heat.OK) return say("No burst while the phone is hot")
        menu = Menu.NONE
        bursting = true
        runJob = scope.launch {
            try {
                var n = 0
                while (isActive) {
                    if (AppSettings.burstClear) { // Clear burst: every frame waits (a moment) for the focus
                        val until = android.os.SystemClock.elapsedRealtime() + 500
                        while (engine.focus != CameraEngine.Focus.FOCUSED && android.os.SystemClock.elapsedRealtime() < until) delay(40)
                    }
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
        if (multi) return
        val picture = toPicture(pos) ?: return
        engine.focusAt(picture.first, picture.second, lock)
        focusPoint = pos
        focusTick++
        if (lock) say("AE/AF lock")
    }

    // volume keys: shutter, zoom, or the phone's volume
    DisposableEffect(Unit) {
        CameraKeys.handler = handler@{ up, first ->
            when (if (CameraKeys.forceShutter) 1 else AppSettings.volumeAction) {
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
                        if (menu != Menu.NONE || evOpen || qualityOpen || moreOpen) { menu = Menu.NONE; evOpen = false; qualityOpen = false; moreOpen = false; moreEditing = false }
                        else if (AppSettings.tapToCapture && !mode.isVideo) shutterTap()
                        else if (!multi) focus(pos, lock = false)
                    },
                    onSwipe = { d -> if (menu == Menu.NONE && !recording && !savingVideo) mode.step(d, shownModes)?.let(::selectMode) },
                    onLongPress = { pos -> if (menu == Menu.NONE) focus(pos, lock = true) },
                    onZoom = { change -> if (multi) Unit else if (mode == Mode.TILTSHIFT) tilt = TiltRules.resize(tilt, change) else { zoomJob?.cancel(); zoom = clampZoom(zoom * change); pinching = true } },
                    onTwist = { deg -> if (mode == Mode.TILTSHIFT) tilt = TiltRules.clamp(tilt.copy(angle = tilt.angle - deg)) },
                    onExposureDrag = { dy -> if (mode == Mode.TILTSHIFT) tilt = TiltRules.clamp(tilt.copy(centre = tilt.centre + dy / boxHeight.coerceAtLeast(1))) else setEv(ev + dy / 150f) },
                    dragEnabled = { focusPoint != null || mode == Mode.TILTSHIFT },
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
            // composition guides and the Tilt-shift band sit over the picture band (never in the saved file)
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(0, boxTop) }.fillMaxWidth().height(with(density) { boxHeight.toDp() })) {
                val grid = GridKind.of(AppSettings.gridKind)
                androidx.compose.animation.AnimatedVisibility(grid != GridKind.NONE, enter = androidx.compose.animation.fadeIn(tween(MOTION_MS)), exit = androidx.compose.animation.fadeOut(tween(MOTION_FAST_MS))) {
                    GridOverlay(if (grid == GridKind.NONE) GridKind.GRID else grid, Modifier.fillMaxSize())
                }
                androidx.compose.animation.AnimatedVisibility(mode == Mode.TEXT && textResult == null, enter = androidx.compose.animation.fadeIn(tween(MOTION_MS)), exit = androidx.compose.animation.fadeOut(tween(MOTION_FAST_MS))) {
                    TextOutline(textOutline, front, Modifier.fillMaxSize())
                }
                androidx.compose.animation.AnimatedVisibility(mode == Mode.SCAN && scanDraft == null, enter = androidx.compose.animation.fadeIn(tween(MOTION_MS)), exit = androidx.compose.animation.fadeOut(tween(MOTION_FAST_MS))) {
                    QuadOutline(docQuad, front, Modifier.fillMaxSize())
                }
                androidx.compose.animation.AnimatedVisibility(mode == Mode.TILTSHIFT, enter = androidx.compose.animation.fadeIn(tween(MOTION_MS)), exit = androidx.compose.animation.fadeOut(tween(MOTION_FAST_MS))) {
                    TiltBand(tilt, Modifier.fillMaxSize())
                }
            }
            if (mode.dual && dualLayout == Layout.PIP && multiReady) dualTiles.firstOrNull { it.border }?.let { small ->
                PipHandle(
                    small, boxTop, viewSize.width, boxHeight,
                    onMove = { dx, dy -> pip = DualRules.clamp(pip.copy(cx = pip.cx + dx, cy = pip.cy + dy), dualOutAspect) },
                    onScale = { f -> pip = DualRules.clamp(pip.copy(w = pip.w * f), dualOutAspect) },
                    onDrag = { pipDragging = it }, onTap = { dualSwap = !dualSwap },
                )
            }
            AnimatedOverlay(focusPoint, enter = popEnter, exit = popExit) { FocusBox(it, focusTick, engine.focus, engine.locked, ev, viewSize.width) }
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Visible(!multi, Modifier.align(Alignment.TopStart).padding(16.dp), enter = popEnter, exit = popExit) { TopPill(
                    flash, ev, evOpen, ::cycleFlash,
                    { if (manualExposure) say("EV is off while ISO and shutter are set by hand") else { evOpen = !evOpen; menu = Menu.NONE; qualityOpen = false; formatOpen = false; proOpen = null } },
                    Modifier, showFlash = !(isVideo && front),
                    live = AppSettings.livePhoto.takeIf { mode == Mode.PHOTO && !front && !launch.secure },
                    onLive = { AppSettings.livePhoto = !AppSettings.livePhoto; say("Live photo: ${if (AppSettings.livePhoto) "On" else "Off"}") },
                ) }
                Visible(histogramOn, Modifier.align(Alignment.TopStart).padding(top = 72.dp, start = 16.dp), enter = popEnter, exit = popExit) { HistogramView(histogramBins) }
                Visible(levelOn, Modifier.align(Alignment.Center), enter = popEnter, exit = popExit) { LevelLine(roll) }
                Visible(!multi, Modifier.align(Alignment.TopEnd).padding(16.dp), enter = popEnter, exit = popExit) { Row(Modifier, Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                    Visible(portraitOwnNow && !readingText, enter = popEnter, exit = popExit) {
                        QualityPill(PortraitRules.label(AppSettings.portraitF), qualityOpen) { qualityOpen = !qualityOpen; menu = Menu.NONE; evOpen = false }
                    }
                    Visible(mode == Mode.NIGHT && nightOk && runJob == null && !readingText, enter = popEnter, exit = popExit) {
                        QualityPill(if (AppSettings.nightLong) "LONG NIGHT" else "PHONE NIGHT", false) { AppSettings.nightLong = !AppSettings.nightLong; say(if (AppSettings.nightLong) "Long night: about 8 frames, hold still" else "Phone's own Night processing") }
                    }
                    Visible(mode == Mode.LONGEXP && runJob == null && !readingText, enter = popEnter, exit = popExit) {
                        QualityPill("${AppSettings.lexpSec} S · ${if (AppSettings.lexpTrails) "TRAILS" else "SMOOTH"}", qualityOpen) { qualityOpen = !qualityOpen; menu = Menu.NONE; evOpen = false }
                    }
                    Visible(lensOk && (mode == Mode.PHOTO || mode == Mode.TEXT) && !launch.secure, enter = popEnter, exit = popExit) {
                        RoundButton(onClick = ::openLens) { CbIcon(Icon.SEARCH, Color.White, size = 24.dp) }
                    }
                    Visible(isMaster && proCaps?.raw == true, enter = popEnter, exit = popExit) {
                        QualityPill(masterFormat.label, formatOpen) { formatOpen = !formatOpen; menu = Menu.NONE; evOpen = false; proOpen = null }
                    }
                    Visible(isVideo && picked != null && !recording && !savingVideo, enter = popEnter, exit = popExit) {
                        QualityPill(
                            if (mode == Mode.TIMELAPSE) TimeLapse.label(AppSettings.lapseSpeed) else picked?.label.orEmpty(), qualityOpen,
                        ) { qualityOpen = !qualityOpen; menu = Menu.NONE; evOpen = false }
                    }
                    RoundButton(onClick = { menu = if (menu == Menu.NONE) Menu.MAIN else Menu.NONE; moreOpen = false; moreEditing = false; evOpen = false; qualityOpen = false; formatOpen = false; proOpen = null }) {
                        CbIcon(Icon.SIX_DOT, if (menu != Menu.NONE) DarkCb.mint else Color.White, size = 24.dp)
                    }
                } }
                Column(Modifier.align(Alignment.TopCenter).padding(top = 74.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Visible(recording, enter = popEnter, exit = popExit) {
                        val extra = if (mode == Mode.TIMELAPSE) "CLIP " + VideoRules.timer((TimeLapse.clipSeconds(recMs / 1000, AppSettings.lapseSpeed) * 1000).toLong()) else null
                        RecordTimer(recMs, recPaused, extra)
                    }
                    Toast(toast)
                    Visible(engine.capturing && extWanted >= 0, enter = popEnter, exit = popExit) { WorkingLine("NIGHT SHOT · HOLD STILL", null) }
                    Visible(runJob != null && sweepProgress != null, enter = popEnter, exit = popExit) {
                        WorkingLine(if (mode == Mode.PANO) "SWEEP SLOWLY · TAP TO FINISH" else "LONG EXPOSURE · HOLD STILL · TAP TO FINISH".let { if (ownNight) "LONG NIGHT · HOLD STILL · TAP TO FINISH" else it }, sweepProgress)
                    }
                    HeatChip(heat, video = mode.isVideo)
                    Visible(mode == Mode.PROVIDEO && recording, enter = popEnter, exit = popExit) { AudioMeter(micLevel) }
                }
                Visible(formatOpen && isMaster, Modifier.align(Alignment.TopEnd).padding(top = 72.dp, start = 16.dp, end = 16.dp), enter = popEnter, exit = popExit) {
                    QualityPanel(listOf(ChipRow("PHOTO FORMAT", MasterFormat.entries.map { f -> Chip(f.label, f == masterFormat) { AppSettings.masterFormat = f.ordinal; formatOpen = false; say("Format: ${f.label}") } })))
                }
                Visible(qualityOpen && portraitOwnNow, Modifier.align(Alignment.TopEnd).padding(top = 72.dp, start = 16.dp, end = 16.dp), enter = popEnter, exit = popExit) {
                    QualityPanel(listOf(ChipRow("BACKGROUND BLUR (APERTURE)", PortraitRules.F_NUMBERS.indices.map { i -> Chip(PortraitRules.label(i), i == AppSettings.portraitF) { AppSettings.portraitF = i; qualityOpen = false } })))
                }
                Visible(qualityOpen && mode == Mode.LONGEXP, Modifier.align(Alignment.TopEnd).padding(top = 72.dp, start = 16.dp, end = 16.dp), enter = popEnter, exit = popExit) {
                    QualityPanel(listOf(
                        ChipRow("TIME", listOf(2, 5, 10, 30).map { sec -> Chip("$sec S", sec == AppSettings.lexpSec) { AppSettings.lexpSec = sec } }),
                        ChipRow("LOOK", listOf(Chip("SMOOTH", !AppSettings.lexpTrails) { AppSettings.lexpTrails = false }, Chip("LIGHT TRAILS", AppSettings.lexpTrails) { AppSettings.lexpTrails = true })),
                    ))
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
                Box(Modifier.align(Alignment.TopCenter).padding(top = 124.dp, start = 16.dp, end = 16.dp)) {
                    AnimatedOverlay(qr?.takeIf { mode == Mode.PHOTO && menu == Menu.NONE }, enter = popEnter, exit = popExit) { code ->
                        QrChip(
                            QrRules.kind(code), QrRules.shown(code), QrRules.canOpen(code) && !launch.secure,
                            onOpen = { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { say("Nothing can open this") } },
                            onCopy = { copyText(context, code); say("Copied") },
                            onShare = { shareText(context, code) },
                            onClose = { qr = null },
                        )
                    }
                    Visible(mode == Mode.SCAN && scanPages.isNotEmpty() && scanDraft == null && menu == Menu.NONE, Modifier.align(Alignment.TopCenter), enter = popEnter, exit = popExit) {
                        SuggestChip(if (scanPages.size == 1) "Done · 1 page" else "Done · ${scanPages.size} pages", { scanSaveOpen = true })
                    }
                    Visible(aiProgress != null, enter = popEnter, exit = popExit) { WorkingLine("AI ZOOM · ENHANCING", aiProgress) }
                    Visible(nightOk && hintsOn && (engine.liveIso >= 1600 || engine.liveNs >= 100_000_000L) && mode == Mode.PHOTO && extWanted < 0 && qr == null && !suggestText && menu == Menu.NONE && !recording, Modifier.align(Alignment.TopCenter), enter = popEnter, exit = popExit) {
                        SuggestChip("Try Night mode", { selectMode(Mode.NIGHT) })
                    }
                    Visible(tooClose && mode == Mode.PORTRAIT && menu == Menu.NONE, Modifier.align(Alignment.TopCenter), enter = popEnter, exit = popExit) {
                        SuggestChip("Move further away", {})
                    }
                    Visible(macro && mode == Mode.PHOTO && menu == Menu.NONE, Modifier.align(Alignment.TopCenter), enter = popEnter, exit = popExit) {
                        SuggestChip("Macro · tap to leave", ::leaveMacro)
                    }
                    Visible(suggestText && qr == null && mode == Mode.PHOTO && menu == Menu.NONE && !recording, Modifier.align(Alignment.TopCenter), enter = popEnter, exit = popExit) {
                        SuggestChip("Try Text mode", { selectMode(Mode.TEXT) })
                    }
                }

                Visible(menu == Menu.NONE, Modifier.align(Alignment.BottomCenter), enter = popEnter, exit = popExit) {
                    Column(Modifier.padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // the More grid covers the controls above the strip, as in the stock camera
                        Visible(!moreOpen, enter = popEnter, exit = popExit) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                            Visible(!evOpen && !pinching && proOpen == null, Modifier.fillMaxWidth().padding(horizontal = 16.dp), enter = popEnter, exit = popExit) {
                                Crossfade(multi, animationSpec = tween(MOTION_FAST_MS), label = "bottom row") { multiNow -> if (multiNow) {
                                    if (mode.dual) DualBar(dualLayout, { dualLayout = it; AppSettings.dualLayout = it.ordinal }, { dualSwap = !dualSwap }, enabled = multiReady) else QuadLabel(if (multiReady) quadRear else 0, quadNote)
                                } else Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    if (isMaster) ProSwitch(AppSettings.proOn, { AppSettings.proOn = it; say(if (it) "Pro" else "Auto") }) else if (retouchOk) LookButton(Icon.RETOUCH, retouchBase.sliders) { menu = Menu.RETOUCH; evOpen = false; proOpen = null; qualityOpen = false } else Spacer(Modifier.size(44.dp))
                                    if (facing == Facing.BACK) LensPills(zoom, stops) { pill ->
                                        macro = false
                                        // the active button steps through the extra focal lengths (28 / 35 mm, 243 mm); the others jump
                                        if (pill.selected) ZoomStops.next(pill.index, zoom, wide, AppSettings.extra28, AppSettings.extra35, AppSettings.extra243)?.let(::goTo)
                                        else goTo(pill.stop)
                                    }
                                    when {
                                        isMaster -> LookButton(Icon.STYLES, menu == Menu.STYLES) { menu = Menu.STYLES; evOpen = false; proOpen = null; formatOpen = false }
                                        lookFilterOk -> LookButton(Icon.FILTER, AppSettings.filter > 0) { menu = Menu.FILTERS; evOpen = false; proOpen = null; qualityOpen = false }
                                        else -> Spacer(Modifier.size(44.dp))
                                    }
                                } }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            val normal = mode.style == VideoStyle.NORMAL || multi
                            Crossfade(recording && normal && !recHdr, animationSpec = tween(MOTION_FAST_MS), label = "left slot") { snap ->
                                if (snap) RoundButton(size = 52, onClick = { if (multi) dualPhoto() else snapshot() }) { CbIcon(Icon.CAMERA, Color.White, size = 26.dp) }
                                else Thumbnail(thumb, PhotoSaver.saving > 0 || savingVideo, ::openLast)
                            }
                            Box(contentAlignment = Alignment.Center) {
                                if (mode == Mode.DUALPHOTO) {
                                    Shutter(enabled = multiReady && !dualBusy && heat != Heat.TOO_HOT, running = dualBusy, onTap = ::dualCapture, onHoldStart = {}, onHoldEnd = {})
                                } else if (multi) {
                                    RecordButton(recording, enabled = multiReady && !savingVideo && heat != Heat.TOO_HOT, onClick = ::recordTap)
                                } else if (isVideo) {
                                    RecordButton(recording, enabled = state == CameraEngine.State.Streaming && !savingVideo && heat != Heat.TOO_HOT, onClick = ::recordTap)
                                } else {
                                    Shutter(
                                        enabled = state == CameraEngine.State.Streaming && !engine.capturing && heat != Heat.TOO_HOT, running = runJob != null,
                                        onTap = ::shutterTap, onHoldStart = ::holdStart, onHoldEnd = ::holdEnd,
                                    )
                                }
                                Box(Modifier.align(Alignment.TopEnd).padding(end = 0.dp)) { CountBadge(runCount) }
                            }
                            Crossfade(if (recording) (if (normal && !recDirect) 1 else 2) else if (mode == Mode.SLOMO || mode == Mode.DUALPHOTO) 2 else if (multi) 3 else 0, animationSpec = tween(MOTION_FAST_MS), label = "right slot") { slot ->
                                when (slot) {
                                    0 -> RoundButton(size = 52, onClick = ::flip) { CbIcon(Icon.REFRESH, Color.White, size = 26.dp) }
                                    3 -> RoundButton(size = 52, onClick = ::dualPhoto) { CbIcon(Icon.CAMERA, Color.White, size = 26.dp) }
                                    1 -> RoundButton(size = 52, onClick = ::togglePause) { CbIcon(if (recPaused) Icon.PLAY else Icon.PAUSE, if (recPaused) DarkCb.mint else Color.White, size = 26.dp) }
                                    else -> Spacer(Modifier.size(52.dp))
                                }
                            }
                        }
                        } }
                        Visible(moreOpen, enter = popEnter, exit = popExit) {
                            MoreGrid(
                                shownModes, mode, moreEditing,
                                onPick = { m -> if (m == mode) { moreOpen = false; moreEditing = false } else selectMode(m) },
                                onMove = { m, by -> AppSettings.modeOrder = ModeOrder.encode(ModeOrder.move(ModeOrder.parse(AppSettings.modeOrder), shownModes, m, by)) },
                                onEdit = { moreEditing = !moreEditing },
                            )
                        }
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (shownModes.size > 1) ModeStrip(
                                mode, shownModes, enabled = !recording && !savingVideo, onSelect = ::selectMode,
                                onReselect = { m -> if (m == Mode.VIDEO) { menu = Menu.SCENES; evOpen = false; qualityOpen = false; moreOpen = false } },
                                onMore = { moreOpen = !moreOpen; moreEditing = false; evOpen = false; qualityOpen = false; formatOpen = false; proOpen = null },
                            ) else Spacer(Modifier.height(36.dp))
                        }
                    }
                }
                val menuHolder = remember { arrayOf(Menu.MAIN) } // the open menu is kept while it fades out, so the panel does not turn into the 6-dot menu
                if (menu != Menu.NONE) menuHolder[0] = menu
                val menuShown = menuHolder[0]
                Visible(menu != Menu.NONE, Modifier.align(Alignment.BottomCenter), enter = popEnter, exit = popExit) {
                    if (menuShown == Menu.FILTERS) Box(Modifier.padding(bottom = 16.dp)) {
                        FilterPanel(AppSettings.filter) { AppSettings.filter = it; say(com.claudeborne.camera.look.Filters.all[it].name) }
                    } else if (menuShown == Menu.RETOUCH) Box(Modifier.padding(bottom = 16.dp)) {
                        RetouchPanel(retouchBase) { AppSettings.retouch = com.claudeborne.camera.face.RetouchStore.encode(it); glView?.requestRender() }
                    } else if (menuShown == Menu.STYLES) Box(Modifier.padding(bottom = 16.dp)) {
                        StylesPanel(onChange = { glView?.requestRender() })
                    } else if (menuShown == Menu.SCENES) Box(Modifier.padding(bottom = 16.dp)) {
                        ScenesPanel(AppSettings.scene) { AppSettings.scene = it; menu = Menu.NONE; say("Scene: ${Scene.all[it].name}") }
                    } else if (isVideo) VideoMenuPanel(
                        menuShown, showHdr = plainVideo && hdrChoices.isNotEmpty() && picked?.highSpeed != true && !recording, hdr = AppSettings.videoHdr,
                        showSteady = caps?.previewStabilisation == true && picked?.highSpeed != true && !recording, steady = AppSettings.ultraSteady, focusHeld = focusHeld,
                        onPage = { menu = it },
                        onHdr = { v -> AppSettings.videoHdr = v; menu = Menu.NONE; say(if (v == 0) "10-bit HDR: Off" else "10-bit HDR: ${Hdr.entries[v - 1].label}") },
                        onSteady = { AppSettings.ultraSteady = !AppSettings.ultraSteady; menu = Menu.NONE; say("Ultra Steady: ${if (AppSettings.ultraSteady) "On" else "Off"}") },
                        onFocus = { focusHeld = !focusHeld; engine.holdFocus(focusHeld); menu = Menu.NONE; say("Focus lock: ${if (focusHeld) "On" else "Off"}") },
                        onSettings = { menu = Menu.NONE; onSettings() },
                        hdrChoices = listOf("Off" to 0) + hdrChoices,
                        modifier = Modifier.padding(bottom = 16.dp), showSettings = !launch.secure,
                    ) else MenuPanel(
                        menuShown, aspect, timerSec, intervalSec,
                        onPage = { menu = it },
                        onAspect = { aspect = it; menu = Menu.NONE; say("Aspect ratio: ${it.label}") },
                        onTimer = { timerSec = it; menu = Menu.NONE; say(if (it == 0) "Timer: Off" else "Timer: $it s") },
                        onInterval = { intervalSec = it; menu = Menu.NONE; say(if (it == 0) "Interval shooting: Off" else "Interval: every $it s") },
                        onSettings = { menu = Menu.NONE; onSettings() },
                        modifier = Modifier.padding(bottom = 16.dp), showSettings = !launch.secure,
                        showSoft = mode == Mode.PHOTO || mode == Mode.PORTRAIT, soft = AppSettings.softLight, short = mode.oneShot,
                        showLive = mode == Mode.PHOTO && !front && !launch.secure, live = AppSettings.livePhoto, onLive = { AppSettings.livePhoto = !AppSettings.livePhoto; menu = Menu.NONE; say("Live photo: ${if (AppSettings.livePhoto) "On" else "Off"}") },
                        showAutoHdr = mode == Mode.PHOTO && !front && DocScanner.available, autoHdr = AppSettings.autoHdr, onAutoHdr = { AppSettings.autoHdr = !AppSettings.autoHdr; menu = Menu.NONE; say("Auto HDR: ${if (AppSettings.autoHdr) "On" else "Off"}") },
                        showAction = mode == Mode.PHOTO && info?.actionOk == true, action = action, onAction = { action = !action; menu = Menu.NONE; say("Action mode: ${if (action) "On" else "Off"}") },
                        showMacro = mode == Mode.PHOTO && closeUp, macro = macro, onMacro = { menu = Menu.NONE; if (macro) leaveMacro() else enterMacro() },
                        onSoft = { AppSettings.softLight = it; menu = Menu.NONE; say("Soft light: ${com.claudeborne.camera.look.SoftLight.label(it)}") },
                        master = isMaster, histogram = AppSettings.showHistogram, faceOk = proCaps?.faceDetect == true, face = AppSettings.faceMetering,
                        onHistogram = { AppSettings.showHistogram = !AppSettings.showHistogram; menu = Menu.NONE; say("Histogram: ${if (AppSettings.showHistogram) "On" else "Off"}") },
                        onFace = { AppSettings.faceMetering = !AppSettings.faceMetering; menu = Menu.NONE; say("Face metering: ${if (AppSettings.faceMetering) "On" else "Off"}") },
                    )
                }
            }
            ErrorOverlay(state, onRetry = engine::retry)
            BusyOverlay(readingText, if (mode == Mode.SCAN) "FINDING THE PAGE" else busyText, null)
            AnimatedOverlay(scanDraft, enter = overlayEnter, exit = overlayExit, modifier = Modifier.fillMaxSize()) { draft ->
                fun keep(q: Quad, how: Enhance, finish: Boolean) {
                    scanBusy = true
                    scope.launch(Dispatchers.Default) {
                        val page = runCatching { makePage(draft, q, how) }.getOrNull()
                        withContext(Dispatchers.Main) {
                            scanBusy = false
                            if (page == null) { say("Could not make the page"); return@withContext }
                            scanPages.add(page)
                            scanDraft = null
                            if (finish) scanSaveOpen = true else say("Page ${scanPages.size} kept")
                        }
                    }
                }
                ScanEditor(draft, scanBusy, scanPages.size + 1, onRetake = { scanDraft = null }, onAdd = { q, h -> keep(q, h, false) }, onDone = { q, h -> keep(q, h, true) })
            }
            ScanSaveDialog(
                scanSaveOpen,
                scanPages.size, scanSaving != null, scanSaving,
                onPdf = {
                    scanSaving = 0f
                    val pages = scanPages.toList()
                    scope.launch(Dispatchers.IO) {
                        val result = runCatching { ScanSaver.savePdf(context, pages, System.currentTimeMillis()) { p -> scanSaving = p } }
                        withContext(Dispatchers.Main) {
                            scanSaving = null
                            result.onSuccess { scanPages.clear(); scanSaveOpen = false; say("PDF saved in Documents/Scans") }
                                .onFailure { say(if (it is java.io.IOException && !PhotoSaver.hasRoom()) "Storage is full" else "Could not save the PDF") }
                        }
                    }
                },
                onJpeg = {
                    scanSaving = 0f
                    val pages = scanPages.toList()
                    val now = System.currentTimeMillis()
                    var left = pages.size
                    var failed = false
                    pages.forEach { jpeg ->
                        PhotoSaver.save(context, jpeg, StillFormat.JPEG, null, false, now) { result ->
                            result.onSuccess { uri -> AppSettings.rememberLastPhoto(uri.toString()); scope.launch { thumb = withContext(Dispatchers.IO) { PhotoSaver.thumbnail(context, uri) }?.asImageBitmap() } }
                                .onFailure { failed = true }
                            left--
                            scanSaving = (pages.size - left).toFloat() / pages.size
                            if (left == 0) {
                                scanSaving = null
                                if (failed) say("Some pages could not be saved") else { scanPages.clear(); scanSaveOpen = false; say("Saved in DCIM/Camera") }
                            }
                        }
                    }
                },
                onDiscard = { scanPages.clear(); scanSaveOpen = false },
                onDismiss = { scanSaveOpen = false },
            )
            AnimatedOverlay(textResult, enter = overlayEnter, exit = overlayExit, modifier = Modifier.fillMaxSize()) { text ->
                TextEditor(text, { copyText(context, it); say("Copied") }, { shareText(context, it) }, { textResult = null })
            }
            Visible(viewerOpen, Modifier.fillMaxSize(), enter = overlayEnter, exit = overlayExit) {
                SessionViewer(sessionShots.toList()) { viewerOpen = false }
            }
            var micBlocked by remember { mutableStateOf(false) } // kept while the dialog fades out
            micAsk?.let { micBlocked = it }
            MicDialog(
                micAsk != null, micBlocked, onAllow = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                onSettings = { micAsk = null; context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                onCancel = { micAsk = null },
            )
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

private fun copyText(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("text", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** A photo of a page for the editor: an upright preview of at most 2048 pixels and the page corners found on it (the whole picture when none are found). */
private fun makeDraft(jpeg: ByteArray, rotation: Int): ScanDraft {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 2048) sample *= 2
    val raw = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw java.io.IOException("unreadable photo")
    val upright = if (rotation % 360 == 0) raw else android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true).also { raw.recycle() }
    val found = DocScanner.findQuad(upright)
    return ScanDraft(jpeg, rotation, upright, found ?: Quad.FULL, found != null)
}

/** The kept page: the full-size photo, turned upright, flattened inside the corners, cleaned up, as a JPEG. */
private fun makePage(draft: ScanDraft, quad: Quad, how: Enhance): ByteArray {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(draft.jpeg, 0, draft.jpeg.size, o)
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / sample > 4200) sample *= 2
    val raw = android.graphics.BitmapFactory.decodeByteArray(draft.jpeg, 0, draft.jpeg.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw java.io.IOException("unreadable photo")
    val upright = if (draft.rotation % 360 == 0) raw else android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postRotate(draft.rotation.toFloat()) }, true).also { raw.recycle() }
    val flat = DocScanner.flatten(upright, quad, 3000)
    upright.recycle()
    val clean = DocScanner.enhance(flat, how)
    flat.recycle()
    return java.io.ByteArrayOutputStream().also { clean.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it); clean.recycle() }.toByteArray()
}
