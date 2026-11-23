package com.claudeborne.camera

import android.net.Uri

/** How the app was started: the stock camera intents, a launcher shortcut, or a plain launch. [serial] tells two launches apart. */
enum class LaunchKind { NORMAL, STILL, VIDEO, SECURE_STILL, IMAGE_CAPTURE, VIDEO_CAPTURE, SELFIE }

data class LaunchRequest(val kind: LaunchKind = LaunchKind.NORMAL, val output: Uri? = null, val serial: Int = 0) {
    /** Started from the lock screen: no settings, and only the pictures taken since this launch may be shown. */
    val secure get() = kind == LaunchKind.SECURE_STILL

    /** Another app asked for one photo or video and waits for the result. */
    val returnsResult get() = kind == LaunchKind.IMAGE_CAPTURE || kind == LaunchKind.VIDEO_CAPTURE

    val wantsVideo get() = kind == LaunchKind.VIDEO || kind == LaunchKind.VIDEO_CAPTURE
    val wantsPhoto get() = kind == LaunchKind.STILL || kind == LaunchKind.SECURE_STILL || kind == LaunchKind.IMAGE_CAPTURE || kind == LaunchKind.SELFIE
    val wantsFront get() = kind == LaunchKind.SELFIE

    companion object {
        const val SHORTCUT_EXTRA = "shortcut"
        const val SHORTCUT_SELFIE = "selfie"
        const val SHORTCUT_VIDEO = "video"

        /** The request for an intent's [action] and the launcher shortcut name it carries (or null). */
        fun of(action: String?, shortcut: String?, output: Uri? = null, serial: Int = 0): LaunchRequest {
            val kind = when (action) {
                "android.media.action.STILL_IMAGE_CAMERA" -> LaunchKind.STILL
                "android.media.action.STILL_IMAGE_CAMERA_SECURE" -> LaunchKind.SECURE_STILL
                "android.media.action.VIDEO_CAMERA" -> LaunchKind.VIDEO
                "android.media.action.IMAGE_CAPTURE", "android.media.action.IMAGE_CAPTURE_SECURE" -> LaunchKind.IMAGE_CAPTURE
                "android.media.action.VIDEO_CAPTURE" -> LaunchKind.VIDEO_CAPTURE
                else -> when (shortcut) {
                    SHORTCUT_SELFIE -> LaunchKind.SELFIE
                    SHORTCUT_VIDEO -> LaunchKind.VIDEO
                    else -> LaunchKind.NORMAL
                }
            }
            return LaunchRequest(kind, output, serial)
        }
    }
}
