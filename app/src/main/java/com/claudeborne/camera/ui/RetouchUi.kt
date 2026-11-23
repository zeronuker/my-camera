package com.claudeborne.camera.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.camera.face.Retouch
import com.claudeborne.camera.face.RetouchPreset
import com.claudeborne.camera.face.RetouchSlider

/** What each of the eleven sliders and three presets draws inside its round button: a face outline with the changed part marked. */
@Composable
private fun RetouchGlyph(slider: RetouchSlider?, preset: RetouchPreset?, tint: Color) {
    Canvas(Modifier.size(26.dp)) {
        val k = size.width / 24f
        val line = Stroke(1.6f * k, cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        if (preset == RetouchPreset.ORIGINAL) { // the "none" sign
            drawCircle(tint, 8.5f * k, p(12f, 12f), style = line)
            drawLine(tint, p(6f, 18f), p(18f, 6f), 1.6f * k, StrokeCap.Round)
            return@Canvas
        }
        // face outline for everything else
        drawOval(tint, p(6f, 3.5f), Size(12f * k, 17f * k), style = line)
        when (slider ?: RetouchSlider.TEXTURE) {
            RetouchSlider.TEXTURE -> drawArc(tint, 90f, 180f, true, p(6f, 3.5f), Size(12f * k, 17f * k), style = Fill)
            RetouchSlider.TONE -> drawCircle(tint, 3.2f * k, p(12f, 12f))
            RetouchSlider.CHEEKS -> { drawCircle(tint, 1.6f * k, p(8.8f, 13f)); drawCircle(tint, 1.6f * k, p(15.2f, 13f)) }
            RetouchSlider.HEAD -> drawOval(tint, p(3.5f, 1.5f), Size(17f * k, 21f * k), style = Stroke(1f * k))
            RetouchSlider.EYES -> { drawCircle(tint, 1.8f * k, p(9.2f, 10f)); drawCircle(tint, 1.8f * k, p(14.8f, 10f)) }
            RetouchSlider.WIDTH -> { drawLine(tint, p(2.5f, 12f), p(5.5f, 12f), 1.6f * k, StrokeCap.Round); drawLine(tint, p(18.5f, 12f), p(21.5f, 12f), 1.6f * k, StrokeCap.Round) }
            RetouchSlider.LENGTH -> { drawLine(tint, p(12f, 0.8f), p(12f, 3f), 1.6f * k, StrokeCap.Round); drawLine(tint, p(12f, 21f), p(12f, 23.2f), 1.6f * k, StrokeCap.Round) }
            RetouchSlider.HAIRLINE -> drawLine(tint, p(8.5f, 6.5f), p(15.5f, 6.5f), 1.8f * k, StrokeCap.Round)
            RetouchSlider.TEMPLES -> { drawCircle(tint, 1.4f * k, p(7.8f, 8f)); drawCircle(tint, 1.4f * k, p(16.2f, 8f)) }
            RetouchSlider.CHEEKBONES -> { drawCircle(tint, 1.4f * k, p(7.8f, 12f)); drawCircle(tint, 1.4f * k, p(16.2f, 12f)) }
            RetouchSlider.NOSE -> drawPath(Path().apply { moveTo(11f * k, 9f * k); lineTo(10f * k, 14.5f * k); lineTo(12f * k, 15f * k); lineTo(14f * k, 14.5f * k) }, tint, style = line)
        }
        if (preset == RetouchPreset.CLASSIC) { drawLine(tint, p(20f, 2f), p(20f, 6f), 1.4f * k, StrokeCap.Round); drawLine(tint, p(18f, 4f), p(22f, 4f), 1.4f * k, StrokeCap.Round) }
        if (preset == RetouchPreset.NATURAL) drawPath(Path().apply { moveTo(9f * k, 14.5f * k); quadraticTo(12f * k, 17f * k, 15f * k, 14.5f * k) }, tint, style = line)
    }
}

@Composable
private fun RetouchButton(label: String, on: Boolean, badge: String? = null, onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    val fill by animateColorAsState(if (on) DarkCb.mint.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.1f), tween(MOTION_FAST_MS), label = "retouch fill")
    Column(Modifier.width(66.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(badge ?: " ", style = monoStyle(glide(on, DarkCb.mint, DarkCb.ink2), 0.2.sp).copy(fontSize = 11.sp), maxLines = 1)
        Box(Modifier.size(46.dp).clip(CircleShape).background(fill), Alignment.Center) { glyph(glide(on, DarkCb.mint, Color.White)) }
        Text(label, style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = glide(on, DarkCb.mint, DarkCb.ink2)), maxLines = 1, softWrap = false)
    }
}

/**
 * The Retouch panel as in the stock app: Original, Natural and Classic, a divider, Reset, then the eleven sliders in one scrolling row.
 * A slider button opens its dial above the row (0 to 100, in steps of 5); the picture behind changes as the dial moves.
 */
@Composable
internal fun RetouchPanel(retouch: Retouch, onChange: (Retouch) -> Unit) {
    var open by remember { mutableStateOf<RetouchSlider?>(null) }
    val preset = RetouchPreset.of(retouch)
    LookPanel {
        Visible(open != null, enter = popEnter, exit = popExit) {
            val s = open ?: RetouchSlider.TEXTURE
            val items = remember { (0..100 step 5).map { "$it" } }
            StepDial(s.title.uppercase(), items, s.get(retouch) / 5, { i -> onChange(s.set(retouch, i * 5)) }, { onChange(s.set(retouch, 0)) }, autoLabel = "RESET")
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), Arrangement.spacedBy(4.dp), Alignment.Bottom) {
            RetouchPreset.entries.forEach { pr ->
                RetouchButton(pr.title, preset == pr, onClick = { open = null; onChange(pr.values.copy(correct = retouch.correct)) }) { RetouchGlyph(null, pr, it) }
            }
            Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(58.dp).background(Color.White.copy(alpha = 0.2f)))
            RetouchButton("Reset", false, onClick = { open = null; onChange(Retouch.NONE.copy(correct = retouch.correct)) }) { c -> CbIcon(Icon.REFRESH, c, size = 24.dp) }
            RetouchSlider.entries.forEach { s ->
                val v = s.get(retouch)
                RetouchButton(s.title, open == s || v > 0, badge = if (v > 0) "$v" else null, onClick = { open = if (open == s) null else s }) { RetouchGlyph(s, null, it) }
            }
        }
    }
}
