package com.claudeborne.camera.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.claudeborne.camera.data.CrashLog
import com.claudeborne.camera.data.Probe
import com.claudeborne.camera.data.ProbeReport
import com.claudeborne.camera.data.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Screen { SPLASH, CAMERA, SETTINGS, ABOUT, DIAGNOSTICS }

/** Phase 0 navigation: splash, an empty camera screen, Settings, About, Diagnostics. Each screen fades into the next. */
@Composable
fun Root() {
    var screen by remember { mutableStateOf(Screen.SPLASH) }
    LaunchedEffect(Unit) { delay(700); screen = Screen.CAMERA }
    BackHandler(screen == Screen.SETTINGS || screen == Screen.ABOUT || screen == Screen.DIAGNOSTICS) {
        screen = when (screen) { Screen.DIAGNOSTICS -> Screen.ABOUT; Screen.ABOUT -> Screen.SETTINGS; else -> Screen.CAMERA }
    }
    AnimatedContent(screen, transitionSpec = { fadeIn(tween(MOTION_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS)) }, label = "screen") { s ->
        when (s) {
            Screen.SPLASH -> Box(Modifier.fillMaxSize().background(DarkCb.surface0), Alignment.Center) {
                Text("CAMERA", style = TextStyle(fontFamily = Tourney, fontSize = 34.sp, color = DarkCb.mint, letterSpacing = 4.sp))
            }
            Screen.CAMERA -> CameraScreen { screen = Screen.SETTINGS }
            Screen.SETTINGS -> SettingsScreen({ screen = Screen.CAMERA }, { screen = Screen.ABOUT })
            Screen.ABOUT -> AboutScreen({ screen = Screen.SETTINGS }, { screen = Screen.DIAGNOSTICS })
            Screen.DIAGNOSTICS -> DiagnosticsScreen { screen = Screen.ABOUT }
        }
    }
    UpdateDialog()
}

/** Empty viewfinder for now (Phase 1 builds the camera): always dark, as the stock viewfinder is. */
@Composable
private fun CameraScreen(onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Text("VIEWFINDER COMES IN PHASE 1", Modifier.align(Alignment.Center), style = monoStyle(DarkCb.inkDim, 2.2.sp))
        BrandButton("SETTINGS", Modifier.align(Alignment.TopEnd).padding(12.dp), cb = DarkCb, onClick = onSettings)
    }
}

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.fillMaxSize().cbGrid(cb).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BrandButton("BACK", cb = cb, onClick = onBack)
            Text(title, style = monoStyle(cb.mint, 2.6.sp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.fillMaxWidth().doubleChamferFrame(cb, cb.surface1).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun Body(text: String, color: Color = LocalCb.current.ink2) = Text(text, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = color))

@Composable
private fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit) = Page("SETTINGS", onBack) {
    Body("The General / Photo / Video settings come with the camera in Phase 1.")
    BrandButton("ABOUT CAMERA", Modifier.fillMaxWidth(), onClick = onAbout)
}

@Composable
private fun AboutScreen(onBack: () -> Unit, onDiagnostics: () -> Unit) = Page("ABOUT CAMERA", onBack) {
    val context = LocalContext.current
    val info = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
    val cb = LocalCb.current
    Card {
        Text("VERSION", style = monoStyle(cb.inkDim, 1.sp)); Body(info.versionName ?: "")
        Text("BUILD", style = monoStyle(cb.inkDim, 1.sp)); Body("${info.longVersionCode}")
    }
    BrandButton("CHECK FOR UPDATES", Modifier.fillMaxWidth(), primary = true, enabled = Updater.state !is Updater.State.Checking && Updater.state !is Updater.State.Downloading) {
        Updater.check(context, manual = true)
    }
    when (val s = Updater.state) {
        Updater.State.Checking -> WorkingLine("CHECKING…", color = cb.mint)
        Updater.State.UpToDate -> Body("You have the newest version.")
        is Updater.State.Failed -> Body("Could not check: ${s.reason}", Warn)
        is Updater.State.Downloading -> WorkingLine("DOWNLOADING… ${(s.progress * 100).toInt()}%", s.progress, cb.mint)
        else -> Unit
    }
    BrandButton("DIAGNOSTICS", Modifier.fillMaxWidth(), onClick = onDiagnostics)
}

@Composable
private fun DiagnosticsScreen(onBack: () -> Unit) = Page("DIAGNOSTICS", onBack) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cb = LocalCb.current
    var report by remember { mutableStateOf<ProbeReport?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<File?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val crashes = remember { CrashLog.latest(context) }

    fun run(live: Boolean) {
        working = "STARTING"; problem = null; saved = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.Default) { Probe.collect(context, live) { working = it } }
                report = r
                working = "SAVING"
                saved = withContext(Dispatchers.IO) { Probe.save(context, r) }
            } catch (e: Throwable) {
                problem = e.toString()
            }
            working = null
        }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { run(it) }

    Body("Asks the phone what its cameras, codecs and camera apps can do, and saves the answer as Download/camera-probe.json.")
    BrandButton("RUN PROBE", Modifier.fillMaxWidth(), primary = true, enabled = working == null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) run(true) else askCamera.launch(Manifest.permission.CAMERA)
    }
    working?.let { WorkingLine(it, color = cb.mint) }
    problem?.let { Body("Probe failed: $it", Warn) }
    report?.let { r ->
        Card { r.lines.forEach { Text(it, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, color = cb.ink2)) } }
        saved?.let { f ->
            Body("Saved to Download/camera-probe.json.", cb.mint)
            BrandButton("SHARE REPORT", Modifier.fillMaxWidth()) { share(context, f, "application/json") }
        }
    }

    Text("CRASH LOG", style = monoStyle(cb.inkDim, 2.2.sp))
    if (crashes.isEmpty()) Body("No crashes recorded.") else {
        crashes.take(3).forEach { f -> Card { Text(f.name, style = monoStyle(cb.mint, 1.sp)); Text(f.readText().lines().take(8).joinToString("\n"), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, color = cb.ink2)) } }
        BrandButton("SHARE LATEST CRASH", Modifier.fillMaxWidth()) {
            share(context, File(context.cacheDir, "share").apply { mkdirs() }.resolve("crash.txt").also { it.writeText(crashes.first().readText()) }, "text/plain")
        }
    }
}

/** Hands a cache file to Android's share sheet. */
private fun share(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"),
    )
}
