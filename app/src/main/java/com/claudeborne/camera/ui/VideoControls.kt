package com.claudeborne.camera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.camera.video.VideoRules
import com.claudeborne.camera.video.VideoStyle
import kotlin.math.roundToInt

/** The shooting modes that exist so far, in the order of the mode strip (the stock order keeps VIDEO next to PHOTO). */
internal enum class Mode(val label: String, val style: VideoStyle?) {
    MASTER("MASTER", null),
    VIDEO("VIDEO", VideoStyle.NORMAL),
    PHOTO("PHOTO", null),
    PORTRAIT("PORTRAIT", null),
    TEXT("TEXT", null),
    NIGHT("NIGHT", null),
    PANO("PANO", null),
    LONGEXP("LONG EXP", null),
    SCAN("SCAN DOCS", null),
    SLOMO("SLO-MO", VideoStyle.SLOMO),
    TIMELAPSE("TIME-LAPSE", VideoStyle.TIMELAPSE),
    PROVIDEO("PRO VIDEO", VideoStyle.NORMAL),
    TILTSHIFT("TILT-SHIFT", null),
    UNDERWATER("UNDERWATER", null),
    DUALPHOTO("DUAL PHOTO", null),
    DUAL("DUAL VIDEO", null),
    QUAD("TRIPLE", null), // 2 rear lenses + the front (the phone refuses 3 lenses + front)
    ;

    /** Dual and Quad view: several cameras at once, drawn as one picture. */
    val multi get() = this == DUAL || this == DUALPHOTO || this == QUAD

    /** The two-camera modes (rear + front), with the layout bar. */
    val dual get() = this == DUAL || this == DUALPHOTO

    val isVideo get() = style != null
    val isMaster get() = this == MASTER
    /** Text and Scan Docs read what is in front of the camera instead of saving a photo of it. */
    val isScanner get() = this == TEXT || this == SCAN
    /** Modes whose shutter runs its own sequence (reading, stitching, stacking): no timer, interval, burst or filters. */
    val oneShot get() = isScanner || this == PANO || this == LONGEXP

}

private val RedRecord = Color(0xFFD33A33)

private const val MORE_SLOT = -1

/** The mode names under the shutter: tap one, or swipe along the strip. A pill glides under the chosen name. */
@Composable
internal fun ModeStrip(selected: Group, shown: List<Group>, enabled: Boolean, onSelect: (Group) -> Unit, onReselect: (Group) -> Unit = {}, onMore: () -> Unit = {}) {
    val density = LocalDensity.current
    val spots = remember { mutableStateMapOf<Int, Pair<Float, Float>>() } // left edge and width of each name, in pixels
    // the first five modes have their own names; the rest live in the More grid, whose slot shows the chosen one's name
    val more = ModeOrder.more(shown)
    val inMore = selected in more
    val slots = ModeOrder.strip(shown).map { it.ordinal to it.label } + if (more.isNotEmpty()) listOf(MORE_SLOT to if (inMore) selected.label else "MORE") else emptyList()
    val selectedSlot = if (inMore) MORE_SLOT else selected.ordinal
    val spot = spots[selectedSlot]
    val x by animateFloatAsState(spot?.first ?: 0f, tween(MOTION_MS), label = "mode pill x")
    val masterEdge by animateFloatAsState(if (selected == Group.MASTER) 1f else 0f, tween(MOTION_MS), label = "master edge") // the MASTER tab is framed in the brand gradient
    val w by animateFloatAsState(spot?.second ?: 0f, tween(MOTION_MS), label = "mode pill width")
    val current by rememberUpdatedState(selected)
    val on by rememberUpdatedState(enabled)
    val list by rememberUpdatedState(shown)
    // more names than fit: the strip scrolls by itself so the chosen name sits in the middle
    val scroll = rememberScrollState()
    var viewWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(selected, spot, viewWidth) {
        if (spot != null && viewWidth > 0) scroll.animateScrollTo((spot.first + spot.second / 2 - viewWidth / 2).roundToInt().coerceAtLeast(0), tween(MOTION_MS))
    }
    Box(Modifier.fillMaxWidth().chrome().padding(4.dp)) { Box(
        Modifier.fillMaxWidth().onSizeChanged { viewWidth = it.width }.fadeEdges().pointerInput(Unit) {
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = { if (on) { if (total < -50f) current.step(1, list)?.let(onSelect) else if (total > 50f) current.step(-1, list)?.let(onSelect) } },
                onHorizontalDrag = { _, dx -> total += dx },
            )
        },
        Alignment.Center,
    ) {
        Box(Modifier.horizontalScroll(scroll, enabled = false)) {
            if (spot != null) {
                Box(
                    Modifier.offset { IntOffset(x.roundToInt(), 0) }.width(with(density) { w.toDp() }).height(36.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.24f)).drawBehind { drawRoundRect(DarkCb.grad, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()), alpha = masterEdge) },
                )
            }
            Row(Modifier.height(36.dp)) {
                slots.forEach { (slot, label) ->
                    Box(
                        Modifier.height(36.dp).padding(horizontal = 8.dp).clickable(enabled = enabled) {
                            if (slot == MORE_SLOT) onMore()
                            else Group.entries[slot].let { g -> if (g == selected) onReselect(g) else onSelect(g) }
                        }.onGloballyPositioned { spots[slot] = it.positionInParent().x - with(density) { 8.dp.toPx() } to it.size.width + with(density) { 16.dp.toPx() } },
                        Alignment.Center,
                    ) {
                        // the name swaps with a short fade when the More slot takes another mode's name
                        AnimatedContent(label, transitionSpec = { fadeIn(tween(MOTION_FAST_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS)) }, label = "mode name") { t ->
                            Text(t, maxLines = 1, softWrap = false, style = monoStyle(glide(slot == selectedSlot, DarkCb.mint, Color.White.copy(alpha = 0.78f)), 1.4.sp).copy(fontSize = 13.sp))
                        }
                    }
                }
            }
        }
    } }
}

/** The pill at the top right that shows the quality ("4K · 30") and opens the picker. */
@Composable
internal fun QualityPill(label: String, open: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(44.dp).chrome().clickable(onClick = onClick).padding(horizontal = 18.dp), Alignment.Center) {
        AnimatedContent(label, transitionSpec = { fadeIn(tween(MOTION_FAST_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS)) }, label = "quality text") { t ->
            Text(t, style = monoStyle(glide(open, DarkCb.mint, Color.White), 0.5.sp).copy(fontSize = 15.sp))
        }
    }
}

/** One row of choices in the quality panel. */
internal class ChipRow(val title: String, val chips: List<Chip>)

internal class Chip(val text: String, val selected: Boolean, val onClick: () -> Unit)

/** The picker opened by the quality pill: rows of chips (quality; speed for time-lapse). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QualityPanel(rows: List<ChipRow>, modifier: Modifier = Modifier) {
    Column(
        modifier.chrome(RoundedCornerShape(24.dp), ChromePanelFill).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { row ->
            Text(row.title, style = monoStyle(DarkCb.inkDim, 2.sp).copy(fontSize = 11.sp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                row.chips.forEach { c ->
                    val fill by animateColorAsState(if (c.selected) DarkCb.mint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.1f), tween(MOTION_FAST_MS), label = "chip")
                    Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(fill).clickable(onClick = c.onClick).padding(horizontal = 14.dp), Alignment.Center) {
                        Text(c.text, style = monoStyle(glide(c.selected, DarkCb.mint, Color.White), 0.5.sp).copy(fontSize = 13.sp))
                    }
                }
            }
        }
    }
}

/** The red record button: a ring with a red disc that shrinks into a rounded "stop" square while recording. */
@Composable
internal fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val size by animateDpAsState(if (recording) 30.dp else 62.dp, tween(MOTION_FAST_MS), label = "record size")
    val corner by animateDpAsState(if (recording) 8.dp else 31.dp, tween(MOTION_FAST_MS), label = "record corner")
    val fill by animateColorAsState(if (enabled) RedRecord else RedRecord.copy(alpha = 0.45f), tween(MOTION_FAST_MS), label = "record colour")
    Box(
        Modifier.size(78.dp).pressScale(press, 0.92f).border(4.dp, Color.White, CircleShape).clickable(press, null, enabled = enabled, onClick = onClick),
        Alignment.Center,
    ) { Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(fill)) }
}

/** Time recorded so far, with a red dot that breathes (and stays still while paused). */
@Composable
internal fun RecordTimer(ms: Long, paused: Boolean, extra: String? = null) {
    val pulse by rememberInfiniteTransition(label = "rec dot").animateFloat(1f, 0.25f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "rec dot")
    Row(
        Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).alpha(if (paused) 1f else pulse).clip(CircleShape).background(if (paused) DarkCb.inkDim else RedRecord))
        Text(VideoRules.timer(ms), style = monoStyle(Color.White, 1.sp).copy(fontSize = 15.sp))
        Visible(paused, enter = popEnter, exit = popExit) { Text("PAUSED", style = monoStyle(Warn, 1.5.sp).copy(fontSize = 11.sp)) }
        if (extra != null) Text(extra, style = monoStyle(DarkCb.mint, 1.sp).copy(fontSize = 12.sp))
    }
}

/** The 6-dot menu of the video modes: 10-bit HDR, Ultra Steady, Focus lock and Settings; hidden items are the ones the camera cannot do. */
@Composable
internal fun VideoMenuPanel(
    page: Menu, showHdr: Boolean, hdr: Int, showSteady: Boolean, steady: Boolean, focusHeld: Boolean,
    onPage: (Menu) -> Unit, onHdr: (Int) -> Unit, onSteady: () -> Unit, onFocus: () -> Unit, onSettings: () -> Unit,
    hdrChoices: List<Pair<String, Int>>, modifier: Modifier = Modifier, showSettings: Boolean = true,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).chrome(RoundedCornerShape(28.dp), ChromePanelFill).padding(18.dp)) {
        AnimatedContent(page, transitionSpec = { fadeIn(tween(MOTION_FAST_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS)) }, label = "video menu") { p ->
            if (p == Menu.HDR) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("‹  10-BIT HDR", Modifier.clickable { onPage(Menu.MAIN) }, style = monoStyle(DarkCb.mint, 2.2.sp))
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                        hdrChoices.forEach { (name, value) ->
                            val selected = value == hdr
                            val fill by animateColorAsState(if (selected) DarkCb.mint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.1f), tween(MOTION_FAST_MS), label = "hdr choice")
                            Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp)).background(fill).clickable { onHdr(value) }, Alignment.Center) {
                                Text(name, style = monoStyle(glide(selected, DarkCb.mint, Color.White), 0.5.sp).copy(fontSize = 13.sp))
                            }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                    if (showHdr) VideoMenuItem("10-bit HDR", hdr != 0, { onPage(Menu.HDR) }) {
                        Text("HDR", style = monoStyle(glide(hdr != 0, DarkCb.mint, Color.White), 0.5.sp).copy(fontSize = 16.sp))
                    }
                    if (showSteady) VideoMenuItem("Ultra Steady", steady, onSteady) { CbIcon(Icon.STEADY, glide(steady, DarkCb.mint, Color.White), size = 26.dp) }
                    VideoMenuItem("Focus lock", focusHeld, onFocus) { CbIcon(Icon.FOCUS_LOCK, glide(focusHeld, DarkCb.mint, Color.White), size = 26.dp) }
                    if (showSettings) VideoMenuItem("Settings", false, onSettings) { CbIcon(Icon.GEAR, Color.White, size = 26.dp) }
                }
            }
        }
    }
}

@Composable
private fun VideoMenuItem(label: String, active: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val fill by animateColorAsState(if (active) DarkCb.mint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.1f), tween(MOTION_FAST_MS), label = "video menu fill")
    Column(Modifier.width(76.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(62.dp).clip(CircleShape).background(fill), Alignment.Center) { icon() }
        Text(label, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = glide(active, DarkCb.mint, DarkCb.ink2)), maxLines = 2)
    }
}

/** Asks for the microphone (video with sound needs it), or, once Android no longer asks, points to the phone's settings. */
@Composable
internal fun MicDialog(show: Boolean, blocked: Boolean, onAllow: () -> Unit, onSettings: () -> Unit, onCancel: () -> Unit) {
    CbDialog("MICROPHONE", onCancel, show = show) {
        Text("Video needs the microphone to record sound. Nothing leaves your phone.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = DarkCb.ink2))
        if (blocked) {
            Text("Microphone access is blocked. Open the app's settings, choose Permissions, then Microphone, and allow it.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Warn))
            BrandButton("OPEN SETTINGS", Modifier.fillMaxWidth(), primary = true, cb = DarkCb, onClick = onSettings)
        } else {
            BrandButton("ALLOW MICROPHONE", Modifier.fillMaxWidth(), primary = true, cb = DarkCb, onClick = onAllow)
        }
        BrandButton("NOT NOW", Modifier.fillMaxWidth(), cb = DarkCb, onClick = onCancel)
    }
}

/** Names that run off the strip fade out at both ends instead of being cut, as in the stock camera. */
private fun Modifier.fadeEdges(): Modifier = graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen).drawWithContent {
    drawContent()
    drawRect(
        androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Transparent, 0.04f to Color.Black, 0.96f to Color.Black, 1f to Color.Transparent),
        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
    )
}
