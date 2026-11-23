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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun hasCamera(context: Context) =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

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
fun CameraScreen(onSettings: () -> Unit) {
    SystemBarIcons(darkIcons = false)
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val engine = remember { CameraEngine(context.applicationContext) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    val renderer = remember { PreviewRenderer(requestRender = { glView?.requestRender() }, onTexture = { texture = it }) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var permitted by remember { mutableStateOf(hasCamera(context)) }
    var refused by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(true) }
    var facing by remember { mutableStateOf(Facing.BACK) }
    var zoom by remember { mutableFloatStateOf(1f) }
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
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    fun say(text: String) { toast = text to ((toast?.second ?: 0) + 1) }

    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permitted = ok
        refused = !ok
    }
    LaunchedEffect(Unit) { if (!permitted) askCamera.launch(Manifest.permission.CAMERA) }

    // the viewfinder band: the whole screen for "Full", otherwise a box under the top buttons that glides between shapes
    val topInset = WindowInsets.safeDrawing.getTop(density)
    val reserve = with(density) { 64.dp.roundToPx() }
    val targetTop = if (aspect == Aspect.FULL) 0 else topInset + reserve
    val targetHeight = if (aspect == Aspect.FULL) viewSize.height else AspectMath.boxHeight(aspect, viewSize.width, (viewSize.height - targetTop).coerceAtLeast(1))
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
                Lifecycle.Event.ON_PAUSE -> { resumed = false; showedPicture = false; runJob?.cancel(); engine.close(); texture = null; glView?.onPause() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            engine.close()
            engine.release()
            sound.release()
        }
    }
    LaunchedEffect(permitted, resumed, texture, facing, aspect) {
        val st = texture
        val view = glView
        if (permitted && resumed && st != null && view != null && view.height > 0) {
            // camera sizes are listed landscape, so the screen shape is its long side over its short side (about 2.2 on this phone)
            engine.open(facing, st, AspectMath.captureAspect(aspect, view.height.toFloat() / view.width), CameraEngine.StillPrefs(AppSettings.heif, AppSettings.ultraHdr, AppSettings.mirrorSelfie))
        }
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
                        scope.launch { thumb = withContext(Dispatchers.IO) { PhotoSaver.thumbnail(context, uri) }?.asImageBitmap() }
                    }.onFailure { say(if (it is java.io.IOException && !PhotoSaver.hasRoom()) "Storage is full" else "Could not save the photo") }
                }
            }

            override fun onFailed(message: String) {
                screenFlash = false
                say(message)
            }
        }
        onDispose { engine.listener = null }
    }
    LaunchedEffect(Unit) { // the last-shot button after a restart
        val saved = AppSettings.lastPhotoUri
        if (saved.isNotEmpty()) thumb = withContext(Dispatchers.IO) { PhotoSaver.thumbnail(context, Uri.parse(saved)) }?.asImageBitmap()
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
    fun setEv(stops: Float) {
        ev = stops.coerceIn(-Ev.LIMIT, Ev.LIMIT)
        engine.setExposure(ev)
    }
    fun flip() {
        renderer.freeze() // keep the last picture while the other camera starts
        zoom = 1f
        flash = Flash.OFF
        focusPoint = null
        facing = if (facing == Facing.BACK) Facing.FRONT else Facing.BACK
    }
    fun cycleFlash() {
        flash = flash.next(rear = facing == Facing.BACK)
        engine.setFlash(flash)
        say("Flash: ${flash.label}")
    }
    fun goTo(ratio: Float) {
        zoomJob?.cancel()
        zoomJob = scope.launch { Animatable(zoom).animateTo(ratio.coerceIn(zoomMin, zoomMax), tween(MOTION_MS)) { zoom = value } }
    }
    fun openLast() {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(AppSettings.lastPhotoUri), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent(view).setPackage(GALLERY))
        } catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(Intent.createChooser(view, "Open with")) }
        }
    }

    /** Takes one photo and waits for it. Returns false when it could not (camera not ready, storage full). */
    suspend fun captureOnce(useFlash: Boolean): Boolean {
        val i = engine.info ?: return false
        if (engine.state != CameraEngine.State.Streaming) return false
        if (!PhotoSaver.hasRoom()) { say("Storage is full"); return false }
        val front = facing == Facing.FRONT
        val mode = if (useFlash) flash else Flash.OFF
        if (front && mode == Flash.ON) { screenFlash = true; delay(350) } // let the screen light the face first
        val place = if (AppSettings.locationTags) LastLocation.get(context) else null
        engine.takePhotoAndWait(CameraEngine.Shot(mode, PhotoRules.jpegOrientation(i.sensorOrientation, deviceDegrees, front), place, squareCrop = aspect == Aspect.R1_1))
        screenFlash = false
        return true
    }

    /** Shutter tap: a timer run, an interval run or a single photo; a second tap while one is running stops it. */
    fun shutterTap() {
        menu = Menu.NONE
        evOpen = false
        if (runJob != null) { runJob?.cancel(); say("Stopped"); return }
        if (engine.state != CameraEngine.State.Streaming || engine.capturing) return
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
        if (!AppSettings.holdForBurst || runJob != null || timerSec > 0 || intervalSec > 0) return
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
                        if (menu != Menu.NONE || evOpen) { menu = Menu.NONE; evOpen = false }
                        else if (AppSettings.tapToCapture) shutterTap()
                        else focus(pos, lock = false)
                    },
                    onLongPress = { pos -> if (menu == Menu.NONE) focus(pos, lock = true) },
                    onZoom = { change -> zoomJob?.cancel(); zoom = (zoom * change).coerceIn(zoomMin, zoomMax); pinching = true },
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
                TopPill(flash, ev, evOpen, ::cycleFlash, { evOpen = !evOpen; menu = Menu.NONE }, Modifier.align(Alignment.TopStart).padding(16.dp))
                RoundButton(Modifier.align(Alignment.TopEnd).padding(16.dp), onClick = { menu = if (menu == Menu.NONE) Menu.MAIN else Menu.NONE; evOpen = false }) {
                    CbIcon(Icon.SIX_DOT, if (menu != Menu.NONE) DarkCb.mint else Color.White, size = 24.dp)
                }
                Box(Modifier.align(Alignment.TopCenter).padding(top = 74.dp)) { Toast(toast) }
                Box(Modifier.align(Alignment.Center)) { Countdown(countdown) }

                Visible(menu == Menu.NONE, Modifier.align(Alignment.BottomCenter), enter = popEnter, exit = popExit) {
                    Column(Modifier.padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.height(64.dp), Alignment.BottomCenter) {
                            Visible(evOpen, enter = popEnter, exit = popExit) { EvDial(ev, ::setEv) { setEv(0f) } }
                            Visible(!evOpen && pinching, enter = popEnter, exit = popExit) { ZoomRuler(zoom, zoomMin, zoomMax) }
                            Visible(!evOpen && !pinching && facing == Facing.BACK, enter = popEnter, exit = popExit) { LensPills(zoom, ::goTo) }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Thumbnail(thumb, PhotoSaver.saving > 0, ::openLast)
                            Box(contentAlignment = Alignment.Center) {
                                Shutter(
                                    enabled = state == CameraEngine.State.Streaming && !engine.capturing, running = runJob != null,
                                    onTap = ::shutterTap, onHoldStart = ::holdStart, onHoldEnd = ::holdEnd,
                                )
                                Box(Modifier.align(Alignment.TopEnd).padding(end = 0.dp)) { CountBadge(runCount) }
                            }
                            RoundButton(size = 52, onClick = ::flip) { CbIcon(Icon.REFRESH, Color.White, size = 26.dp) }
                        }
                        Box(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
                            Text("PHOTO", style = monoStyle(DarkCb.mint, 2.2.sp).copy(fontSize = 13.sp))
                        }
                    }
                }
                Visible(menu != Menu.NONE, Modifier.align(Alignment.BottomCenter), enter = popEnter, exit = popExit) {
                    MenuPanel(
                        menu, aspect, timerSec, intervalSec,
                        onPage = { menu = it },
                        onAspect = { aspect = it; menu = Menu.NONE; say("Aspect ratio: ${it.label}") },
                        onTimer = { timerSec = it; menu = Menu.NONE; say(if (it == 0) "Timer: Off" else "Timer: $it s") },
                        onInterval = { intervalSec = it; menu = Menu.NONE; say(if (it == 0) "Interval shooting: Off" else "Interval: every $it s") },
                        onSettings = { menu = Menu.NONE; onSettings() },
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
            }
            ErrorOverlay(state, onRetry = engine::retry)
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
