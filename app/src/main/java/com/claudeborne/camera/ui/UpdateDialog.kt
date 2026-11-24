package com.claudeborne.camera.ui

import android.text.format.Formatter
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeborne.camera.data.Updater
import com.claudeborne.camera.data.Updater.State

/** Offers a newer build, shows the download, then hands it to Android's installer. Stays closed while the app is locked. */
@Composable
internal fun UpdateDialog() {
    val context = LocalContext.current
    val live = Updater.state
    val show = Updater.showDialog && (live is State.Available || live is State.Downloading || live is State.Ready || live is State.Failed)
    // the last step is kept so the card fades out with its text instead of emptying first
    var last by remember { mutableStateOf<State?>(null) }
    if (show) last = live
    val s = (if (show) live else last) ?: return
    val body = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = DarkCb.ink2)
    val busy = s is State.Downloading
    CbDialog("UPDATE", { if (!busy) Updater.dismiss() }, outsideCloses = !busy, show = show) {
        // the content changes with the step (offer, download, ready, problem); the card itself stays
        Crossfade(s::class, animationSpec = tween(MOTION_MS), label = "update") { step ->
            // reads the live state for the step it is drawing; a step that is already gone draws nothing while it fades
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (step) {
                    State.Available::class -> ((if (show) Updater.state else last) as? State.Available)?.let { now ->
                        Text("A new version is ready to download (${Formatter.formatShortFileSize(context, now.size)}).", style = body)
                        Row(Modifier.fillMaxWidth(), Arrangement.End) {
                            ActionText("LATER", DarkCb.inkDim) { Updater.dismiss() }
                            ActionText("DOWNLOAD", DarkCb.mint) { Updater.download(context, now) }
                        }
                    }
                    State.Downloading::class -> ((if (show) Updater.state else last) as? State.Downloading)?.let { now ->
                        Text("DOWNLOADING… ${(now.progress * 100).toInt()}%", style = monoStyle(DarkCb.mint, 1.sp))
                        ProgressBar(now.progress)
                    }
                    State.Ready::class -> ((if (show) Updater.state else last) as? State.Ready)?.let { now ->
                        Text("Downloaded. Android asks you to confirm, installs it, and the app closes. Open it again afterwards.", style = body)
                        Row(Modifier.fillMaxWidth(), Arrangement.End) {
                            ActionText("LATER", DarkCb.inkDim) { Updater.dismiss() }
                            ActionText("INSTALL", DarkCb.mint) { Updater.install(context, now) }
                        }
                    }
                    State.Failed::class -> ((if (show) Updater.state else last) as? State.Failed)?.let { now ->
                        Text("Update failed: ${now.reason}", style = body.copy(color = Warn))
                        Row(Modifier.fillMaxWidth(), Arrangement.End) { ActionText("CLOSE", DarkCb.inkDim) { Updater.dismiss() } }
                    }
                }
            }
        }
    }
}
