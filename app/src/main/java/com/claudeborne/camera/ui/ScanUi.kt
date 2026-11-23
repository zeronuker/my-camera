package com.claudeborne.camera.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.camera.scan.Frac

private val PanelFill = Color.Black.copy(alpha = 0.66f)

/** The chip over the viewfinder when a QR code is found: what it says, and OPEN (only for things safe to open), COPY, SHARE. */
@Composable
internal fun QrChip(kind: String, shown: String, canOpen: Boolean, onOpen: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(PanelFill).border(1.dp, DarkCb.mint.copy(alpha = 0.5f), RoundedCornerShape(24.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("QR · $kind", style = monoStyle(DarkCb.mint, 2.sp))
            Text("✕", Modifier.clickable(onClick = onClose).padding(4.dp), style = monoStyle(DarkCb.ink2, 0.sp).copy(fontSize = 16.sp))
        }
        Text(shown, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = DarkCb.ink), maxLines = 3)
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            if (canOpen) BrandButton("OPEN", Modifier.weight(1f), primary = true, cb = DarkCb, onClick = onOpen)
            BrandButton("COPY", Modifier.weight(1f), cb = DarkCb, onClick = onCopy)
            BrandButton("SHARE", Modifier.weight(1f), cb = DarkCb, onClick = onShare)
        }
    }
}

/** A small pill that suggests something ("Try Text mode"). */
@Composable
internal fun SuggestChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text.uppercase() + "  ›", modifier.clip(CircleShape).background(PanelFill).border(1.dp, DarkCb.mint.copy(alpha = 0.5f), CircleShape).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        style = monoStyle(DarkCb.mint, 1.5.sp).copy(fontSize = 12.sp),
    )
}

/** The live outline round the text in Text mode: a mint rounded box that glides to the text it finds and fades when there is none. [mirror] for the front camera. */
@Composable
internal fun TextOutline(rect: Frac?, mirror: Boolean, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(Frac(0.1f, 0.3f, 0.9f, 0.7f)) }
    if (rect != null) last = rect
    val spec = tween<Float>(MOTION_MS)
    val l by animateFloatAsState(last.left, spec, label = "outline left")
    val t by animateFloatAsState(last.top, spec, label = "outline top")
    val r by animateFloatAsState(last.right, spec, label = "outline right")
    val b by animateFloatAsState(last.bottom, spec, label = "outline bottom")
    val alpha by animateFloatAsState(if (rect != null) 1f else 0f, tween(MOTION_MS), label = "outline alpha")
    Canvas(modifier) {
        if (alpha <= 0.01f) return@Canvas
        val x1 = (if (mirror) 1f - r else l) * size.width
        val x2 = (if (mirror) 1f - l else r) * size.width
        val color = DarkCb.mint.copy(alpha = alpha)
        drawRoundRect(color, Offset(x1, t * size.height), Size(x2 - x1, (b - t) * size.height), CornerRadius(14.dp.toPx()), style = Stroke(2.5.dp.toPx()))
    }
}

/** The recognised text, ready to edit: select (long press), edit, copy, share. */
@Composable
internal fun TextEditor(initial: String, onCopy: (String) -> Unit, onShare: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(initial) }
    Column(modifier.fillMaxSize().background(DarkCb.surface0).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("TEXT", style = monoStyle(DarkCb.mint, 2.6.sp))
            Text("${text.length} CHARACTERS", style = monoStyle(DarkCb.inkDim, 1.5.sp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(DarkCb.surface1).border(1.dp, DarkCb.lineStrong).padding(14.dp)) {
            BasicTextField(
                text, { text = it }, Modifier.fillMaxSize(),
                textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = DarkCb.ink, lineHeight = 23.sp),
                cursorBrush = SolidColor(DarkCb.mint),
            )
        }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            BrandButton("COPY", Modifier.weight(1f), primary = true, cb = DarkCb) { onCopy(text) }
            BrandButton("SHARE", Modifier.weight(1f), cb = DarkCb) { onShare(text) }
            BrandButton("CLOSE", Modifier.weight(1f), cb = DarkCb, onClick = onClose)
        }
    }
}
