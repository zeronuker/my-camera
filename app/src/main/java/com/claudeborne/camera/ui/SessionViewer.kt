package com.claudeborne.camera.ui

import android.net.Uri
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One photo or video taken since this (lock-screen) launch started. */
internal class SessionShot(val uri: Uri, val video: Boolean)

/**
 * The lock-screen viewer (PLAN.md Phase 3): swipe through the pictures taken since this launch, and nothing else, so a locked phone
 * never shows older photos. Videos show their first picture; playing them needs the phone unlocked.
 */
@Composable
internal fun SessionViewer(shots: List<SessionShot>, onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    val pager = rememberPagerState(initialPage = (shots.size - 1).coerceAtLeast(0)) { shots.size }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val shot = shots[page]
            val picture by produceState<ImageBitmap?>(null, shot.uri) {
                value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(shot.uri, Size(1440, 2560), null) }.getOrNull() }?.asImageBitmap()
            }
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: WaitingLine("LOADING")
                if (shot.video) Text("VIDEO · UNLOCK THE PHONE TO PLAY", Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp), style = monoStyle(DarkCb.ink2, 1.2.sp).copy(fontSize = 12.sp))
            }
        }
        Box(Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            Box(Modifier.background(Color.Black.copy(alpha = 0.6f))) { BrandButton("CLOSE", cb = DarkCb, onClick = onClose) } // readable over a bright picture
        }
        if (shots.size > 1) {
            Text(
                "${pager.currentPage + 1} / ${shots.size}", Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                style = monoStyle(DarkCb.ink2, 1.sp).copy(fontSize = 13.sp),
            )
        }
    }
}
