package com.claudeborne.camera.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background

private fun iconOf(m: Mode): Icon = when (m) {
    Mode.MASTER -> Icon.HISTOGRAM
    Mode.VIDEO, Mode.PROVIDEO -> Icon.VIDEO
    Mode.PHOTO -> Icon.CAMERA
    Mode.PORTRAIT -> Icon.RETOUCH
    Mode.TEXT -> Icon.KEYBOARD
    Mode.NIGHT -> Icon.SOFT_LIGHT
    Mode.PANO -> Icon.ASPECT
    Mode.LONGEXP -> Icon.SUN
    Mode.SCAN -> Icon.DOCUMENT
    Mode.SLOMO -> Icon.INTERVAL
    Mode.TIMELAPSE -> Icon.TIMER
    Mode.TILTSHIFT -> Icon.FILTER
    Mode.UNDERWATER -> Icon.FOCUS_LOCK
    Mode.DUALPHOTO, Mode.DUAL, Mode.QUAD -> Icon.DUAL
}

/**
 * The More grid, as the stock one: the modes that are not in the strip, four to a row, with a pencil at the bottom right.
 * With the pencil on, every mode shows with arrows that move it one place earlier or later; the first five are the strip.
 */
@Composable
internal fun MoreGrid(
    shown: List<Mode>, selected: Mode, editing: Boolean,
    onPick: (Mode) -> Unit, onMove: (Mode, Int) -> Unit, onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = if (editing) shown else ModeOrder.more(shown)
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).chrome(RoundedCornerShape(28.dp), ChromePanelFill).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (editing) Text("MOVE MODES · THE FIRST FIVE SIT IN THE STRIP", style = monoStyle(DarkCb.mint, 1.2.sp).copy(fontSize = 11.sp))
        modes.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { m ->
                    Box(Modifier.weight(1f), Alignment.Center) {
                        ModeTile(m, m == selected, editing, inStrip = shown.indexOf(m) < ModeOrder.PINNED, canEarlier = shown.indexOf(m) > 0, canLater = shown.indexOf(m) < shown.lastIndex, onPick = { onPick(m) }, onMove = { by -> onMove(m, by) })
                    }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth(), Arrangement.End) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onEdit), Alignment.Center) {
                CbIcon(if (editing) Icon.CHECK else Icon.PENCIL, glide(editing, DarkCb.mint, Color.White), size = 24.dp)
            }
        }
    }
}

@Composable
private fun ModeTile(m: Mode, active: Boolean, editing: Boolean, inStrip: Boolean, canEarlier: Boolean, canLater: Boolean, onPick: () -> Unit, onMove: (Int) -> Unit) {
    val fill by animateColorAsState(if (active) DarkCb.mint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.1f), tween(MOTION_FAST_MS), label = "tile")
    val rim by animateColorAsState(if (editing && inStrip) DarkCb.mint.copy(alpha = 0.7f) else Color.Transparent, tween(MOTION_FAST_MS), label = "tile rim")
    Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(62.dp).clip(CircleShape).background(fill).border(1.dp, rim, CircleShape).clickable(enabled = !editing, onClick = onPick), Alignment.Center) {
            CbIcon(iconOf(m), if (active) DarkCb.mint else Color.White, size = 26.dp)
        }
        Text(m.label, style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = if (active) DarkCb.mint else DarkCb.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center), maxLines = 2, softWrap = false)
        if (editing) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("‹", Modifier.clickable(enabled = canEarlier) { onMove(-1) }.padding(horizontal = 8.dp), style = monoStyle(if (canEarlier) DarkCb.mint else DarkCb.inkDim, 0.sp).copy(fontSize = 22.sp))
            Text("›", Modifier.clickable(enabled = canLater) { onMove(1) }.padding(horizontal = 8.dp), style = monoStyle(if (canLater) DarkCb.mint else DarkCb.inkDim, 0.sp).copy(fontSize = 22.sp))
        }
    }
}
