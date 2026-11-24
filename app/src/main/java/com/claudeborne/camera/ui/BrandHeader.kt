package com.claudeborne.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The C emblem's two chamfered lines, on a 512 grid (same as the gallery manager's splash).
private val OUTER = listOf(50f to 334f, 50f to 86f, 86f to 50f, 426f to 50f, 462f to 86f, 462f to 426f, 426f to 462f, 178f to 462f)
private val INNER = listOf(66f to 330f, 66f to 102f, 102f to 66f, 410f to 66f, 446f to 102f, 446f to 410f, 410f to 446f, 190f to 446f)

private fun DrawScope.polyline(points: List<Pair<Float, Float>>, color: Color, width: Float) {
    val s = size.width / 512f
    val path = Path().apply {
        moveTo(points[0].first * s, points[0].second * s)
        points.drop(1).forEach { lineTo(it.first * s, it.second * s) }
    }
    drawPath(path, color, style = Stroke(width * s, join = StrokeJoin.Miter))
}

/**
 * Header of Settings and its pages, as in the gallery manager: the C emblem and brand name on the left, the page title (gradient) with [count]
 * under it on the right, and a back link under the emblem.
 */
@Composable
internal fun BrandHeader(title: String, count: String, onBack: () -> Unit) {
    val cb = LocalCb.current
    val density = LocalDensity.current
    Column {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 1.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
                Box(Modifier.size(64.dp), Alignment.Center) {
                    Canvas(Modifier.matchParentSize()) {
                        polyline(OUTER, cb.mint, 10f)
                        polyline(INNER, cb.mint, 6f)
                    }
                    Text("C", style = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = 58.sp, color = cb.mint, drawStyle = Stroke(with(density) { 2.dp.toPx() })))
                }
                Column(Modifier.width(IntrinsicSize.Max), Arrangement.spacedBy(1.dp), Alignment.CenterHorizontally) {
                    Text(
                        "CLAUDEBORNE",
                        style = TextStyle(
                            fontFamily = Tourney, fontSize = 16.6.sp,
                            brush = Brush.horizontalGradient(0f to cb.mint, 0.55f to cb.blue, 1f to cb.violet),
                            drawStyle = Stroke(with(density) { 0.6.dp.toPx() }),
                        ),
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(cb.lineStrong))
                    Text(
                        "CIRRUS STRATUS", Modifier.fillMaxWidth(), maxLines = 1, softWrap = false,
                        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 3.sp, color = cb.blue, textAlign = TextAlign.Center),
                    )
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(
                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Tourney, fontWeight = FontWeight.Bold, fontSize = if (title.length > 10) 18.sp else 28.sp, brush = cb.grad),
                )
                if (count.isNotEmpty()) Text(count, maxLines = 1, softWrap = false, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp, color = cb.inkDim))
            }
        }
        Text(
            "← BACK", Modifier.clickable(onClick = onBack).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp, letterSpacing = 2.6.sp, color = cb.mint),
        )
    }
}
