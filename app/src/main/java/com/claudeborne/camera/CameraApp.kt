package com.claudeborne.camera

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.claudeborne.camera.data.CrashLog

class CameraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        com.claudeborne.camera.data.Settings.init(this)
        runCatching { launcherShortcuts() }
    }

    /** Long-press the app icon: Selfie and Video. (Added in code so they point at this build's own package, release or "Camera Dev".) */
    private fun launcherShortcuts() {
        val manager = getSystemService(ShortcutManager::class.java) ?: return
        fun shortcut(id: String, label: String, icon: Int, which: String) = ShortcutInfo.Builder(this, id)
            .setShortLabel(label)
            .setIcon(Icon.createWithResource(this, icon))
            .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).putExtra(LaunchRequest.SHORTCUT_EXTRA, which))
            .build()
        manager.dynamicShortcuts = listOf(
            shortcut("selfie", "Selfie", R.drawable.ic_shortcut_selfie, LaunchRequest.SHORTCUT_SELFIE),
            shortcut("video", "Video", R.drawable.ic_shortcut_video, LaunchRequest.SHORTCUT_VIDEO),
        )
    }
}
