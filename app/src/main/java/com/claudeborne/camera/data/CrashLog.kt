package com.claudeborne.camera.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uncaught exceptions are written to `files/crash/` and kept for the Diagnostics screen. No crash service, nothing leaves the phone unless the owner shares it. */
object CrashLog {
    private const val KEEP = 10

    fun install(context: Context) {
        val dir = File(context.applicationContext.filesDir, "crash")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                dir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                File(dir, "crash-$stamp.txt").writeText("thread: ${thread.name}\ntime: ${Date()}\n\n${e.stackTraceToString()}")
                prune(dir.list()?.toList().orEmpty()).forEach { File(dir, it).delete() }
            } catch (_: Exception) { /* a failure while logging must not hide the real crash */ }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Newest first. */
    fun latest(context: Context): List<File> =
        File(context.filesDir, "crash").listFiles()?.sortedByDescending { it.name }.orEmpty()

    /** Names of the old log files to delete so only the newest [KEEP] remain (the names sort by time). */
    internal fun prune(names: List<String>, keep: Int = KEEP): List<String> = names.sortedDescending().drop(keep)
}
