package com.claudeborne.camera.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.Executors

/**
 * Writes photos to `DCIM/Camera` through MediaStore. It runs on its own thread and holds no screen, so a photo still being
 * saved when the app is closed is finished anyway. The file stays "pending" (invisible to other apps) until it is complete.
 */
object PhotoSaver {
    /** How many photos are being saved right now (the screen shows a moving bar while it is above zero). */
    var saving by mutableIntStateOf(0)
        private set

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Below this much free space the shutter refuses and says so instead of failing half way. */
    private const val MIN_FREE_BYTES = 60L * 1024 * 1024

    fun hasRoom(): Boolean = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes >= MIN_FREE_BYTES
    } catch (_: Exception) {
        true // cannot tell: try; a failed write is reported on its own
    }

    /** [exifOrientation] is written into the file only for mirrored selfies (everything else the camera tags itself). */
    fun save(context: Context, bytes: ByteArray, format: StillFormat, exifOrientation: Int?, timeMs: Long, done: (Result<Uri>) -> Unit) {
        val app = context.applicationContext
        saving++
        io.execute {
            val result = runCatching { write(app, bytes, format, exifOrientation, timeMs) }
            main.post {
                saving--
                done(result)
            }
        }
    }

    private fun write(context: Context, bytes: ByteArray, format: StillFormat, exifOrientation: Int?, timeMs: Long): Uri {
        val data = if (exifOrientation == null) bytes else withOrientation(context, bytes, exifOrientation)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, PhotoRules.fileName(timeMs, format.extension))
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.Images.Media.DATE_TAKEN, timeMs)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("could not create the file")
        try {
            resolver.openOutputStream(uri)?.use { it.write(data) } ?: throw java.io.IOException("could not open the file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) } // no half photo left behind
            throw e
        }
        return uri
    }

    private fun withOrientation(context: Context, bytes: ByteArray, orientation: Int): ByteArray {
        val file = File(context.cacheDir, "share").apply { mkdirs() }.resolve("mirror.jpg")
        try {
            file.writeBytes(bytes)
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    /** Small picture for the last-shot button; null when the photo is gone or cannot be read. */
    fun thumbnail(context: Context, uri: Uri): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
}
