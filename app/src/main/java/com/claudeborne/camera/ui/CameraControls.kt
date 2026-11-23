package com.claudeborne.camera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.camera.camera.CameraEngine
import com.claudeborne.camera.camera.ZoomStops
import com.claudeborne.camera.capture.Aspect
import com.claudeborne.camera.capture.Ev
import com.claudeborne.camera.capture.Flash
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ln
import kotlin.math.roundToInt

/** Which page of the 6-dot menu is open. */
internal enum class Menu { NONE, MAIN, ASPECT, TIMER, INTERVAL, HDR, SOFT, FILTERS, STYLES, SCENES }

private val Glass = Color.Black.copy(alpha = 0.42f)

/**
 * One finger: tap, long press, or (while the focus box is up) drag up and down for exposure. Two fingers: pinch zoom.
 * Written as one detector because separate tap / drag / pinch detectors on the same view fight over the same touches.
 */
internal suspend fun PointerInputScope.cameraGestures(
    onTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
    onZoom: (Float) -> Unit,
    onExposureDrag: (Float) -> Unit, // pixels, positive = up
    dragEnabled: () -> Boolean,
    onSwipe: (Int) -> Unit = {}, // -1 left, 1 right: a sideways swipe of a finger that is not setting the exposure
    onTwist: (Float) -> Unit = {}, // degrees, two fingers turning (Tilt-shift)
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val slop = viewConfiguration.touchSlop
    var outcome = 0 // 1 tap, 2 moved, 3 second finger
    val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (outcome == 0) {
            val event = awaitPointerEvent()
            when {
                event.changes.size > 1 -> outcome = 3
                event.changes.all { it.changedToUp() } -> outcome = 1
                (event.changes[0].position - down.position).getDistance() > slop -> outcome = 2
            }
        }
    } == null
    if (timedOut) {
        onLongPress(down.position)
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        return@awaitEachGesture
    }
    if (outcome == 1) return@awaitEachGesture onTap(down.position)
    var twoFingers = false
    var last = down.position
    do {
        val event = awaitPointerEvent()
        if (event.changes.size > 1) {
            twoFingers = true
            val zoom = event.calculateZoom()
            val turn = event.calculateRotation()
            if (turn != 0f) onTwist(turn)
            if (zoom != 1f) { onZoom(zoom); event.changes.forEach { it.consume() } }
        } else if (dragEnabled()) {
            val dy = event.changes[0].positionChange().y
            if (dy != 0f) { onExposureDrag(-dy); event.changes[0].consume() }
        }
        last = event.changes[0].position
    } while (event.changes.any { it.pressed })
    val dx = last.x - down.position.x
    val dy = last.y - down.position.y
    if (!twoFingers && !dragEnabled() && kotlin.math.abs(dx) > 140f && kotlin.math.abs(dx) > 2.5f * kotlin.math.abs(dy)) onSwipe(if (dx < 0) 1 else -1)
}

/** Top left: flash and the exposure reading (tap it for the dial). */
@Composable
internal fun TopPill(flash: Flash, ev: Float, evOpen: Boolean, onFlash: () -> Unit, onEv: () -> Unit, modifier: Modifier = Modifier, showFlash: Boolean = true) {
    Row(modifier.height(44.dp).clip(CircleShape).background(Glass), verticalAlignment = Alignment.CenterVertically) {
        if (showFlash) Box(Modifier.size(44.dp).clickable(onClick = onFlash), Alignment.Center) {
            CbIcon(if (flash == Flash.OFF) Icon.FLASH_OFF else Icon.FLASH, glide(flash == Flash.TORCH, DarkCb.mint, Color.White), filled = flash == Flash.ON || flash == Flash.TORCH, size = 22.dp)
            if (flash == Flash.AUTO) Text("A", Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 5.dp), style = monoStyle(Color.White, 0.sp).copy(fontSize = 10.sp))
        }
        Row(Modifier.clickable(onClick = onEv).padding(start = if (showFlash) 6.dp else 16.dp, end = 16.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("EV", style = monoStyle(DarkCb.inkDim, 1.sp).copy(fontSize = 12.sp))
            Text(Ev.label(ev), style = monoStyle(glide(evOpen || ev != 0f, DarkCb.mint, Color.White), 0.5.sp).copy(fontSize = 15.sp))
        }
    }
}

@Composable
internal fun RoundButton(modifier: Modifier = Modifier, size: Int = 44, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier.size(size.dp).clip(CircleShape).background(Glass).clickable(onClick = onClick), Alignment.Center) { content() }
}

/** The focus square with a sun slider beside it: drag up or down anywhere to brighten or darken. */
@Composable
internal fun FocusBox(center: Offset, tick: Int, focus: CameraEngine.Focus, locked: Boolean, ev: Float, screenWidth: Int) {
    val density = LocalDensity.current
    val half = with(density) { 38.dp.toPx() }
    val colour = when {
        locked || focus == CameraEngine.Focus.FOCUSED -> DarkCb.mint
        focus == CameraEngine.Focus.FAILED -> Warn
        else -> Color.White
    }
    val tint by animateColorAsState(colour, tween(MOTION_FAST_MS), label = "focus")
    val scale = remember(tick) { Animatable(1.35f) }
    LaunchedEffect(tick) { scale.animateTo(1f, tween(MOTION_FAST_MS)) }
    val sunOnRight = center.x < screenWidth - with(density) { 120.dp.toPx() }
    Box(Modifier.offset { IntOffset((center.x - half).roundToInt(), (center.y - half).roundToInt()) }.size(76.dp)) {
        Canvas(Modifier.size(76.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
            val len = 14.dp.toPx()
            val w = 2.dp.toPx()
            for ((x, y, dx, dy) in listOf(listOf(0f, 0f, 1f, 1f), listOf(size.width, 0f, -1f, 1f), listOf(0f, size.height, 1f, -1f), listOf(size.width, size.height, -1f, -1f))) {
                drawLine(tint, Offset(x, y), Offset(x + dx * len, y), w)
                drawLine(tint, Offset(x, y), Offset(x, y + dy * len), w)
            }
        }
        // the sun rides up and down a short track: its height is the exposure
        Box(Modifier.align(if (sunOnRight) Alignment.CenterEnd else Alignment.CenterStart).offset(x = if (sunOnRight) 34.dp else (-34).dp).width(24.dp).height(96.dp), Alignment.Center) {
            Box(Modifier.width(1.dp).height(96.dp).background(Color.White.copy(alpha = 0.35f)))
            Box(Modifier.offset(y = (-(ev / Ev.LIMIT) * 42f).dp).size(24.dp).clip(CircleShape).background(Glass), Alignment.Center) {
                CbIcon(Icon.SUN, if (ev != 0f) DarkCb.mint else Color.White, size = 18.dp)
            }
        }
        if (locked) Text("AE/AF LOCK", Modifier.align(Alignment.BottomCenter).offset(y = 22.dp), style = monoStyle(DarkCb.mint, 1.sp).copy(fontSize = 10.sp))
    }
}

/** A ruler of exposure steps: drag it sideways like a dial, or press the circle to go back to zero. */
@Composable
internal fun EvDial(ev: Float, onChange: (Float) -> Unit, onReset: () -> Unit) {
    val current by rememberUpdatedState(ev)
    val density = LocalDensity.current
    val tickPx = with(density) { 22.dp.toPx() } // one third of a stop
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(64.dp).clip(CircleShape).background(Glass), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(start = 10.dp).size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = if (ev != 0f) 0.14f else 0.05f)).clickable(enabled = ev != 0f, onClick = onReset), Alignment.Center) {
            CbIcon(Icon.REFRESH, Color.White.copy(alpha = if (ev != 0f) 1f else 0.35f), size = 20.dp)
        }
        Column(Modifier.weight(1f).pointerInput(Unit) {
            detectHorizontalDragGestures { _, dx -> onChange((current - dx / tickPx / 3f).coerceIn(-Ev.LIMIT, Ev.LIMIT)) }
        }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Ev.label(ev), Modifier.padding(top = 6.dp), style = monoStyle(glide(ev != 0f, DarkCb.mint, Color.White), 1.sp).copy(fontSize = 15.sp))
            Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                val centre = size.width / 2
                var i = (-Ev.LIMIT * 3).roundToInt()
                while (i <= (Ev.LIMIT * 3).roundToInt()) {
                    val x = centre + (i / 3f - ev) * 3f * tickPx
                    if (x in 0f..size.width) {
                        val whole = i % 3 == 0
                        drawLine(Color.White.copy(alpha = if (whole) 0.9f else 0.4f), Offset(x, if (whole) size.height * 0.3f else size.height * 0.5f), Offset(x, size.height), 1.5.dp.toPx())
                    }
                    i++
                }
                drawLine(DarkCb.mint, Offset(centre, 0f), Offset(centre, size.height), 2.dp.toPx())
            }
        }
        Box(Modifier.padding(end = 10.dp).size(40.dp)) // keeps the ruler centred
    }
}

/** The 6-dot menu: a grid of round buttons, with one page per choice. Items for later phases are not shown. */
@Composable
internal fun MenuPanel(
    menu: Menu, aspect: Aspect, timerSec: Int, intervalSec: Int,
    onPage: (Menu) -> Unit, onAspect: (Aspect) -> Unit, onTimer: (Int) -> Unit, onInterval: (Int) -> Unit, onSettings: () -> Unit,
    modifier: Modifier = Modifier, showSettings: Boolean = true,
    /** Master mode adds Histogram and Face metering (the latter only where the camera can find faces). */
    master: Boolean = false, histogram: Boolean = false, faceOk: Boolean = false, face: Boolean = false, onHistogram: () -> Unit = {}, onFace: () -> Unit = {},
    /** Photo's menu has Soft light (Master has it among the styles). */
    showSoft: Boolean = false, soft: Int = 0, onSoft: (Int) -> Unit = {},
    /** Text mode (and Scan Docs) keep only Aspect ratio and Settings. */
    short: Boolean = false,
    /** Photo: Action mode (where the camera has it) and Macro shot (where there is an ultra wide lens). */
    showAction: Boolean = false, action: Boolean = false, onAction: () -> Unit = {}, showMacro: Boolean = false, macro: Boolean = false, onMacro: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = 0.66f)).border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(28.dp)).padding(18.dp)) {
        AnimatedContent(menu, transitionSpec = { fadeIn(tween(MOTION_FAST_MS)) togetherWith fadeOut(tween(MOTION_FAST_MS)) }, label = "menu") { page ->
            when (page) {
                Menu.MAIN, Menu.NONE -> {
                    // a grid of four columns, as the stock menus
                    val items = buildList<@Composable () -> Unit> {
                        add { MenuItem(Icon.ASPECT, "Aspect ratio", aspect != Aspect.FULL) { onPage(Menu.ASPECT) } }
                        if (showSoft) add { MenuItem(Icon.SOFT_LIGHT, "Soft light", soft > 0) { onPage(Menu.SOFT) } }
                        if (showAction) add { MenuItem(Icon.STEADY, "Action mode", action, onAction) }
                        if (showMacro) add { MenuItem(Icon.FOCUS_LOCK, "Macro shot", macro, onMacro) }
                        if (!short) add { MenuItem(Icon.TIMER, "Timer", timerSec > 0) { onPage(Menu.TIMER) } }
                        if (master) add { MenuItem(Icon.HISTOGRAM, "Histogram", histogram, onHistogram) }
                        if (master && faceOk) add { MenuItem(Icon.FACE_METERING, "Face metering", face, onFace) }
                        if (!short) add { MenuItem(Icon.INTERVAL, "Interval shooting", intervalSec > 0) { onPage(Menu.INTERVAL) } }
                        if (showSettings) add { MenuItem(Icon.GEAR, "Settings", false, onSettings) }
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { item -> Box(Modifier.weight(1f), Alignment.Center) { item() } }
                                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                Menu.ASPECT -> MenuPage("ASPECT RATIO", { onPage(Menu.MAIN) }) {
                    Aspect.entries.forEach { Choice(it.label, it == aspect) { onAspect(it) } }
                }
                Menu.TIMER -> MenuPage("TIMER", { onPage(Menu.MAIN) }) {
                    listOf(0, 3, 5, 10).forEach { Choice(if (it == 0) "Off" else "$it s", it == timerSec) { onTimer(it) } }
                }
                Menu.INTERVAL -> MenuPage("INTERVAL SHOOTING", { onPage(Menu.MAIN) }) {
                    listOf(0, 3, 5, 10).forEach { Choice(if (it == 0) "Off" else "$it s", it == intervalSec) { onInterval(it) } }
                }
                Menu.SOFT -> MenuPage("SOFT LIGHT", { onPage(Menu.MAIN) }) {
                    listOf(0, 3, 6, 9).forEach { Choice(com.claudeborne.camera.look.SoftLight.label(it), it == soft) { onSoft(it) } }
                }
                Menu.HDR, Menu.FILTERS, Menu.STYLES, Menu.SCENES -> Unit // pages of other panels
            }
        }
    }
}

@Composable
private fun MenuItem(icon: Icon, label: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(76.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(62.dp).clip(CircleShape).background(if (active) DarkCb.mint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.1f)), Alignment.Center) {
            CbIcon(icon, if (active) DarkCb.mint else Color.White, size = 26.dp)
        }
        Text(label, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = if (active) DarkCb.mint else DarkCb.ink2), maxLines = 2)
    }
}

@Composable
private fun MenuPage(title: String, onBack: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("‹  $title", Modifier.clickable(onClick = onBack), style = monoStyle(DarkCb.mint, 2.2.sp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) DarkCb.mint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.1f), tween(MOTION_FAST_MS), label = "choice")
    Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp)).background(fill).clickable(onClick = onClick), Alignment.Center) {
        Text(text, style = monoStyle(glide(selected, DarkCb.mint, Color.White), 0.5.sp).copy(fontSize = 13.sp))
    }
}

/** The big number of the self-timer. */
@Composable
internal fun Countdown(seconds: Int?) {
    AnimatedContent(
        seconds,
        transitionSpec = { (fadeIn(tween(MOTION_FAST_MS)) + scaleIn(tween(MOTION_MS), initialScale = 1.5f)) togetherWith (fadeOut(tween(MOTION_FAST_MS)) + scaleOut(tween(MOTION_MS), targetScale = 0.7f)) },
        label = "countdown",
    ) { s ->
        if (s != null) Text("$s", style = TextStyle(fontFamily = Tourney, fontSize = 120.sp, color = Color.White))
    }
}

@Composable
internal fun LensPills(zoom: Float, stops: FloatArray, onPick: (com.claudeborne.camera.camera.LensPill) -> Unit) {
    Row(Modifier.clip(CircleShape).background(Glass).padding(4.dp), Arrangement.spacedBy(2.dp), Alignment.CenterVertically) {
        ZoomStops.pills(zoom, stops).forEach { p ->
            val fill by animateColorAsState(if (p.selected) Color(0x33FFFFFF) else Color.Transparent, tween(MOTION_FAST_MS), label = "pill")
            Box(
                Modifier.height(40.dp).widthIn(min = 40.dp).clip(CircleShape).background(fill).clickable { onPick(p) }.padding(horizontal = 10.dp),
                Alignment.Center,
            ) { Text(p.text, style = monoStyle(glide(p.selected, DarkCb.mint, DarkCb.ink), 0.5.sp).copy(fontSize = 14.sp)) }
        }
    }
}

/** Shown while pinching: the zoom number and a log-scale ruler with the lens stops marked. */
@Composable
internal fun ZoomRuler(zoom: Float, min: Float, max: Float, stops: FloatArray) {
    Column(Modifier.clip(CircleShape).background(Glass).padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(ZoomStops.format(zoom) + "x", style = monoStyle(DarkCb.mint, 1.sp).copy(fontSize = 15.sp))
        Canvas(Modifier.width(240.dp).height(22.dp)) {
            val span = ln(max / min).coerceAtLeast(0.01f)
            fun x(r: Float) = size.width * ln(r / min) / span
            for (i in 0..40) {
                val at = size.width * i / 40
                drawLine(Color.White.copy(alpha = 0.35f), Offset(at, size.height * 0.55f), Offset(at, size.height), 1.dp.toPx())
            }
            stops.filter { it in min..max }.forEach { drawLine(Color.White.copy(alpha = 0.8f), Offset(x(it), size.height * 0.25f), Offset(x(it), size.height), 1.5.dp.toPx()) }
            drawLine(DarkCb.mint, Offset(x(zoom.coerceIn(min, max)), 0f), Offset(x(zoom.coerceIn(min, max)), size.height), 2.dp.toPx())
        }
    }
}

/**
 * Round, as in the stock app: it squishes when pressed. A tap takes a photo (or stops a timer or interval run); touching and
 * holding takes a burst until the finger lifts. While something is running the inside turns into a mint "stop" square.
 */
@Composable
internal fun Shutter(enabled: Boolean, running: Boolean, onTap: () -> Unit, onHoldStart: () -> Unit, onHoldEnd: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val ink by animateColorAsState(if (running) DarkCb.mint else if (enabled) Color.White else Color.White.copy(alpha = 0.55f), tween(MOTION_FAST_MS), label = "shutter")
    val corner by animateIntAsState(if (running) 24 else 50, tween(MOTION_FAST_MS), label = "shutter shape")
    val tap by rememberUpdatedState(onTap)
    val holdStart by rememberUpdatedState(onHoldStart)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    Box(
        Modifier.size(78.dp).pressScale(press, 0.92f).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(RoundedCornerShape(corner)).background(ink)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val p = PressInteraction.Press(it)
                        press.emit(p)
                        val released = tryAwaitRelease()
                        press.emit(if (released) PressInteraction.Release(p) else PressInteraction.Cancel(p))
                        holdEnd()
                    },
                    onTap = { tap() },
                    onLongPress = { holdStart() },
                )
            },
    )
}

/** The last photo, with a moving bar above it while one is being written. */
@Composable
internal fun Thumbnail(thumb: ImageBitmap?, saving: Boolean, onClick: () -> Unit) {
    Column(Modifier.size(52.dp), Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
        Visible(saving, enter = popEnter, exit = popExit) { ProgressBar(null, Modifier.fillMaxWidth()) }
        if (thumb != null) {
            Image(thumb, "Last photo", Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(10.dp)).clickable(onClick = onClick), contentScale = ContentScale.Crop)
        }
    }
}

/** A short message near the top ("Flash: Off", "Storage is full") that fades in and out. */
@Composable
internal fun Toast(message: Pair<String, Int>?) {
    var last by remember { androidx.compose.runtime.mutableStateOf(message?.first.orEmpty()) }
    if (message != null) last = message.first
    Visible(message != null, enter = popEnter, exit = popExit) {
        Text(last.uppercase(), Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 18.dp, vertical = 9.dp), style = monoStyle(DarkCb.ink, 1.5.sp).copy(fontSize = 12.sp))
    }
}

/** An amber warning while the phone is hot; it stays until the phone has cooled down. */
@Composable
internal fun HeatChip(heat: com.claudeborne.camera.camera.Heat, video: Boolean = false) {
    var last by remember { mutableStateOf(heat) }
    if (heat != com.claudeborne.camera.camera.Heat.OK) last = heat
    Visible(heat != com.claudeborne.camera.camera.Heat.OK, enter = popEnter, exit = popExit) {
        val text = if (last == com.claudeborne.camera.camera.Heat.TOO_HOT) "TOO HOT · LET IT COOL DOWN" else if (video) "PHONE IS HOT" else "PHONE IS HOT · NO BURSTS"
        Text(text, Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).border(1.dp, Warn, CircleShape).padding(horizontal = 16.dp, vertical = 8.dp), style = monoStyle(Warn, 1.2.sp).copy(fontSize = 11.sp))
    }
}

/** A small number badge (photos in a burst or interval run). */
@Composable
internal fun CountBadge(count: Int) {
    Visible(count > 0, enter = popEnter, exit = popExit) {
        Text("$count", Modifier.clip(CircleShape).background(DarkCb.mint).padding(horizontal = 12.dp, vertical = 5.dp), style = monoStyle(DarkCb.surface0, 0.5.sp).copy(fontSize = 14.sp))
    }
}
