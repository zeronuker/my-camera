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
        if (savedInstanceState == null) Updater.check(this, manual = false)
        setContent { ClaudeBorneTheme { Root() } }
    }
}
