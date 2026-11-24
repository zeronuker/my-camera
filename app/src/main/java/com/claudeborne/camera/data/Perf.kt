package com.claudeborne.camera.data

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Timing notes for tuning, written to the log under the tag `Perf` (read with `adb logcat -s Perf`). They hold numbers only, never pictures
 * or places. Times from the emulator mean little; the phone is what counts.
 */
object Perf {
    private const val TAG = "Perf"
    private var armedAt = 0L
    private var kind = ""
    private var waiting = false
    private var shutterAt = 0L

    /** True once any camera frame has been drawn in this run: the splash over the viewfinder fades away on it. */
    var liveFrame by mutableStateOf(false)
        private set

    /** A launch (first start, or a new intent into the running app) begins: the next camera frame ends the measurement. */
    fun launched(kind: String) {
        this.kind = kind
        armedAt = SystemClock.elapsedRealtime()
        waiting = true
    }

    /** The first live camera frame reached the screen. */
    fun firstFrame() {
        if (!liveFrame) liveFrame = true
        if (!waiting) return
        waiting = false
        val now = SystemClock.elapsedRealtime()
        val sinceProcess = now - Process.getStartElapsedRealtime()
        Log.i(TAG, "first frame ($kind): ${now - armedAt} ms after the launch intent, $sinceProcess ms after the process started")
    }

    fun shutterPressed() { shutterAt = SystemClock.elapsedRealtime() }

    /** The camera handed over the photo (shutter lag: press to picture in hand). */
    fun photoCaptured() {
        if (shutterAt != 0L) Log.i(TAG, "shutter lag: ${SystemClock.elapsedRealtime() - shutterAt} ms from the press to the photo")
    }

    /** A photo was written; [withLook] means it also went through the offscreen look pass. */
    fun saved(startedAt: Long, withLook: Boolean, queued: Int) {
        val rt = Runtime.getRuntime()
        Log.i(
            TAG,
            "saved in ${SystemClock.elapsedRealtime() - startedAt} ms (look=$withLook, still waiting=$queued), java heap ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB, native heap ${Debug.getNativeHeapAllocatedSize() shr 20} MB",
        )
    }
}
