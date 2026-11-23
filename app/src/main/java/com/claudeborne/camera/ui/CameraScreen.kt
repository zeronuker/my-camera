package com.claudeborne.camera.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLSurfaceView
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.claudeborne.camera.camera.CameraEngine
import com.claudeborne.camera.camera.CameraEngine.Facing
import com.claudeborne.camera.camera.ZoomStops
import com.claudeborne.camera.gl.PreviewRenderer
import com.claudeborne.camera.capture.Flash
import com.claudeborne.camera.capture.LastLocation
import com.claudeborne.camera.capture.PhotoRules
import com.claudeborne.camera.capture.PhotoSaver
import com.claudeborne.camera.data.Settings as AppSettings
import android.content.ActivityNotFoundException
import android.media.MediaActionSound
import android.view.OrientationEventListener
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ln

private fun hasCamera(context: Context) =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * The viewfinder (Phase 1, part A): live preview, lens pills, pinch zoom, front/rear flip and the camera's error screens.
 * The camera is opened only while this screen is on show and released the moment it pauses.
 */
@Composable
fun CameraScreen(onSettings: () -> Unit) {
    SystemBarIcons(darkIcons = false)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { CameraEngine(context.applicationContext) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    val renderer = remember { PreviewRenderer(requestRender = { glView?.requestRender() }, onTexture = { texture = it }) }
    var permitted by remember { mutableStateOf(hasCamera(context)) }
    var refused by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(true) }
    var facing by remember { mutableStateOf(Facing.BACK) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pinching by remember { mutableStateOf(false) }
    var showedPicture by remember { mutableStateOf(false) }
    var zoomJob by remember { mutableStateOf<Job?>(null) }
    var flash by remember { mutableStateOf(Flash.OFF) }
    var toast by remember { mutableStateOf<Pair<String, Int>?>(null) } // text and a counter, so the same text can show twice in a row
    var screenFlash by remember { mutableStateOf(false) }
    var shutterTick by remember { mutableIntStateOf(0) }
    var deviceDegrees by remember { mutableIntStateOf(0) }
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    fun say(text: String) { toast = text to (toast?.second ?: 0) + 1 }

    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permitted = ok
        refused = !ok
    }
    LaunchedEffect(Unit) { if (!permitted) askCamera.launch(Manifest.permission.CAMERA) }

    // release the camera when the screen pauses (before onPause returns), reopen when it is back
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { permitted = hasCamera(context); resumed = true; glView?.onResume() }
                Lifecycle.Event.ON_PAUSE -> { resumed = false; showedPicture = false; engine.close(); texture = null; glView?.onPause() }
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
    LaunchedEffect(permitted, resumed, texture, facing) {
        val st = texture
        val view = glView
        if (permitted && resumed && st != null && view != null && view.height > 0) {
            // the long side over the short side (about 2.2 on this phone): camera sizes are listed landscape
            engine.open(facing, st, view.height.toFloat() / view.width,CameraEngine.StillPrefs(AppSettings.heif, AppSettings.ultraHdr, AppSettings.mirrorSelfie))
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
                val exif = if (photo.mirrored) PhotoRules.exifOrientation(photo.jpegOrientation, mirrored = true) else null
                PhotoSaver.save(context, photo.bytes, photo.format, exif, System.currentTimeMillis()) { result ->
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
    LaunchedEffect(toast) { if (toast != null) { delay(1600); toast = null } }
    LaunchedEffect(Unit) { snapshotFlow { zoom }.collect { engine.setZoom(it) } }
    val info = engine.info
    LaunchedEffect(info) {
        info?.let {
            renderer.sensorOrientation = it.sensorOrientation
            renderer.mirror = it.facing == Facing.FRONT
            renderer.bufferWidth = it.previewWidth
            renderer.bufferHeight = it.previewHeight
        }
    }
    val state = engine.state
    LaunchedEffect(state) {
        if (state == CameraEngine.State.Streaming) { renderer.unfreeze(); showedPicture = true }
    }
    LaunchedEffect(zoom, pinching) { if (pinching) { delay(900); pinching = false } }

    val zoomMin = info?.zoomMin ?: 1f
    val zoomMax = info?.zoomMax ?: 1f
    fun flip() {
        renderer.freeze() // keep the last picture while the other camera starts
        zoom = 1f
        flash = Flash.OFF
        engine.setFlash(Flash.OFF)
        facing = if (facing == Facing.BACK) Facing.FRONT else Facing.BACK
    }
    fun cycleFlash() {
        flash = flash.next(rear = facing == Facing.BACK)
        engine.setFlash(flash)
        say("Flash: ${flash.label}")
    }
    fun shoot() {
        val sensor = info?.sensorOrientation ?: return
        if (state != CameraEngine.State.Streaming || engine.capturing) return
        if (!PhotoSaver.hasRoom()) return say("Storage is full")
        val front = facing == Facing.FRONT
        scope.launch {
            if (front && flash == Flash.ON) { screenFlash = true; delay(350) } // let the screen light the face first
            val place = if (AppSettings.locationTags) LastLocation.get(context) else null
            engine.takePhoto(CameraEngine.Shot(flash, PhotoRules.jpegOrientation(sensor, deviceDegrees, front), place))
        }
    }
    fun openLast() {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(AppSettings.lastPhotoUri), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent(view).setPackage(GALLERY))
        } catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(Intent.createChooser(view, "Open with")) }
        }
    }
    fun goTo(ratio: Float) {
        zoomJob?.cancel()
        zoomJob = scope.launch { Animatable(zoom).animateTo(ratio.coerceIn(zoomMin, zoomMax), tween(MOTION_MS)) { zoom = value } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    glView = this
                }
            },
            modifier = Modifier.fillMaxSize().pointerInput(zoomMin, zoomMax) {
                detectTransformGestures { _, _, change, _ ->
                    if (change != 1f) {
                        zoomJob?.cancel()
                        zoom = (zoom * change).coerceIn(zoomMin, zoomMax)
                        pinching = true
                    }
                }
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
            Controls(
                zoom = zoom, zoomMin = zoomMin, zoomMax = zoomMax, front = facing == Facing.FRONT, showRuler = pinching,
                flash = flash, thumb = thumb, saving = PhotoSaver.saving > 0, canShoot = state == CameraEngine.State.Streaming && !engine.capturing,
                onPill = ::goTo, onFlip = ::flip, onSettings = onSettings, onFlash = ::cycleFlash, onShoot = ::shoot, onThumb = ::openLast,
            )
            Toast(toast)
            ErrorOverlay(state, onRetry = engine::retry)
        } else {
            PermissionScreen(refused, context) { askCamera.launch(Manifest.permission.CAMERA) }
        }
    }
}

@Composable
private fun Controls(
    zoom: Float, zoomMin: Float, zoomMax: Float, front: Boolean, showRuler: Boolean,
    flash: Flash, thumb: ImageBitmap?, saving: Boolean, canShoot: Boolean,
    onPill: (Float) -> Unit, onFlip: () -> Unit, onSettings: () -> Unit, onFlash: () -> Unit, onShoot: () -> Unit, onThumb: () -> Unit,
) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(
            Modifier.align(Alignment.TopStart).padding(16.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onFlash),
            Alignment.Center,
        ) {
            val on = flash == Flash.ON || flash == Flash.TORCH
            CbIcon(if (flash == Flash.OFF) Icon.FLASH_OFF else Icon.FLASH, glide(flash == Flash.TORCH, DarkCb.mint, Color.White), filled = on, size = 22.dp)
            if (flash == Flash.AUTO) Text("A", Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 5.dp), style = monoStyle(Color.White, 0.sp).copy(fontSize = 10.sp))
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(16.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onSettings),
            Alignment.Center,
        ) { CbIcon(Icon.GEAR, Color.White, size = 22.dp) }

        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.height(64.dp), Alignment.BottomCenter) {
                Visible(showRuler, enter = popEnter, exit = popExit) { ZoomRuler(zoom, zoomMin, zoomMax) }
                Visible(!showRuler && !front, enter = popEnter, exit = popExit) { LensPills(zoom, onPill) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.size(52.dp), Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
                    Visible(saving, enter = popEnter, exit = popExit) { ProgressBar(null, Modifier.fillMaxWidth()) } // a photo is being written
                    if (thumb != null) {
                        Image(thumb, "Last photo", Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(10.dp)).clickable(onClick = onThumb), contentScale = ContentScale.Crop)
                    }
                }
                Shutter(canShoot, onShoot)
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onFlip), Alignment.Center) {
                    CbIcon(Icon.REFRESH, Color.White, size = 26.dp)
                }
            }
            Box(Modifier.border(1.dp, DarkCb.mint.copy(alpha = 0.5f), CircleShape).padding(horizontal = 22.dp, vertical = 8.dp)) {
                Text("PHOTO", style = monoStyle(DarkCb.mint, 2.2.sp).copy(fontSize = 13.sp))
            }
        }
    }
}

/** Round, as in the stock app; it squishes when pressed and dims while a photo is being taken. */
@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val ink by animateColorAsState(if (enabled) Color.White else Color.White.copy(alpha = 0.55f), tween(MOTION_FAST_MS), label = "shutter")
    Box(Modifier.size(78.dp).pressScale(press, 0.92f).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape).background(ink).clickable(press, null, enabled = enabled, onClick = onClick))
}

/** A short message near the top ("Flash: Off", "Storage is full") that fades in and out. */
@Composable
private fun Toast(message: Pair<String, Int>?) {
    var last by remember { mutableStateOf(message?.first.orEmpty()) }
    if (message != null) last = message.first
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 74.dp), Alignment.TopCenter) {
        Visible(message != null, enter = popEnter, exit = popExit) {
            Text(last.uppercase(), Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 18.dp, vertical = 9.dp), style = monoStyle(DarkCb.ink, 1.5.sp).copy(fontSize = 12.sp))
        }
    }
}

private const val GALLERY = "com.claudeborne.gallerymanager"

@Composable
private fun LensPills(zoom: Float, onPick: (Float) -> Unit) {
    Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).padding(4.dp), Arrangement.spacedBy(2.dp), Alignment.CenterVertically) {
        ZoomStops.pills(zoom).forEach { p ->
            val fill by animateColorAsState(if (p.selected) Color(0x33FFFFFF) else Color.Transparent, tween(MOTION_FAST_MS), label = "pill")
            Box(
                Modifier.height(40.dp).widthIn(min = 40.dp).clip(CircleShape).background(fill).clickable { onPick(p.stop) }.padding(horizontal = 10.dp),
                Alignment.Center,
            ) { Text(p.text, style = monoStyle(glide(p.selected, DarkCb.mint, DarkCb.ink), 0.5.sp).copy(fontSize = 14.sp)) }
        }
    }
}

/** Shown while pinching: the zoom number and a log-scale ruler with the lens stops marked. */
@Composable
private fun ZoomRuler(zoom: Float, min: Float, max: Float) {
    Column(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(ZoomStops.format(zoom) + "x", style = monoStyle(DarkCb.mint, 1.sp).copy(fontSize = 15.sp))
        Canvas(Modifier.width(240.dp).height(22.dp)) {
            val span = ln(max / min).coerceAtLeast(0.01f)
            fun x(r: Float) = size.width * ln(r / min) / span
            val ticks = 40
            for (i in 0..ticks) {
                val at = size.width * i / ticks
                drawLine(Color.White.copy(alpha = 0.35f), Offset(at, size.height * 0.55f), Offset(at, size.height), 1.dp.toPx())
            }
            ZoomStops.rear.filter { it in min..max }.forEach { drawLine(Color.White.copy(alpha = 0.8f), Offset(x(it), size.height * 0.25f), Offset(x(it), size.height), 1.5.dp.toPx()) }
            drawLine(DarkCb.mint, Offset(x(zoom.coerceIn(min, max)), 0f), Offset(x(zoom.coerceIn(min, max)), size.height), 2.dp.toPx())
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

private fun bodyText() = androidx.compose.ui.text.TextStyle(fontFamily = Inter, fontSize = 14.sp, color = DarkCb.ink2, textAlign = TextAlign.Start)
