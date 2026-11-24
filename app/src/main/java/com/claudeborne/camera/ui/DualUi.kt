package com.claudeborne.camera.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.camera.dual.Layout
import com.claudeborne.camera.dual.Tile
import kotlin.math.roundToInt

/**
 * The tiles of a dual or quad picture, each camera gliding to its new place when the layout changes, a camera is swapped or the small
 * picture moves ([animate] false while a finger drags it, so it follows at once). Tiles are returned biggest first, the order they are drawn in.
 */
@Composable
internal fun animateTiles(target: List<Tile>, animate: Boolean): List<Tile> {
    val spec = if (animate) tween<Float>(MOTION_MS) else snap()
    val last = HashMap<Int, Tile>()
    val out = ArrayList<Tile>()
    // a fixed number of animated values (four cameras at most), so the number of calls never changes between recompositions
    for (source in 0 until 4) {
        val t = target.firstOrNull { it.source == source }
        val keep = last[source]
        val x by animateFloatAsState(t?.x ?: keep?.x ?: 0f, spec, label = "tile x")
        val y by animateFloatAsState(t?.y ?: keep?.y ?: 0f, spec, label = "tile y")
        val w by animateFloatAsState(t?.w ?: keep?.w ?: 0f, spec, label = "tile w")
        val h by animateFloatAsState(t?.h ?: keep?.h ?: 0f, spec, label = "tile h")
        if (t != null) out += Tile(source, x, y, w, h, t.mirror, t.border)
    }
    return out.sortedByDescending { it.w * it.h }
}

@Composable
private fun LayoutGlyph(layout: Layout, tint: Color) {
    Canvas(Modifier.size(26.dp)) {
        val k = size.width / 24f
        val line = Stroke(1.6f * k)
        fun rect(x: Float, y: Float, w: Float, h: Float, filled: Boolean = false) =
            if (filled) drawRoundRect(tint, Offset(x * k, y * k), Size(w * k, h * k), CornerRadius(1.5f * k))
            else drawRoundRect(tint, Offset(x * k, y * k), Size(w * k, h * k), CornerRadius(1.5f * k), style = line)
        when (layout) {
            Layout.PIP -> { rect(3f, 3f, 18f, 18f); rect(12f, 5.5f, 7f, 8f, filled = true) }
            Layout.TOP_BOTTOM -> { rect(3f, 3f, 18f, 8f); rect(3f, 13f, 18f, 8f) }
            Layout.SIDE_BY_SIDE -> { rect(3f, 3f, 8f, 18f); rect(13f, 3f, 8f, 18f) }
        }
    }
}

/** The row above the record button in Dual view: the three layouts and the swap button. */
@Composable
internal fun DualBar(layout: Layout, onLayout: (Layout) -> Unit, onSwap: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.42f)).padding(horizontal = 8.dp, vertical = 6.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
        Layout.entries.forEach { l ->
            val on = l == layout
            val fill by animateColorAsState(if (on) DarkCb.mint.copy(alpha = 0.24f) else Color.Transparent, tween(MOTION_FAST_MS), label = "layout fill")
            Box(Modifier.size(44.dp).clip(CircleShape).background(fill).clickable(enabled = enabled) { onLayout(l) }, Alignment.Center) { LayoutGlyph(l, glide(on, DarkCb.mint, Color.White)) }
        }
        Box(Modifier.size(1.dp, 28.dp).background(Color.White.copy(alpha = 0.2f)))
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onSwap), Alignment.Center) { CbIcon(Icon.REFRESH, Color.White, size = 24.dp) }
    }
}

/** A small label over the picture in Quad view: how many rear cameras are really streaming. */
@Composable
internal fun QuadLabel(rear: Int, note: String? = null) {
    Text(
        "$rear REAR + FRONT" + (note?.let { " · $it" } ?: ""),Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.42f)).padding(horizontal = 14.dp, vertical = 8.dp),
        style = monoStyle(DarkCb.mint, 1.5.sp).copy(fontSize = 12.sp),
    )
}

/**
 * Sits exactly over the small picture of Picture-in-picture: drag to move it, pinch to resize it, tap to swap the two cameras.
 * [tile] is its place in fractions of the picture band ([boxW] x [boxH] pixels starting [boxTop] pixels from the top of the screen).
 */
@Composable
internal fun PipHandle(tile: Tile, boxTop: Int, boxW: Int, boxH: Int, onMove: (Float, Float) -> Unit, onScale: (Float) -> Unit, onDrag: (Boolean) -> Unit, onTap: () -> Unit) {
    val density = LocalDensity.current
    val move by rememberUpdatedState(onMove)
    val scale by rememberUpdatedState(onScale)
    val drag by rememberUpdatedState(onDrag)
    val x = (tile.x * boxW).roundToInt()
    val y = boxTop + ((1f - tile.y - tile.h) * boxH).roundToInt()
    Box(
        Modifier.offset { IntOffset(x, y) }
            .size(with(density) { (tile.w * boxW).toDp() }, with(density) { (tile.h * boxH).toDp() })
            .pointerInput(boxW, boxH) {
                detectTransformGestures { _, pan, zoom, _ ->
                    drag(true)
                    move(pan.x / boxW, -pan.y / boxH) // y counts up in the picture
                    if (zoom != 1f) scale(zoom)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onPress = { tryAwaitRelease(); drag(false) }) },
    )
}
