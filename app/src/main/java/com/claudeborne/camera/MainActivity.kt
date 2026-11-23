package com.claudeborne.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.claudeborne.camera.data.Updater
import com.claudeborne.camera.ui.ClaudeBorneTheme
import com.claudeborne.camera.ui.Root

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // "Camera Dev" (debug) is a different app from the release APK the updater offers, so it never checks
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (savedInstanceState == null && !debug) Updater.check(this, manual = false)
        setContent { ClaudeBorneTheme { Root() } }
    }
}
