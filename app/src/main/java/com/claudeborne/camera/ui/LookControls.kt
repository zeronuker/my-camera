package com.claudeborne.camera.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.camera.data.Settings as AppSettings
import com.claudeborne.camera.look.Filters
import com.claudeborne.camera.look.GridKind
import com.claudeborne.camera.look.GridMath
import com.claudeborne.camera.look.Look
import com.claudeborne.camera.look.Scene
import com.claudeborne.camera.look.SoftLight
import com.claudeborne.camera.look.StylePreset

private val PanelShape = RoundedCornerShape(28.dp)
private val GlassFill = Color.Black.copy(alpha = 0.42f)

/** The glass panel the looks panels sit in (the same as the 6-dot menu). */
@Composable
private fun LookPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(PanelShape).background(Color.Black.copy(alpha = 0.66f)).border(1.dp, Color.White.copy(alpha = 0.14f), PanelShape).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/** The round button right of the lens pills: filters (Photo, Video) or styles (Master). */
@Composable
internal fun LookButton(icon: Icon, active: Boolean, onClick: () -> Unit) {
    RoundButton(onClick = onClick) { CbIcon(icon, glide(active, DarkCb.mint, Color.White), size = 24.dp) }
}

// ---- filters ----

/** A small swatch of what a filter does to four typical colours (sky, skin, leaf, sun), computed from the same grade the shader uses. */
@Composable
private fun FilterSwatch(index: Int, modifier: Modifier = Modifier) {
    val colours = remember(index) {
        val def = Filters.all[index]
        listOf(floatArrayOf(0.35f, 0.6f, 0.9f), floatArrayOf(0.9f, 0.72f, 0.6f), floatArrayOf(0.3f, 0.65f, 0.3f), floatArrayOf(0.95f, 0.82f, 0.25f))
            .map { c -> def.grade(c[0], c[1], c[2]).let { Color(it[0], it[1], it[2]) } }
    }
    Canvas(modifier) {
        val w = size.width / 2
        val h = size.height / 2
        colours.forEachIndexed { i, c -> drawRect(c, Offset((i % 2) * w, (i / 2) * h), Size(w, h)) }
    }
}

/** The filters panel: Original and our own looks, each with a swatch; the picture behind changes as you choose. */
@Composable
internal fun FilterPanel(selected: Int, onPick: (Int) -> Unit) {
    LookPanel {
        Text(
            Filters.all[selected].let { "${it.name.uppercase()}  ·  ${it.note}" }, Modifier.padding(horizontal = 18.dp),
            style = monoStyle(DarkCb.mint, 1.sp).copy(fontSize = 11.sp), maxLines = 2,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), Arrangement.spacedBy(10.dp)) {
            Filters.all.forEachIndexed { i, def ->
                val on = i == selected
                val border by animateColorAsState(if (on) DarkCb.mint else Color.Transparent, tween(MOTION_FAST_MS), label = "filter border")
                Column(Modifier.width(72.dp).clickable { onPick(i) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterSwatch(i, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).border(2.dp, border, RoundedCornerShape(14.dp)))
                    Text(def.name, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = glide(on, DarkCb.mint, DarkCb.ink2)), maxLines = 1)
                }
            }
        }
    }
}

// ---- scenes ----

/** The scenes panel (opened by touching the VIDEO label again): None, Stage, Silhouette, Fireworks. */
@Composable
internal fun ScenesPanel(selected: Int, onPick: (Int) -> Unit) {
    val tiles = listOf(
        listOf(Color(0xFF2A2F3A), Color(0xFF3A404E)),
        listOf(Color(0xFF241A55), Color(0xFFD14BD6)),
        listOf(Color(0xFFE0762C), Color(0xFF3A1F25)),
        listOf(Color(0xFF0B0F24), Color(0xFFE8A93A)),
    )
    LookPanel {
        Text("SCENES  ·  an exposure and tone preset for video", Modifier.padding(horizontal = 18.dp), style = monoStyle(DarkCb.inkDim, 1.sp).copy(fontSize = 11.sp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), Arrangement.SpaceEvenly) {
            Scene.all.forEachIndexed { i, scene ->
                val on = i == selected
                val border by animateColorAsState(if (on) DarkCb.mint else Color.Transparent, tween(MOTION_FAST_MS), label = "scene border")
                Column(Modifier.width(72.dp).clickable { onPick(i) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(tiles[i])).border(2.dp, border, RoundedCornerShape(14.dp)), Alignment.Center) {
                        if (i == 0) Canvas(Modifier.size(26.dp)) { // the "none" sign: a circle with a stroke through it
                            drawCircle(Color.White, size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(1.8.dp.toPx()))
                            drawLine(Color.White, Offset(size.width * 0.2f, size.height * 0.8f), Offset(size.width * 0.8f, size.height * 0.2f), 1.8.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    Text(scene.name, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = glide(on, DarkCb.mint, DarkCb.ink2)), maxLines = 1)
                }
            }
        }
    }
}

// ---- master styles ----

internal enum class StyleControl(val title: String) { SOFT("Soft light"), TONE("Tonality"), SAT("Saturation"), WARM("Cold / warm"), TINT("Cyan / magenta"), SHARP("Sharpness"), VIGNETTE("Vignette") }

private fun styleValue(c: StyleControl): Int = when (c) {
    StyleControl.SOFT -> AppSettings.softLight
    StyleControl.TONE -> AppSettings.styleTone
    StyleControl.SAT -> AppSettings.styleSat
    StyleControl.WARM -> AppSettings.styleWarm
    StyleControl.TINT -> AppSettings.styleTint
    StyleControl.SHARP -> AppSettings.styleSharp
    StyleControl.VIGNETTE -> if (AppSettings.styleVignette) 1 else 0
}

private fun setStyle(c: StyleControl, v: Int) {
    when (c) {
        StyleControl.SOFT -> AppSettings.softLight = v.coerceIn(0, Look.SOFT_MAX)
        StyleControl.TONE -> AppSettings.styleTone = v
        StyleControl.SAT -> AppSettings.styleSat = v
        StyleControl.WARM -> AppSettings.styleWarm = v
        StyleControl.TINT -> AppSettings.styleTint = v
        StyleControl.SHARP -> AppSettings.styleSharp = v.coerceAtLeast(0)
        StyleControl.VIGNETTE -> AppSettings.styleVignette = v != 0
    }
}

private fun styleText(c: StyleControl): String {
    val v = styleValue(c)
    return when (c) {
        StyleControl.SOFT -> SoftLight.label(v)
        StyleControl.VIGNETTE -> if (v != 0) "On" else "Off"
        else -> if (v > 0) "+$v" else "$v"
    }
}

/** Applies a preset page to the style controls (Soft light comes with it). */
internal fun applyPreset(index: Int) {
    val p = StylePreset.all[index.coerceIn(0, StylePreset.all.lastIndex)]
    AppSettings.stylePage = index
    AppSettings.styleTone = p.tone; AppSettings.styleSat = p.sat; AppSettings.styleWarm = p.warm; AppSettings.styleTint = p.tint; AppSettings.styleSharp = p.sharp
    AppSettings.softLight = p.soft
    AppSettings.styleVignette = false
}

/** Tiny drawings for the seven controls, as in the stock styles page: circle, half circle, bars, thermometer, ring of dots, triangle, square with circle. */
@Composable
private fun StyleGlyph(c: StyleControl, tint: Color) {
    Canvas(Modifier.size(26.dp)) {
        val k = size.width / 24f
        val line = Stroke(1.8f * k, cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        when (c) {
            StyleControl.SOFT -> drawCircle(tint, 8f * k, p(12f, 12f))
            StyleControl.TONE -> {
                drawCircle(tint, 8.5f * k, p(12f, 12f), style = line)
                drawArc(tint, 90f, 180f, true, p(3.5f, 3.5f), Size(17f * k, 17f * k))
            }
            StyleControl.SAT -> for (i in 0 until 5) drawRoundRect(tint, p(3f + i * 4f, 5f + (if (i % 2 == 0) 0f else 3f)), Size(2.6f * k, (14f - (if (i % 2 == 0) 0f else 6f)) * k), CornerRadius(1f * k), style = Fill)
            StyleControl.WARM -> {
                drawRoundRect(tint, p(9.5f, 3f), Size(5f * k, 12f * k), CornerRadius(2.5f * k), style = line)
                drawCircle(tint, 3.8f * k, p(12f, 17f))
            }
            StyleControl.TINT -> for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawCircle(tint, 1.7f * k, p(12f + 8f * Math.cos(a).toFloat(), 12f + 8f * Math.sin(a).toFloat()))
            }
            StyleControl.SHARP -> drawPath(Path().apply { moveTo(12f * k, 4f * k); lineTo(21f * k, 20f * k); lineTo(3f * k, 20f * k); close() }, tint, style = line)
            StyleControl.VIGNETTE -> {
                drawRoundRect(tint, p(3f, 3f), Size(18f * k, 18f * k), CornerRadius(4f * k), style = Fill)
                drawCircle(Color.Black.copy(alpha = 0.55f), 6f * k, p(12f, 12f))
            }
        }
    }
}

/**
 * The Master styles panel: swipe sideways to change the preset page, a reset button, and seven controls. Tapping a control opens
 * its dial above the row; the Vignette control switches on and off at once.
 */
@Composable
internal fun StylesPanel(onChange: () -> Unit) {
    var open by remember { mutableStateOf<StyleControl?>(null) }
    val page = AppSettings.stylePage.coerceIn(0, StylePreset.all.lastIndex)
    val pageNow by rememberUpdatedState(page)
    LookPanel {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        val next = if (total < -60f) pageNow + 1 else if (total > 60f) pageNow - 1 else pageNow
                        if (next != pageNow && next in StylePreset.all.indices) { applyPreset(next); onChange() }
                    },
                    onHorizontalDrag = { _, dx -> total += dx },
                )
            },
            Arrangement.SpaceBetween, Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).clickable { applyPreset(page); onChange() }, Alignment.Center) { CbIcon(Icon.REFRESH, Color.White, size = 20.dp) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(StylePreset.all[page].name.uppercase(), style = monoStyle(DarkCb.mint, 2.sp).copy(fontSize = 13.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StylePreset.all.indices.forEach { i ->
                        Box(Modifier.size(if (i == page) 8.dp else 6.dp).clip(CircleShape).background(glide(i == page, DarkCb.mint, Color.White.copy(alpha = 0.4f))))
                    }
                }
            }
            Box(Modifier.size(40.dp))
        }
        Visible(open != null && open != StyleControl.VIGNETTE, enter = popEnter, exit = popExit) {
            val c = open ?: StyleControl.TONE
            val low = if (c == StyleControl.SOFT || c == StyleControl.SHARP) 0 else -10
            val high = if (c == StyleControl.SOFT) Look.SOFT_MAX else 10
            val items = (low..high).toList()
            StepDial(
                c.title.uppercase(), items.map { if (c == StyleControl.SOFT) SoftLight.label(it) else if (it > 0) "+$it" else "$it" },
                styleValue(c) - low, { i -> setStyle(c, items[i]); onChange() }, { setStyle(c, 0); onChange() }, autoLabel = "RESET",
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), Arrangement.SpaceEvenly) {
            StyleControl.entries.forEach { c ->
                val on = open == c || (c == StyleControl.VIGNETTE && AppSettings.styleVignette)
                Column(
                    Modifier.width(48.dp).clickable {
                        if (c == StyleControl.VIGNETTE) { setStyle(c, if (AppSettings.styleVignette) 0 else 1); open = null; onChange() } else open = if (open == c) null else c
                    },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(styleText(c), style = monoStyle(glide(on, DarkCb.mint, DarkCb.ink2), 0.2.sp).copy(fontSize = 11.sp), maxLines = 1)
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (on) DarkCb.mint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.1f)), Alignment.Center) {
                        StyleGlyph(c, glide(on, DarkCb.mint, Color.White))
                    }
                }
            }
        }
    }
}

// ---- composition guides ----

/** Grid lines over the picture band: thirds, diagonals or a golden spiral. */
@Composable
internal fun GridOverlay(kind: GridKind, modifier: Modifier = Modifier) {
    val tint = Color.White.copy(alpha = 0.5f)
    Canvas(modifier) {
        val stroke = 1.dp.toPx()
        fun DrawScope.lines(list: List<com.claudeborne.camera.look.Segment>) = list.forEach { drawLine(tint, Offset(it.x1, it.y1), Offset(it.x2, it.y2), stroke) }
        when (kind) {
            GridKind.NONE -> Unit
            GridKind.GRID -> lines(GridMath.thirds(size.width, size.height))
            GridKind.DIAGONAL -> lines(GridMath.diagonals(size.width, size.height))
            GridKind.SPIRAL -> {
                val pts = GridMath.spiral(size.width, size.height)
                drawPath(Path().apply { pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) } }, tint, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** The two edges of the sharp band of Tilt-shift, drawn over the picture while the mode is on (the picture outside them is blurred). */
@Composable
internal fun TiltBand(tilt: com.claudeborne.camera.look.Tilt, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val h = size.height
        val theta = Math.toRadians(tilt.angle.toDouble())
        val dir = Offset(Math.cos(theta).toFloat(), -Math.sin(theta).toFloat()) // along the band, in pixels (y down)
        val normal = Offset(-Math.sin(theta).toFloat(), -Math.cos(theta).toFloat()) // across the band, towards its top edge
        val centre = Offset(size.width / 2f, (1f - tilt.centre) * h)
        val reach = size.width + h
        for (side in listOf(-1f, 1f)) {
            val origin = centre + normal * (side * tilt.half * h)
            drawLine(Color.White.copy(alpha = 0.75f), origin - dir * reach, origin + dir * reach, 1.5.dp.toPx())
        }
        drawLine(DarkCb.mint.copy(alpha = 0.5f), centre - dir * 40.dp.toPx(), centre + dir * 40.dp.toPx(), 2.dp.toPx())
    }
}
