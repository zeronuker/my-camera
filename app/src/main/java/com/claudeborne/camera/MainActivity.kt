package com.claudeborne.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.claudeborne.camera.data.Updater
import com.claudeborne.camera.ui.CameraKeys
import com.claudeborne.camera.ui.ClaudeBorneTheme
import com.claudeborne.camera.ui.Root

class MainActivity : ComponentActivity() {
    // the viewfinder can take the volume keys (shutter or zoom, per the setting)
    private fun isVolume(key: Int) = key == android.view.KeyEvent.KEYCODE_VOLUME_UP || key == android.view.KeyEvent.KEYCODE_VOLUME_DOWN

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        isVolume(keyCode) && CameraKeys.handles() && CameraKeys.handler?.invoke(keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP, event.repeatCount == 0) == true ||
            super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean = isVolume(keyCode) && CameraKeys.handles() || super.onKeyUp(keyCode, event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // "Camera Dev" (debug) is a different app from the release APK the updater offers, so it never checks
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (savedInstanceState == null && !debug) Updater.check(this, manual = false)
        setContent { ClaudeBorneTheme { Root() } }
    }
}
