package com.claudeborne.camera.scan

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Sends a picture to Google Lens through the Google app's share entry point. The button is shown only where that entry point exists. */
object LensShare {
    private val LENS = ComponentName("com.google.android.googlequicksearchbox", "com.google.android.apps.search.lens.LensShareEntryPointActivity")

    fun available(context: Context): Boolean = runCatching { context.packageManager.getActivityInfo(LENS, 0); true }.getOrDefault(false)

    fun intent(picture: Uri): Intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").setComponent(LENS)
        .putExtra(Intent.EXTRA_STREAM, picture).apply { clipData = ClipData.newRawUri("", picture) }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}
