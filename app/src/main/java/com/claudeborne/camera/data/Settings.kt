package com.claudeborne.camera.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The user's choices, as Compose state saved at once (same pattern as my-gallerymanager). Call [init] once at start-up.
 * Part B only reads them; the screens that change them come with the settings pages in part D.
 */
object Settings {
    private lateinit var prefs: SharedPreferences

    var shutterSound by mutableStateOf(true)
    var mirrorSelfie by mutableStateOf(true)
    var locationTags by mutableStateOf(false) // off until the settings page can ask for the location permission
    var ultraHdr by mutableStateOf(true)
    var heif by mutableStateOf(false)
    var photoAutoRotate by mutableStateOf(false) // false = "Follow system": the viewfinder is portrait, so photos are upright for that
    var volumeAction by mutableIntStateOf(1) // 0 volume, 1 shutter, 2 zoom (the stock default is Shutter)
    var tapToCapture by mutableStateOf(false) // a tap on the viewfinder takes the photo instead of focusing
    var holdForBurst by mutableStateOf(true) // touch and hold the shutter for a burst
    var lastPhotoUri by mutableStateOf("")
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        shutterSound = prefs.getBoolean("shutterSound", shutterSound)
        mirrorSelfie = prefs.getBoolean("mirrorSelfie", mirrorSelfie)
        locationTags = prefs.getBoolean("locationTags", locationTags)
        ultraHdr = prefs.getBoolean("ultraHdr", ultraHdr)
        heif = prefs.getBoolean("heif", heif)
        photoAutoRotate = prefs.getBoolean("photoAutoRotate", photoAutoRotate)
        volumeAction = prefs.getInt("volumeAction", volumeAction)
        tapToCapture = prefs.getBoolean("tapToCapture", tapToCapture)
        holdForBurst = prefs.getBoolean("holdForBurst", holdForBurst)
        lastPhotoUri = prefs.getString("lastPhotoUri", "") ?: ""
    }

    fun rememberLastPhoto(uri: String) {
        lastPhotoUri = uri
        prefs.edit().putString("lastPhotoUri", uri).apply()
    }
}
