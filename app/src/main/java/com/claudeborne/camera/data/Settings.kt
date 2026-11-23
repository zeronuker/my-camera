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

    var qrScan by Pref("qrScan", true) // look for QR codes in Photo mode
    var recommendText by Pref("recommendText", true) // suggest Text mode when text fills the frame

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

    // Master (Pro): shooting choices that stay between launches
    var masterFormat by Pref("masterFormat", 0) // 0 JPG, 1 RAW, 2 RAW+JPG (index into MasterFormat)
    var proOn by Pref("proOn", false) // Auto / Pro switch of Master and Pro video
    var showHistogram by Pref("showHistogram", false)
    var faceMetering by Pref("faceMetering", false)
    var peaking by Pref("peaking", false)
    var zebra by Pref("zebra", false)
    var levelLine by Pref("levelLine", false)
    var logProfile by Pref("logProfile", false) // Pro video: the flat log-style look in the file
    var lutPreview by Pref("lutPreview", true) // Pro video: show the normal look on screen while the file is flat
    var shutterAngle by Pref("shutterAngle", false) // Pro video: show the shutter as an angle instead of 1/x

    // Looks (Phase 5)
    var filter by Pref("filter", 0) // index into Filters.all; 0 = Original
    var softLight by Pref("softLight", 0) // 0..9
    var styleTone by Pref("styleTone", 0)
    var styleSat by Pref("styleSat", 0)
    var styleWarm by Pref("styleWarm", 0)
    var styleTint by Pref("styleTint", 0)
    var styleSharp by Pref("styleSharp", 0)
    var styleVignette by Pref("styleVignette", false) // stock default is On (the phone's own lens look, which an app cannot switch); here it is a gentle corner darkening and starts Off so a plain Master photo stays untouched
    var stylePage by Pref("stylePage", 0)
    var scene by Pref("scene", 0) // index into Scene.all, used in Video

    // Composition assistance
    var gridKind by Pref("gridKind", 0) // 0 none, 1 grid, 2 diagonal lines, 3 golden spiral
    var composeLevel by Pref("composeLevel", false)

    // Watermark
    var wmOn by Pref("wmOn", false)
    var wmTarget by Pref("wmTarget", 2) // 0 photos, 1 videos, 2 photos and videos
    var wmShotOn by Pref("wmShotOn", true)
    var wmLocation by Pref("wmLocation", false)
    var wmDate by Pref("wmDate", false)
    var wmText by Pref("wmText", "")
    var wmSize by Pref("wmSize", 1) // 0 small, 1 medium, 2 large
    var wmPosition by Pref("wmPosition", 0) // 0 bottom, 1 bottom left, 2 bottom right, 3 top

    var lastPhotoUri by Pref("lastPhotoUri", "")
    var lastIsVideo by Pref("lastIsVideo", false)

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        all.forEach { it.load() }
    }

    fun rememberLast(uri: String, video: Boolean) { lastPhotoUri = uri; lastIsVideo = video }

    fun rememberLastPhoto(uri: String) = rememberLast(uri, false)
}
