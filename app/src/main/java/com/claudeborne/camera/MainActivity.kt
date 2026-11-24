package com.claudeborne.camera

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.claudeborne.camera.capture.PhotoSaver
import com.claudeborne.camera.data.Updater
import com.claudeborne.camera.ui.CameraKeys
import com.claudeborne.camera.ui.ClaudeBorneTheme
import com.claudeborne.camera.ui.Root

class MainActivity : ComponentActivity() {
    private var launch by mutableStateOf(LaunchRequest())
    private var serial = 0

    // the viewfinder can take the volume keys (shutter or zoom, per the setting)
    private fun isVolume(key: Int) = key == android.view.KeyEvent.KEYCODE_VOLUME_UP || key == android.view.KeyEvent.KEYCODE_VOLUME_DOWN

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        isVolume(keyCode) && CameraKeys.handles() && CameraKeys.handler?.invoke(keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP, event.repeatCount == 0) == true ||
            super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean = isVolume(keyCode) && CameraKeys.handles() || super.onKeyUp(keyCode, event)

    private fun requestOf(i: Intent?): LaunchRequest {
        val out = i?.let { runCatching { it.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java) }.getOrNull() }
        return LaunchRequest.of(i?.action, i?.getStringExtra(LaunchRequest.SHORTCUT_EXTRA), out, ++serial)
    }

    /** A launch from the lock screen may show over it; any other launch may not. */
    private fun apply(request: LaunchRequest) {
        launch = request
        com.claudeborne.camera.data.Perf.launched(request.kind.name)
        setShowWhenLocked(request.secure)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        apply(requestOf(intent))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        apply(requestOf(intent))
        // "Cirrus Stratus Dev" (debug) is a different app from the release APK the updater offers, so it never checks
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (savedInstanceState == null && !debug && !launch.secure) Updater.check(this, manual = false)
        setContent { ClaudeBorneTheme { Root(launch, ::deliver) } }
    }

    /**
     * Another app asked for a photo or video (IMAGE_CAPTURE / VIDEO_CAPTURE): hand it over and close. With an output Uri the file is
     * copied there and our own copy removed; without one the saved file's Uri (and for photos a small picture) goes back.
     */
    private fun deliver(saved: Uri?) {
        val request = launch
        if (saved == null) { setResult(Activity.RESULT_CANCELED); finish(); return }
        Thread {
            val out = request.output
            var ok = true
            if (out != null) {
                ok = try {
                    contentResolver.openInputStream(saved)!!.use { input -> contentResolver.openOutputStream(out, "wt")!!.use { input.copyTo(it) } }
                    runCatching { contentResolver.delete(saved, null, null) } // the caller keeps its own file, no duplicate in the camera roll
                    true
                } catch (_: Exception) {
                    false
                }
            }
            val result = Intent()
            if (out == null) {
                result.data = saved
                result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (request.kind == LaunchKind.IMAGE_CAPTURE) result.putExtra("data", PhotoSaver.thumbnail(this, saved))
            }
            runOnUiThread {
                setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
                finish()
            }
        }.start()
    }
}
