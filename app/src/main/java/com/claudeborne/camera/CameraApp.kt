package com.claudeborne.camera

import android.app.Application
import com.claudeborne.camera.data.CrashLog

class CameraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
