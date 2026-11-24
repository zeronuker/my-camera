package com.claudeborne.camera.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp

/**
 * Master's own look: brand-gradient chamfered corner brackets round the picture, an optional thirds grid, and, when Master is
 * entered, a short sweep: the brackets draw in from outside while a scan line runs down the picture. Drawn on the screen only; nothing of
 * it reaches a photo or video. [top] and [height] are the band the picture fills (pixels).
 */
@Composable
internal fun MasterFrame(active: Boolean, top: Int, height: Int, grid: Boolean, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 700 else MOTION_FAST_MS, easing = FastOutSlowInEasing), label = "master frame")
    val sweep = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (active) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(950, easing = LinearOutSlowInEasing))
        }
    }
    if (p <= 0.001f) return
    val brush = remember { Brush.horizontalGradient(listOf(DarkCb.mint, DarkCb.blue, DarkCb.violet)) }
    Canvas(modifier.fillMaxSize()) {
        val y0 = top.toFloat()
        val h = if (height > 0) height.toFloat() else size.height
        val inset = lerp(48.dp.toPx(), 10.dp.toPx(), p)
        val cut = 22.dp.toPx()
        val leg = 46.dp.toPx() * p
        val left = inset
        val right = size.width - inset
        val upper = y0 + inset
        val lower = y0 + h - inset
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        fun corner(x: Float, y: Float, sx: Float, sy: Float) {
            val path = Path().apply {
                moveTo(x, y + sy * (cut + leg))
                lineTo(x, y + sy * cut)
                lineTo(x + sx * cut, y)
                lineTo(x + sx * (cut + leg), y)
            }
            drawPath(path, brush, alpha = p, style = stroke)
        }
        corner(left, upper, 1f, 1f)
        corner(right, upper, -1f, 1f)
        corner(left, lower, 1f, -1f)
        corner(right, lower, -1f, -1f)
        if (grid) {
            val gridColor = Color.White.copy(alpha = 0.22f * p)
            val w = right - left
            val v = lower - upper
            for (i in 1..2) {
                drawLine(gridColor, Offset(left + w * i / 3f, upper), Offset(left + w * i / 3f, lower), 0.8.dp.toPx())
                drawLine(gridColor, Offset(left, upper + v * i / 3f), Offset(right, upper + v * i / 3f), 0.8.dp.toPx())
            }
        }
        scanLine(brush, y0, h, sweep.value)
    }
}

/** The line that runs down the picture once when Master opens: bright at its head, with a short fading trail above it. */
private fun DrawScope.scanLine(brush: Brush, y0: Float, h: Float, t: Float) {
    if (t >= 1f) return
    val y = y0 + h * t
    val fade = 1f - t
    val trail = 70.dp.toPx()
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, DarkCb.mint.copy(alpha = 0.16f * fade)), startY = y - trail, endY = y),
        topLeft = Offset(0f, y - trail), size = androidx.compose.ui.geometry.Size(size.width, trail),
    )
    drawLine(brush, Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), alpha = 0.9f * fade)
}

/** The exposure readout under the top pills in Master: ISO, shutter, white balance and lens, each fading in one after the other. */
@Composable
internal fun MasterReadout(iso: Int, shutter: String, wb: String, lens: String, modifier: Modifier = Modifier) {
    Row(modifier.chrome().padding(horizontal = 16.dp, vertical = 8.dp), Arrangement.spacedBy(18.dp), Alignment.CenterVertically) {
        ReadoutCell("ISO", if (iso > 0) "$iso" else "–", 0)
        ReadoutCell("SHUTTER", shutter, 70)
        ReadoutCell("WB", wb, 140)
        ReadoutCell("LENS", lens, 210)
    }
}

@Composable
private fun ReadoutCell(label: String, value: String, delayMs: Int) {
    Column(Modifier.appear(delayMs), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, maxLines = 1, softWrap = false, style = monoStyle(DarkCb.mint, 0.4.sp).copy(fontSize = 13.sp))
        Text(label, maxLines = 1, softWrap = false, style = monoStyle(DarkCb.inkDim, 1.2.sp).copy(fontSize = 8.sp))
    }
}
