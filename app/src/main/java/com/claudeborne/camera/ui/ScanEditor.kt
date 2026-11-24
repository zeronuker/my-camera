package com.claudeborne.camera.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.camera.scan.DocRules
import com.claudeborne.camera.scan.DocScanner
import com.claudeborne.camera.scan.Enhance
import com.claudeborne.camera.scan.Pt
import com.claudeborne.camera.scan.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/** A photo of a page waiting in the editor: the full-size JPEG (turned by [rotation] to be upright), a smaller upright [preview], and the corners found on it. */
internal class ScanDraft(val jpeg: ByteArray, val rotation: Int, val preview: Bitmap, val quad: Quad, val found: Boolean)

/** The live outline round the page in Scan Docs: a mint four-sided shape that glides to the page it finds and fades when there is none. */
@Composable
internal fun QuadOutline(quad: Quad?, mirror: Boolean, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(Quad.FULL) }
    if (quad != null) last = quad
    val spec = tween<Float>(MOTION_MS)
    val xs = last.points.map { animateFloatAsState(it.x, spec, label = "quad x").value }
    val ys = last.points.map { animateFloatAsState(it.y, spec, label = "quad y").value }
    val alpha by animateFloatAsState(if (quad != null) 1f else 0f, tween(MOTION_MS), label = "quad alpha")
    Canvas(modifier) {
        if (alpha <= 0.01f) return@Canvas
        val path = Path()
        for (i in 0 until 4) {
            val x = (if (mirror) 1f - xs[i] else xs[i]) * size.width
            val y = ys[i] * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, DarkCb.mint.copy(alpha = 0.16f * alpha), style = Fill)
        drawPath(path, DarkCb.mint.copy(alpha = alpha), style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
    }
}

/**
 * A scanned page: drag the four corners onto the page (ADJUST) or look at the flattened result in a clean-up (COLOUR, GREY, B&W),
 * then keep it with ADD PAGE (more pages follow) or DONE.
 */
@Composable
internal fun ScanEditor(
    draft: ScanDraft, busy: Boolean, pageNumber: Int,
    onRetake: () -> Unit, onAdd: (Quad, Enhance) -> Unit, onDone: (Quad, Enhance) -> Unit,
    modifier: Modifier = Modifier,
) {
    var quad by remember(draft) { mutableStateOf(draft.quad) }
    var view by remember(draft) { mutableIntStateOf(-1) } // -1 adjust the corners, otherwise the index of the clean-up being shown
    var preview by remember(draft) { mutableStateOf<Bitmap?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val enhance = Enhance.entries[if (view < 0) 0 else view]
    val ok = DocRules.plausible(quad, 0.05f)
    LaunchedEffect(draft, view, quad) {
        if (view >= 0 && ok) {
            preview = null
            preview = withContext(Dispatchers.Default) { runCatching { DocScanner.enhance(DocScanner.flatten(draft.preview, quad, 1400), enhance) }.getOrNull() }
        }
    }
    Box(modifier.fillMaxSize().background(DarkCb.surface0)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("SCAN DOCS", style = monoStyle(DarkCb.mint, 2.6.sp))
                Text("PAGE $pageNumber", style = monoStyle(DarkCb.inkDim, 1.5.sp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color.Black).onSizeChanged { size = it }, Alignment.Center) {
                Crossfade(view, animationSpec = tween(MOTION_MS), label = "scan view") { v ->
                    val shown = if (v < 0) draft.preview else preview
                    if (shown != null) Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else LoadingPane("FLATTENING", null, Modifier.fillMaxSize())
                }
                if (view < 0) {
                    val bw = draft.preview.width.toFloat()
                    val bh = draft.preview.height.toFloat()
                    val k = min(size.width / bw, size.height / bh)
                    val iw = bw * k
                    val ih = bh * k
                    val ox = (size.width - iw) / 2f
                    val oy = (size.height - ih) / 2f
                    var held by remember(draft) { mutableIntStateOf(-1) }
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(draft) {
                            detectDragGestures(
                                onDragStart = { at -> held = DocRules.nearest(quad, Pt((at.x - ox) / iw, (at.y - oy) / ih), 0.14f) },
                                onDragEnd = { held = -1 },
                                onDragCancel = { held = -1 },
                            ) { change, _ ->
                                if (held >= 0) quad = quad.with(held, DocRules.clamp(Pt((change.position.x - ox) / iw, (change.position.y - oy) / ih)))
                            }
                        },
                    ) {
                        val path = Path()
                        val pts = quad.points.map { Offset(ox + it.x * iw, oy + it.y * ih) }
                        pts.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                        path.close()
                        drawPath(path, DarkCb.mint.copy(alpha = 0.14f), style = Fill)
                        drawPath(path, DarkCb.mint, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
                        pts.forEachIndexed { i, o ->
                            drawCircle(Color.Black.copy(alpha = 0.35f), 15.dp.toPx(), o)
                            drawCircle(if (i == held) Color.White else DarkCb.mint, 15.dp.toPx(), o, style = Stroke(2.5.dp.toPx()))
                        }
                    }
                }
            }
            val hint = when {
                !ok -> "The corners cross. Drag them back onto the page." to Warn
                view >= 0 -> "Flattened and cleaned up. Choose ADJUST to move the corners." to DarkCb.ink2
                !draft.found -> "No page edge found. Drag the corners onto the page." to Warn
                else -> "Drag the corners to fit the page." to DarkCb.ink2
            }
            Text(hint.first, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = hint.second))
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                listOf("ADJUST") .plus(Enhance.entries.map { it.label }).forEachIndexed { i, label ->
                    val on = view == i - 1
                    val fill by androidx.compose.animation.animateColorAsState(if (on) DarkCb.mint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f), tween(MOTION_FAST_MS), label = "scan chip")
                    Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(fill).clickable { view = i - 1 }, Alignment.Center) {
                        Text(label, style = monoStyle(glide(on, DarkCb.mint, Color.White), 0.5.sp).copy(fontSize = 12.sp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                BrandButton("RETAKE", Modifier.weight(1f), cb = DarkCb, enabled = !busy, onClick = onRetake)
                BrandButton("ADD PAGE", Modifier.weight(1f), cb = DarkCb, enabled = ok && !busy) { onAdd(quad, enhance) }
                BrandButton("DONE", Modifier.weight(1f), primary = true, cb = DarkCb, enabled = ok && !busy) { onDone(quad, enhance) }
            }
        }
        BusyOverlay(busy, "SAVING PAGE", null)
    }
}

/** How to save the scanned pages: one PDF, or a JPEG photo per page. */
@Composable
internal fun ScanSaveDialog(show: Boolean, pages: Int, saving: Boolean, progress: Float?, onPdf: () -> Unit, onJpeg: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    CbDialog("SAVE SCAN", onDismiss, subtitle = if (pages == 1) "1 page" else "$pages pages", outsideCloses = !saving, show = show) {
        if (saving) {
            WorkingLine("SAVING", progress)
        } else {
            BrandButton("SAVE AS PDF", Modifier.fillMaxWidth(), primary = true, cb = DarkCb, onClick = onPdf)
            BrandButton("SAVE AS PHOTOS", Modifier.fillMaxWidth(), cb = DarkCb, onClick = onJpeg)
            BrandButton("DISCARD PAGES", Modifier.fillMaxWidth(), danger = true, cb = DarkCb, onClick = onDiscard)
        }
    }
}
