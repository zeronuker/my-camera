package com.claudeborne.camera.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    fun hasRoom(minBytes: Long = MIN_FREE_BYTES): Boolean = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes >= minBytes
    } catch (_: Exception) {
        true // cannot tell: try; a failed write is reported on its own
    }

    /** [exifOrientation] is written into the file only for mirrored selfies (everything else the camera tags itself). */
    fun save(context: Context, bytes: ByteArray, format: StillFormat, exifOrientation: Int?, squareCrop: Boolean, timeMs: Long, done: (Result<Uri>) -> Unit) {
        val app = context.applicationContext
        saving++
        io.execute {
            val result = runCatching { write(app, bytes, format, exifOrientation, squareCrop, timeMs) }
            main.post {
                saving--
                done(result)
            }
        }
    }

    private fun write(context: Context, bytes: ByteArray, wanted: StillFormat, exifOrientation: Int?, squareCrop: Boolean, timeMs: Long): Uri {
        // the 1:1 shape is cut out of the 4:3 photo; that makes a plain JPEG (an Ultra HDR file loses its gain map when re-encoded)
        val raw = wanted == StillFormat.DNG // a DNG is written as it is: no crop, the turn is already inside it
        val format = if (squareCrop && !raw) StillFormat.JPEG else wanted
        val data = when {
            raw -> bytes
            squareCrop -> squared(context, bytes, exifOrientation)
            exifOrientation != null -> withOrientation(context, bytes, exifOrientation)
            else -> bytes
        }
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

    /** The centre square of the photo, re-encoded; the tags that matter (turn, time, place, camera) are copied over. */
    private fun squared(context: Context, bytes: ByteArray, orientation: Int?): ByteArray {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val source = dir.resolve("square-in.jpg")
        val target = dir.resolve("square-out.jpg")
        try {
            source.writeBytes(bytes)
            val picture = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw java.io.IOException("could not read the photo")
            val (left, top, side) = AspectMath.squareCrop(picture.width, picture.height)
            val cut = Bitmap.createBitmap(picture, left, top, side, side)
            target.outputStream().use { cut.compress(Bitmap.CompressFormat.JPEG, 97, it) }
            val from = ExifInterface(source.path)
            ExifInterface(target.path).apply {
                val kept = listOf(
                    ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP,
                    ExifInterface.TAG_GPS_PROCESSING_METHOD,
                )
                for (tag in kept) from.getAttribute(tag)?.let { setAttribute(tag, it) }
                setAttribute(ExifInterface.TAG_ORIENTATION, (orientation ?: from.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)).toString())
                saveAttributes()
            }
            return target.readBytes()
        } finally {
            source.delete()
            target.delete()
        }
    }

    /** Small picture for the last-shot button; null when the photo is gone or cannot be read. */
    fun thumbnail(context: Context, uri: Uri): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
}
