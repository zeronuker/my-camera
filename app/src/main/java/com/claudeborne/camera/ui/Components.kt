package com.claudeborne.camera.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.animateFloat
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The ClaudeBorne button, ported from brand.css `.cb-btn`: square, mono, uppercase, a faint line border that turns mint when pressed;
// the primary one is filled with the brand gradient and dark text.

/** True inside [CbDialog]: there, every [ActionText] is drawn as a brand button (mint = primary, red = destructive, anything else = secondary). */
internal val LocalInDialog = compositionLocalOf { false }

@Composable
fun BrandButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    cb: CbColors = LocalCb.current,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val red = Color(0xFFFF6B6B)
    val edge by animateColorAsState(if (pressed && enabled) cb.mint else if (danger) red.copy(alpha = 0.6f) else cb.lineStrong, tween(MOTION_FAST_MS), label = "edge")
    val ink = glide(enabled, if (primary) cb.surface0 else if (danger) red else cb.ink, cb.inkDim)
    val filled = primary && enabled // a primary that cannot be used yet is drawn as a quiet outlined button, so its words stay readable
    val base = modifier.pressScale(press)
    val framed = if (filled) base.background(cb.grad) else base.border(1.dp, edge)
    Text(
        label,
        (if (enabled) framed.clickable(press, null, onClick = onClick) else framed).padding(horizontal = 20.dp, vertical = 12.dp),
        style = monoStyle(ink, 2.2.sp).copy(fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center),
    )
}

/** A button as wide as its parent (the start screen's SEND and RECEIVE). */
@Composable
fun WideBrandButton(label: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
    BrandButton(label, Modifier.fillMaxWidth(), primary = primary, enabled = enabled, onClick = onClick)

/** The brand's status dot (as in the web update toast): mint with a soft halo that breathes, for "live" states such as waiting for a device. */
@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    val cb = LocalCb.current
    val halo by androidx.compose.animation.core.rememberInfiniteTransition(label = "dot").animateFloat(
        0.15f, 0.45f, androidx.compose.animation.core.infiniteRepeatable(tween(1100), androidx.compose.animation.core.RepeatMode.Reverse), label = "halo",
    )
    androidx.compose.foundation.layout.Box(modifier.size(13.dp), androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.layout.Box(Modifier.size(13.dp).background(cb.mint.copy(alpha = halo), androidx.compose.foundation.shape.CircleShape))
        androidx.compose.foundation.layout.Box(Modifier.size(7.dp).background(cb.mint, androidx.compose.foundation.shape.CircleShape))
    }
}

/** A live status line: the dot and the words. */
@Composable
fun WaitingLine(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        LiveDot()
        Text(text, style = monoStyle(LocalCb.current.inkDim, 1.sp))
    }
}

/** The small gap dialogs leave around each button. */
internal val DialogButtonGap = Modifier.padding(horizontal = 4.dp)

/** The close button of a full-screen editor: a red cross, big, with a 48dp touch target. */
@Composable
fun CloseButton(onClick: () -> Unit) {
    Box(Modifier.size(48.dp).semantics { contentDescription = "Close" }.clickable(onClick = onClick), Alignment.Center) {
        CbIcon(Icon.CLOSE, RED, size = 28.dp)
    }
}

/** A [BrandButton] with an icon before its words. */
@Composable
fun IconBrandButton(label: String, icon: Icon, modifier: Modifier = Modifier, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val cb = LocalCb.current
    val red = Color(0xFFFF6B6B)
    val ink = if (primary) cb.surface0 else if (danger) red else cb.ink
    val press = remember { MutableInteractionSource() }
    val base = modifier.pressScale(press)
    val framed = if (primary) base.background(cb.grad) else base.border(1.dp, if (danger) red.copy(alpha = 0.6f) else cb.lineStrong)
    Row(framed.clickable(press, null, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), Alignment.CenterVertically) {
        CbIcon(icon, ink, size = 18.dp)
        Text(label, style = monoStyle(ink, 2.2.sp).copy(fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal))
    }
}

internal val RED = Color(0xFFFF6B6B)

internal fun monoStyle(color: Color, spacing: androidx.compose.ui.unit.TextUnit) =
    androidx.compose.ui.text.TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = spacing, color = color)

// Card frame, all in dp: distance of the outer line and inner line from the card edge
private const val OUTER_AT = 0.75f
private const val INNER_AT = 4f
private const val CUT = 10f // chamfer leg of the outer line
private const val SHRINK = 0.586f // 2 - sqrt(2): chamfer leg lost per dp of inset, keeps the lines parallel

private fun chamferLeg(at: Float) = CUT - (at - OUTER_AT) * SHRINK

internal fun chamfered(w: Float, h: Float, inset: Float, leg: Float) = androidx.compose.ui.graphics.Path().apply {
    val r = w - inset
    val b = h - inset
    moveTo(inset + leg, inset); lineTo(r - leg, inset); lineTo(r, inset + leg); lineTo(r, b - leg)
    lineTo(r - leg, b); lineTo(inset + leg, b); lineTo(inset, b - leg); lineTo(inset, inset + leg); close()
}

/** Brand card frame: a chamfered double line in the brand gradient, with an optional chamfered [fill]. Content needs about 6dp of padding. */
internal fun Modifier.doubleChamferFrame(cb: CbColors, fill: Color? = null) = drawBehind {
    val line = androidx.compose.ui.graphics.Brush.linearGradient(listOf(cb.mint, cb.blue, cb.violet), androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, size.height))
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * density, join = androidx.compose.ui.graphics.StrokeJoin.Miter)
    if (fill != null) drawPath(chamfered(size.width, size.height, OUTER_AT * density, CUT * density), fill)
    drawPath(chamfered(size.width, size.height, OUTER_AT * density, CUT * density), line, style = stroke)
    drawPath(chamfered(size.width, size.height, INNER_AT * density, chamferLeg(INNER_AT) * density), line, style = androidx.compose.ui.graphics.drawscope.Stroke(1f * density, join = androidx.compose.ui.graphics.StrokeJoin.Miter))
}

/** Brand dialog: chamfered double-border card over a dimmed screen. */
@Composable
internal fun CbDialog(title: String, onDismiss: () -> Unit, subtitle: String? = null, outsideCloses: Boolean = true, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { if (outsideCloses) onDismiss() }, Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(shown, enter = popEnter) {
                androidx.compose.runtime.CompositionLocalProvider(LocalInDialog provides true) {
                    androidx.compose.foundation.layout.Column(
                        Modifier.padding(horizontal = 24.dp).fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {}
                            .doubleChamferFrame(DarkCb, DarkCb.surface1).padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = monoStyle(DarkCb.mint, 2.6.sp))
                            subtitle?.let { Text(it, style = monoStyle(DarkCb.inkDim, 1.sp)) }
                        }
                        content()
                    }
                }
            }
        }
    }
}

/** In a dialog the same call is a brand button (mint = primary, red = destructive); elsewhere a quiet text action. */
@Composable
internal fun ActionText(label: String, color: Color, onClick: () -> Unit) {
    if (LocalInDialog.current) {
        val primary = color == DarkCb.mint || color == LocalCb.current.mint
        BrandButton(label, DialogButtonGap, primary = primary, danger = color == RED, cb = DarkCb, onClick = onClick)
        return
    }
    Text(
        label,
        Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        style = monoStyle(animateColorAsState(color, tween(MOTION_FAST_MS), label = "action").value, 2.2.sp),
    )
}

/** Status and navigation bar icons: dark ones for a light screen, light ones for a dark screen (the viewfinder is always dark). */
@Composable
internal fun SystemBarIcons(darkIcons: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}
