package com.claudeborne.camera.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * The user's choices, as Compose state saved at once (same idea as my-gallerymanager). Call [init] once at start-up.
 * Each value is declared with `by Pref(name, default)`; reading it in a composable updates the screen when it changes.
 */
object Settings {
    private lateinit var prefs: SharedPreferences
    private val all = mutableListOf<Pref<*>>()

    private class Pref<T : Any>(val key: String, val default: T) : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(default)

        init { all += this }

        @Suppress("UNCHECKED_CAST")
        fun load() {
            state.value = when (default) {
                is Boolean -> prefs.getBoolean(key, default)
                is Int -> prefs.getInt(key, default)
                else -> prefs.getString(key, default as String) ?: default
            } as T
        }

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            prefs.edit().apply {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    else -> putString(key, value as String)
                }
            }.apply()
        }
    }

    // General
    var shutterSound by Pref("shutterSound", true)
    var mirrorSelfie by Pref("mirrorSelfie", true)
    var locationTags by Pref("locationTags", false) // the stock default is on; here it is off until the owner turns it on and allows the permission
    var ultraHdr by Pref("ultraHdr", true)
    var heif by Pref("heif", false)
    var volumeAction by Pref("volumeAction", 1) // 0 volume, 1 shutter, 2 zoom (the stock default is Shutter)
    var photoAutoRotate by Pref("photoAutoRotate", false) // false = "Follow system": the viewfinder is portrait, so photos are upright for that

    // Photo
    var holdForBurst by Pref("holdForBurst", true) // touch and hold the shutter for a burst
    var tapToCapture by Pref("tapToCapture", false) // a tap on the viewfinder takes the photo instead of focusing
    var extra28 by Pref("extra28", true) // more focal lengths, reached by tapping the active zoom button again
    var extra35 by Pref("extra35", true)
    var extra243 by Pref("extra243", true)
    var defaultWide by Pref("defaultWide", 0) // the "1x" button: 0 = 24 mm, 1 = 28 mm, 2 = 35 mm

    // Video
    var hevc by Pref("hevc", true) // High efficiency video; 10-bit HDR always uses it
    var bitrateHigh by Pref("bitrateHigh", true) // Bitrate: High quality (the stock default) or Standard
    var adaptiveFps by Pref("adaptiveFps", 2) // 0 off, 1 for 30 fps videos, 2 for 30 and 60 fps videos (the stock default)
    var lockLens by Pref("lockLens", false)
    var lockWb by Pref("lockWb", false)
    var ultraSteady by Pref("ultraSteady", false) // Android's preview stabilisation (crops the picture a little)
    var videoHdr by Pref("videoHdr", 0) // 0 off, 1 HLG10, 2 HDR10
    var videoRes by Pref("videoRes", 3) // index into Res (3 = 4K)
    var videoFps by Pref("videoFps", 30)
    var sloRes by Pref("sloRes", 1) // 1080p
    var sloFps by Pref("sloFps", 240)
    var lapseSpeed by Pref("lapseSpeed", 30)

    var lastPhotoUri by Pref("lastPhotoUri", "")
    var lastIsVideo by Pref("lastIsVideo", false)

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        all.forEach { it.load() }
    }

    fun rememberLast(uri: String, video: Boolean) { lastPhotoUri = uri; lastIsVideo = video }

    fun rememberLastPhoto(uri: String) = rememberLast(uri, false)
}
