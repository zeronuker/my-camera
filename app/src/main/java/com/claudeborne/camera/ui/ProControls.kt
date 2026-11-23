package com.claudeborne.camera.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.camera.camera.LevelMath
import kotlin.math.roundToInt

private val Glass = Color.Black.copy(alpha = 0.42f)

/** The Pro controls that open a dial. EV keeps its own pill at the top left. */
internal enum class ProParam { ISO, SHUTTER, WB, FOCUS }

/** One control of the Pro bar: its name and its value ("ISO" "400"), mint when set by hand. */
internal class ProChip(val param: ProParam, val name: String, val value: String, val manual: Boolean)

/** The stock "Auto | Pro" switch left of the lens pills: a pill that glides between the two names. */
@Composable
internal fun ProSwitch(pro: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val slide by animateFloatAsState(if (pro) 1f else 0f, tween(MOTION_FAST_MS), label = "pro switch")
    Box(modifier.height(44.dp).clip(CircleShape).background(Glass)) {
        Box(Modifier.offset(x = (slide * 46f).dp).padding(3.dp).size(width = 46.dp, height = 38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)))
        Row {
            Box(Modifier.size(width = 52.dp, height = 44.dp).clickable { onChange(false) }, Alignment.Center) {
                Text("Auto", style = monoStyle(glide(!pro, DarkCb.mint, DarkCb.ink2), 0.3.sp).copy(fontSize = 13.sp))
            }
            Box(Modifier.size(width = 46.dp, height = 44.dp).clickable { onChange(true) }, Alignment.Center) {
                Text("Pro", style = monoStyle(glide(pro, DarkCb.mint, DarkCb.ink2), 0.3.sp).copy(fontSize = 13.sp))
            }
        }
    }
}

/** The row of manual controls above the lens pills: tap one to open its dial. */
@Composable
internal fun ProBar(chips: List<ProChip>, open: ProParam?, onTap: (ProParam) -> Unit) {
    Row(Modifier.clip(CircleShape).background(Glass).padding(4.dp), Arrangement.spacedBy(2.dp), Alignment.CenterVertically) {
        chips.forEach { c ->
            val selected = open == c.param
            val fill by animateColorAsState(if (selected) Color(0x33FFFFFF) else Color.Transparent, tween(MOTION_FAST_MS), label = "pro chip")
            Column(Modifier.height(40.dp).clip(CircleShape).background(fill).clickable { onTap(c.param) }.padding(horizontal = 12.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(c.name, style = monoStyle(DarkCb.inkDim, 1.sp).copy(fontSize = 9.sp))
                Text(c.value, style = monoStyle(glide(c.manual || selected, DarkCb.mint, Color.White), 0.3.sp).copy(fontSize = 13.sp))
            }
        }
    }
}

/**
 * A ruler of choices for one Pro control (ISO, shutter, white balance, focus): drag it sideways, the middle mark is the value. AUTO at
 * the left gives the control back to the camera. [index] is the chosen entry of [items], null when the camera decides.
 */
@Composable
internal fun StepDial(title: String, items: List<String>, index: Int?, onIndex: (Int) -> Unit, onAuto: () -> Unit, autoNote: String? = null) {
    val density = LocalDensity.current
    val stepPx = with(density) { 26.dp.toPx() }
    val last = (items.size - 1).coerceAtLeast(0)
    var pos by remember { mutableFloatStateOf((index ?: 0).toFloat()) }
    val current by rememberUpdatedState(index)
    val onIndexNow by rememberUpdatedState(onIndex)
    LaunchedEffect(index) { if (index != null && kotlin.math.abs(pos - index) > 0.5f) pos = index.toFloat() }
    val auto = index == null
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(64.dp).clip(CircleShape).background(Glass), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.padding(start = 10.dp).height(40.dp).clip(CircleShape).background(if (auto) DarkCb.mint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.14f)).clickable(enabled = !auto, onClick = onAuto).padding(horizontal = 12.dp),
            Alignment.Center,
        ) { Text("AUTO", style = monoStyle(glide(auto, DarkCb.mint, Color.White), 1.sp).copy(fontSize = 12.sp)) }
        Column(
            Modifier.weight(1f).pointerInput(items.size) {
                detectHorizontalDragGestures { _, dx ->
                    if (items.isEmpty()) return@detectHorizontalDragGestures
                    pos = (pos - dx / stepPx).coerceIn(0f, last.toFloat())
                    val i = pos.roundToInt()
                    if (i != current) onIndexNow(i)
                }
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val text = if (auto) (autoNote ?: "AUTO") else items.getOrElse(index ?: 0) { "" }
            Text("$title  $text", Modifier.padding(top = 6.dp), style = monoStyle(glide(!auto, DarkCb.mint, Color.White), 1.sp).copy(fontSize = 14.sp))
            Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                val centre = size.width / 2
                for (i in items.indices) {
                    val x = centre + (i - pos) * stepPx
                    if (x in 0f..size.width) {
                        val big = i % 3 == 0
                        drawLine(Color.White.copy(alpha = if (auto) 0.25f else if (big) 0.9f else 0.4f), Offset(x, if (big) size.height * 0.3f else size.height * 0.5f), Offset(x, size.height), 1.5.dp.toPx())
                    }
                }
                drawLine(if (auto) Color.White.copy(alpha = 0.4f) else DarkCb.mint, Offset(centre, 0f), Offset(centre, size.height), 2.dp.toPx())
            }
        }
        Box(Modifier.padding(end = 10.dp).size(40.dp)) // keeps the ruler centred
    }
}

/** A small switch of a row of toggles (peaking, zebra, level, and in Pro video log, LUT and angle). */
internal class Toggle(val name: String, val on: Boolean, val click: () -> Unit)

/** Switches for the assistants, small pills that turn mint when on. */
@Composable
internal fun AssistRow(items: List<Toggle>) {
    Row(Modifier.clip(CircleShape).background(Glass).padding(horizontal = 4.dp), Arrangement.spacedBy(0.dp), Alignment.CenterVertically) {
        for ((name, on, click) in items.map { Triple(it.name, it.on, it.click) }) {
            Box(Modifier.height(32.dp).clip(CircleShape).clickable(onClick = click).padding(horizontal = 12.dp), Alignment.Center) {
                Text(name, style = monoStyle(glide(on, DarkCb.mint, DarkCb.ink2), 1.sp).copy(fontSize = 11.sp))
            }
        }
    }
}

/** The brightness histogram in a small glass panel (the Master 6-dot menu turns it on). */
@Composable
internal fun HistogramView(bins: IntArray?, modifier: Modifier = Modifier) {
    Box(modifier.width(168.dp).height(64.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.5f)).border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp)).padding(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val data = bins ?: return@Canvas
            // the end bins hold every clipped pixel, so the scale ignores them or one spike would flatten the rest
            val top = (data.drop(1).dropLast(1).maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
            val w = size.width / data.size
            data.forEachIndexed { i, v ->
                val h = (v / top).coerceIn(0f, 1f) * size.height
                val clipped = i == 0 || i == data.size - 1
                drawRect(if (clipped && v > top) Warn else DarkCb.mint.copy(alpha = 0.85f), Offset(i * w, size.height - h), androidx.compose.ui.geometry.Size(w * 0.8f, h))
            }
        }
    }
}

/** A line across the middle of the picture that tilts against the phone; it turns mint when the phone is level. */
@Composable
internal fun LevelLine(roll: Float, modifier: Modifier = Modifier) {
    val level = LevelMath.isLevel(roll)
    val tint by animateColorAsState(if (level) DarkCb.mint else Color.White.copy(alpha = 0.85f), tween(MOTION_FAST_MS), label = "level")
    Row(modifier.rotate(-roll), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(54.dp).height(2.dp).background(tint))
        Box(Modifier.width(54.dp).height(2.dp).background(tint))
    }
}

/** Peak level of the microphone as a thin bar (0..1); the red end warns about clipping. */
@Composable
internal fun AudioMeter(level: Float, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(level.coerceIn(0f, 1f), tween(90), label = "audio level")
    Row(modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("MIC", style = monoStyle(DarkCb.inkDim, 1.sp).copy(fontSize = 10.sp))
        Canvas(Modifier.width(110.dp).height(6.dp)) {
            drawRoundRect(Color.White.copy(alpha = 0.16f), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            drawRoundRect(if (shown > 0.9f) Warn else DarkCb.mint, size = androidx.compose.ui.geometry.Size(size.width * shown, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
    }
}
