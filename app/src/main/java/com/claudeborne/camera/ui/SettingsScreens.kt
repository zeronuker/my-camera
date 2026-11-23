package com.claudeborne.camera.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.camera.camera.ZoomStops
import com.claudeborne.camera.capture.Watermark
import com.claudeborne.camera.look.GridKind
import com.claudeborne.camera.video.Encoders
import com.claudeborne.camera.data.Settings as AppSettings

private enum class SettingsPage { ROOT, FORMATS, HOLD, METHODS, LENS, VOLUME, ORIENTATION, ADAPTIVE, BITRATE, WATERMARK, WM_TARGET, WM_SIZE, WM_POSITION, COMPOSITION, GRID, SCANNING }

private val VOLUME_ACTIONS = listOf("Volume", "Shutter", "Zoom")
private val ORIENTATIONS = listOf("Follow system", "Auto-rotate")
private val ADAPTIVE = listOf("Off", "For 30 fps videos", "For 30 fps and 60 fps videos")

/**
 * Settings as in the stock app: General / Photo / Video tabs of grouped cards, with sub-pages for the choices. Only rows for features
 * that exist are shown; rows for later phases (watermark, composition, scanning, video…) appear when those phases are built.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit) {
    var page by remember { mutableStateOf(SettingsPage.ROOT) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(page != SettingsPage.ROOT) { page = SettingsPage.ROOT }
    AnimatedContent(
        page,
        transitionSpec = {
            if (targetState == SettingsPage.ROOT) fadeIn(tween(MOTION_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS))
            else pushEnter togetherWith fadeOut(tween(MOTION_FAST_MS))
        },
        label = "settings page",
    ) { p ->
        val up = { page = SettingsPage.ROOT }
        when (p) {
            SettingsPage.ROOT -> Page("SETTINGS", onBack) {
                SegmentedTabs(listOf("General", "Photo", "Video"), tab) { tab = it }
                AnimatedContent(tab, transitionSpec = { fadeIn(tween(MOTION_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS)) }, label = "tab content") { t ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        when (t) {
                            0 -> GeneralTab({ page = it }, onAbout)
                            1 -> PhotoTab { page = it }
                            else -> VideoTab { page = it }
                        }
                    }
                }
            }
            SettingsPage.FORMATS -> Page("FORMATS", up) {
                Group(
                    "Photo",
                    "Ultra HDR keeps extra brightness data for HDR screens and is still a normal JPEG. Mirrored selfies are always plain JPEG.",
                ) { row { SwitchRow("Ultra HDR", AppSettings.ultraHdr, { AppSettings.ultraHdr = it }) } }
                Group(footer = "Smaller files at the same quality (HEIF). When on, it is used instead of Ultra HDR. Some apps cannot open HEIF photos.") {
                    row { SwitchRow("High efficiency image", AppSettings.heif, { AppSettings.heif = it }) }
                }
                if (Encoders.hasHevc()) Group(
                    "Video", "Smaller video files at the same quality (HEVC). Turn it off if another app cannot play your videos. 10-bit HDR video always uses HEVC.",
                ) { row { SwitchRow("High efficiency video", AppSettings.hevc, { AppSettings.hevc = it }) } }
            }
            SettingsPage.HOLD -> Page("TOUCH AND HOLD SHUTTER", up) {
                Group(footer = "Keep the shutter pressed to take photos one after another until you let go.") {
                    row { SwitchRow("Take burst photos", AppSettings.holdForBurst, { AppSettings.holdForBurst = it }) }
                }
                Group("Burst mode", "Clear waits for the focus before every frame: sharper frames, a slower burst. Classic is as fast as the camera goes.") {
                    row { RadioRow("Classic", !AppSettings.burstClear, { AppSettings.burstClear = false }) }
                    row { RadioRow("Clear", AppSettings.burstClear, { AppSettings.burstClear = true }) }
                }
            }
            SettingsPage.METHODS -> Page("SHOOTING METHODS", up) {
                Group(footer = "Tap anywhere in the viewfinder to take a photo. While on, tapping no longer sets the focus.") {
                    row { SwitchRow("Tap to capture", AppSettings.tapToCapture, { AppSettings.tapToCapture = it }) }
                }
                if (com.claudeborne.camera.scan.HandScanner.supported) Group(footer = "In Photo mode, hold an open palm facing the camera for a second: a 3 second timer starts, then the photo is taken. Works with the front and the rear camera.") {
                    row { SwitchRow("Show palm", AppSettings.showPalm, { AppSettings.showPalm = it }) }
                }
            }
            SettingsPage.LENS -> Page("LENS FOCAL LENGTH", up) {
                Group("More focal lengths", "Tap the active zoom button again to switch between more focal lengths.") {
                    row { SwitchRow("28 mm", AppSettings.extra28, { AppSettings.extra28 = it }) }
                    row { SwitchRow("35 mm", AppSettings.extra35, { AppSettings.extra35 = it }) }
                    row { SwitchRow("243 mm", AppSettings.extra243, { AppSettings.extra243 = it }) }
                }
                Group("Default wide-angle focal length") {
                    listOf("24 mm", "28 mm", "35 mm").forEachIndexed { i, mm ->
                        row { RadioRow("${ZoomStops.format(ZoomStops.wideRatio(i))}x  ($mm)", AppSettings.defaultWide == i, { AppSettings.defaultWide = i }) }
                    }
                }
            }
            SettingsPage.VOLUME -> Page("VOLUME BUTTON ACTION", up) {
                Group {
                    VOLUME_ACTIONS.forEachIndexed { i, name -> row { RadioRow(name, AppSettings.volumeAction == i, { AppSettings.volumeAction = i }) } }
                }
            }
            SettingsPage.ADAPTIVE -> Page("ADAPTIVE FRAME RATE", up) {
                Group(footer = "In dim light the camera may slow down to get brighter pictures. The video file keeps the right speed.") {
                    ADAPTIVE.forEachIndexed { i, name -> row { RadioRow(name, AppSettings.adaptiveFps == i, { AppSettings.adaptiveFps = i }) } }
                }
            }
            SettingsPage.BITRATE -> Page("BITRATE", up) {
                Group(footer = "High quality uses more storage for a sharper picture: about 30 megabits a second for 4K at 30 fps with HEVC, 19 for Standard.") {
                    row { RadioRow("Standard", !AppSettings.bitrateHigh, { AppSettings.bitrateHigh = false }) }
                    row { RadioRow("High quality", AppSettings.bitrateHigh, { AppSettings.bitrateHigh = true }) }
                }
            }
            SettingsPage.WATERMARK -> Page("WATERMARK", up) { WatermarkPage { page = it } }
            SettingsPage.WM_TARGET -> Page("ADD WATERMARK TO", { page = SettingsPage.WATERMARK }) {
                Group(footer = "Not in slow motion (120 and 240 fps): the camera writes those videos straight to the file.") {
                    Watermark.TARGETS.forEachIndexed { i, name -> row { RadioRow(name, AppSettings.wmTarget == i, { AppSettings.wmTarget = i }) } }
                }
            }
            SettingsPage.WM_SIZE -> Page("FONT SIZE", { page = SettingsPage.WATERMARK }) {
                Group { Watermark.SIZES.forEachIndexed { i, name -> row { RadioRow(name, AppSettings.wmSize == i, { AppSettings.wmSize = i }) } } }
            }
            SettingsPage.WM_POSITION -> Page("POSITION", { page = SettingsPage.WATERMARK }) {
                Group { Watermark.POSITIONS.forEachIndexed { i, name -> row { RadioRow(name, AppSettings.wmPosition == i, { AppSettings.wmPosition = i }) } } }
            }
            SettingsPage.COMPOSITION -> Page("COMPOSITION ASSISTANCE", up) {
                Group(footer = "Lines over the viewfinder to help you frame a picture. They are not saved in the photo.") {
                    row { NavRow("Grid and guides", GridKind.of(AppSettings.gridKind).label) { page = SettingsPage.GRID } }
                    row { SwitchRow("Level", AppSettings.composeLevel, { AppSettings.composeLevel = it }, "A line that tilts against the phone and turns mint when it is level.") }
                }
            }
            SettingsPage.GRID -> Page("GRID AND GUIDES", { page = SettingsPage.COMPOSITION }) {
                Group { GridKind.entries.forEachIndexed { i, kind -> row { RadioRow(kind.label, AppSettings.gridKind == i, { AppSettings.gridKind = i }) } } }
            }
            SettingsPage.SCANNING -> Page("TEXT AND QR CODE SCANNING", up) {
                Group(footer = "Looks for QR codes in Photo mode and shows what they say; nothing opens until you tap OPEN. Everything is read on the phone.") {
                    row { SwitchRow("QR code scanning", AppSettings.qrScan, { AppSettings.qrScan = it }) }
                }
                Group(footer = "In Photo mode, suggests Text mode when text fills the frame.") {
                    row { SwitchRow("Recommend Text Scanner", AppSettings.recommendText, { AppSettings.recommendText = it }) }
                }
            }
            SettingsPage.ORIENTATION -> Page("PHOTO ORIENTATION", up) {
                Group(footer = "The screen always stays upright; this decides how the saved photo or video is turned.") {
                    row { RadioRow("Follow system", !AppSettings.photoAutoRotate, { AppSettings.photoAutoRotate = false }, "Photos and videos are saved upright for the screen.") }
                    row { RadioRow("Auto-rotate", AppSettings.photoAutoRotate, { AppSettings.photoAutoRotate = true }, "Photos and videos turn with the way you hold the phone.") }
                }
            }
        }
    }
}

@Composable
private fun GeneralTab(open: (SettingsPage) -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    var refused by remember { mutableStateOf(false) }
    fun hasLocation() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ok = result.values.any { it }
        AppSettings.locationTags = ok
        refused = !ok
    }
    Group(
        "General",
        if (refused) "Location access was refused. Allow it in the phone's settings for this app, then switch this on." else null,
    ) {
        row { SwitchRow("Shutter sound", AppSettings.shutterSound, { AppSettings.shutterSound = it }) }
        row { SwitchRow("Mirror selfie", AppSettings.mirrorSelfie, { AppSettings.mirrorSelfie = it }) }
        row {
            SwitchRow("Add location tags", AppSettings.locationTags, { on ->
                refused = false
                if (!on) AppSettings.locationTags = false
                else if (hasLocation()) AppSettings.locationTags = true
                else ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            })
        }
        row { NavRow("Formats") { open(SettingsPage.FORMATS) } }
    }
    Group {
        row { NavRow("Watermark", if (AppSettings.wmOn) "On" else "Off") { open(SettingsPage.WATERMARK) } }
        row { NavRow("Composition assistance") { open(SettingsPage.COMPOSITION) } }
    }
    if (refused) {
        BrandButton("OPEN PHONE SETTINGS", Modifier.fillMaxWidth()) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }
    Group("Handy features") {
        row { NavRow("Volume button action", VOLUME_ACTIONS[AppSettings.volumeAction.coerceIn(0, 2)]) { open(SettingsPage.VOLUME) } }
        row { NavRow("Photo orientation", ORIENTATIONS[if (AppSettings.photoAutoRotate) 1 else 0]) { open(SettingsPage.ORIENTATION) } }
        row { SwitchRow("Smart suggestions", AppSettings.smartSuggestions, { AppSettings.smartSuggestions = it }, "Short hints such as trying Text mode when a page of text is in view.") }
    }
    Group { row { NavRow("About Camera", onClick = onAbout) } }
}

@Composable
private fun VideoTab(open: (SettingsPage) -> Unit) {
    Group(footer = "Videos are recorded with the sound of the microphone (not in slo-mo and time-lapse) and saved in DCIM/Camera.") {
        row { SwitchRow("Lock lens", AppSettings.lockLens, { AppSettings.lockLens = it }, "Do not automatically change lenses while recording videos.") }
        row { SwitchRow("Lock white balance", AppSettings.lockWb, { AppSettings.lockWb = it }, "Do not automatically adjust white balance while recording videos.") }
        row { NavRow("Adaptive frame rate", ADAPTIVE[AppSettings.adaptiveFps.coerceIn(0, 2)]) { open(SettingsPage.ADAPTIVE) } }
        row { NavRow("Bitrate", if (AppSettings.bitrateHigh) "High quality" else "Standard") { open(SettingsPage.BITRATE) } }
    }
}

@Composable
private fun PhotoTab(open: (SettingsPage) -> Unit) {
    Group {
        row { NavRow("Touch and hold shutter button", if (AppSettings.holdForBurst) "Burst" else "Off") { open(SettingsPage.HOLD) } }
        row { NavRow("Shooting methods", listOfNotNull("Tap to capture".takeIf { AppSettings.tapToCapture }, "Show palm".takeIf { AppSettings.showPalm && com.claudeborne.camera.scan.HandScanner.supported }).joinToString(", ").ifEmpty { "Off" }) { open(SettingsPage.METHODS) } }
        row { NavRow("Lens focal length") { open(SettingsPage.LENS) } }
        row { NavRow("Text and QR code scanning", if (AppSettings.qrScan || AppSettings.recommendText) "On" else "Off") { open(SettingsPage.SCANNING) } }
    }
    Group(footer = "Beyond 10x zoom, a second picture is saved after the normal one, redrawn by an AI model (Real-ESRGAN) for finer detail. It takes about a minute; a bar shows the progress. Keep the app open until it is done.") {
        row { SwitchRow("AI Telescope Zoom", AppSettings.aiZoom, { AppSettings.aiZoom = it }) }
    }
    Group(footer = "Switches to the ultra wide lens (it focuses from 3.5 cm) when the subject is too close for the main lens.") {
        row { SwitchRow("Auto macro", AppSettings.autoMacro, { AppSettings.autoMacro = it }) }
    }
    if (com.claudeborne.camera.camera.Extensions.nightAvailable(LocalContext.current)) Group(
        footer = "A test: takes every Photo mode picture with the phone's own Night processing, so you can compare it with the plain camera in daylight. Slower; no Ultra HDR.",
    ) {
        row { SwitchRow("Night processing for all photos", AppSettings.nightAlways, { AppSettings.nightAlways = it }) }
    }
}

/** The watermark settings, as the stock page: on/off, where it goes, what it says, size and position. */
@Composable
private fun WatermarkPage(open: (SettingsPage) -> Unit) {
    val model = Watermark.modelName(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
    var editing by remember { mutableStateOf(false) }
    Group(footer = "A line of text in the corner of your photos and videos. It is part of the picture. In slow motion (120 and 240 fps) videos it is left out.") {
        row { SwitchRow("Watermark", AppSettings.wmOn, { AppSettings.wmOn = it }) }
    }
    Group {
        row { NavRow("Add watermark to", Watermark.TARGETS[AppSettings.wmTarget.coerceIn(0, 2)]) { open(SettingsPage.WM_TARGET) } }
    }
    Group {
        row { SwitchRow("Shot on $model", AppSettings.wmShotOn, { AppSettings.wmShotOn = it }) }
        row { SwitchRow("Location", AppSettings.wmLocation, { AppSettings.wmLocation = it }, "The last known place, as coordinates. Needs location tags to be on.") }
        row { SwitchRow("Date and time", AppSettings.wmDate, { AppSettings.wmDate = it }) }
        row { NavRow("Customised text", AppSettings.wmText.ifBlank { "None" }) { editing = true } }
    }
    Group {
        row { NavRow("Font size", Watermark.SIZES[AppSettings.wmSize.coerceIn(0, 2)]) { open(SettingsPage.WM_SIZE) } }
        row { NavRow("Position", Watermark.POSITIONS[AppSettings.wmPosition.coerceIn(0, 3)]) { open(SettingsPage.WM_POSITION) } }
    }
    if (editing) TextDialog("CUSTOMISED TEXT", AppSettings.wmText, { AppSettings.wmText = it.take(60); editing = false }) { editing = false }
}

/** A one-line text box in a brand dialog. */
@Composable
private fun TextDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    CbDialog(title, onDismiss, subtitle = "Up to 60 characters") {
        androidx.compose.foundation.text.BasicTextField(
            text, { text = it.take(60) }, Modifier.fillMaxWidth().background(DarkCb.surface2).padding(12.dp),
            singleLine = true, textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = DarkCb.ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(DarkCb.mint),
        )
        BrandButton("SAVE", Modifier.fillMaxWidth(), primary = true, cb = DarkCb) { onSave(text) }
        BrandButton("CANCEL", Modifier.fillMaxWidth(), cb = DarkCb, onClick = onDismiss)
    }
}
