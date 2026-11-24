package com.claudeborne.camera.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The settings look: grouped cards in ClaudeBorne frames, rows with a switch, a value or a radio, and a segmented tab bar.
// They take the phone's light or dark theme through LocalCb.

class GroupBuilder {
    internal val items = mutableListOf<@Composable () -> Unit>()

    /** One row of the card; a thin line is drawn between rows. */
    fun row(content: @Composable () -> Unit) { items += content }
}

/** A titled card of rows, with an optional explanation under it. */
@Composable
fun Group(title: String? = null, footer: String? = null, build: GroupBuilder.() -> Unit) {
    val cb = LocalCb.current
    val items = GroupBuilder().apply(build).items
    Column(Modifier.fillMaxWidth().padding(top = if (title != null) 6.dp else 0.dp)) {
        // the title in mint with a hairline after it, as in the gallery manager
        title?.let {
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it.uppercase(), style = monoStyle(cb.mint, 2.6.sp))
                Box(Modifier.padding(start = 10.dp).weight(1f).height(1.dp).background(cb.lineStrong))
            }
        }
        items.forEachIndexed { i, item ->
            item()
            if (i < items.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(cb.line))
        }
        footer?.let { Text(it, Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim, lineHeight = 17.sp)) }
    }
}

@Composable
private fun RowBase(onClick: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(click).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun RowScope.TitleBlock(title: String, subtitle: String?) {
    val cb = LocalCb.current
    Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title.uppercase(), style = monoStyle(cb.ink, 1.5.sp).copy(fontSize = 12.sp))
        subtitle?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = cb.inkDim, lineHeight = 17.sp)) }
    }
}

/** A setting with a few short choices: the name, with the choices as tabs under it (the selected one tinted and mint). */
@Composable
fun ChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cb = LocalCb.current
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = monoStyle(cb.ink, 1.5.sp).copy(fontSize = 12.sp))
        Row {
            options.forEachIndexed { i, name ->
                val on = i == selected
                Text(
                    name.uppercase(),
                    Modifier.background(glide(on, cb.surface2, Color.Transparent)).clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 9.dp),
                    maxLines = 1, softWrap = false, style = monoStyle(glide(on, cb.mint, cb.inkDim), 1.2.sp),
                )
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) =
    RowBase({ onChange(!checked) }) {
        TitleBlock(title, subtitle)
        CbSwitch(checked)
    }

/** A row that opens a sub-page; [value] is the current choice shown on the right. */
@Composable
fun NavRow(title: String, value: String? = null, onClick: () -> Unit) {
    val cb = LocalCb.current
    RowBase(onClick) {
        TitleBlock(title, null)
        value?.let { Text(it.uppercase(), Modifier.padding(end = 6.dp), style = monoStyle(cb.mint, 1.sp)) }
        CbIcon(Icon.CHEVRON, cb.inkDim, size = 18.dp)
    }
}

@Composable
fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null) {
    val cb = LocalCb.current
    RowBase(onClick) {
        TitleBlock(title, subtitle)
        val ring by animateColorAsState(if (selected) cb.mint else cb.lineStrong, tween(MOTION_FAST_MS), label = "radio ring")
        val dot by animateFloatAsState(if (selected) 1f else 0f, tween(MOTION_FAST_MS), label = "radio dot")
        Box(Modifier.size(22.dp).border(1.5.dp, ring, CircleShape), Alignment.Center) {
            Box(Modifier.size(12.dp).scale(dot).clip(CircleShape).background(cb.mint))
        }
    }
}

/** On/off switch: the knob glides and the track turns mint. */
@Composable
fun CbSwitch(checked: Boolean) {
    val cb = LocalCb.current
    val track by animateColorAsState(if (checked) cb.mint else cb.surface3, tween(MOTION_FAST_MS), label = "switch track")
    val knob by animateColorAsState(if (checked) DarkCb.surface0 else cb.inkDim, tween(MOTION_FAST_MS), label = "switch knob")
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, tween(MOTION_FAST_MS), label = "switch x")
    Box(Modifier.width(48.dp).height(28.dp).clip(CircleShape).background(track).padding(3.dp)) {
        Box(Modifier.offset(x = x).size(22.dp).clip(CircleShape).background(knob))
    }
}
