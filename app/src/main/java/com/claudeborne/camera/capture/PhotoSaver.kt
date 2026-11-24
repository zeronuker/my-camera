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
import android.graphics.Canvas
import com.claudeborne.camera.gl.PhotoLook
import com.claudeborne.camera.look.Look
import java.io.File
import java.util.concurrent.Executors

/** What to burn into a saved photo: a look (filter, Soft light, style...) and/or an [overlay] such as the watermark. */
class PhotoEdit(val look: Look, val overlay: ((Canvas, Int, Int) -> Unit)?)

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

    /**
     * [exifOrientation] is written into the file only for mirrored selfies (everything else the camera tags itself). [edit] is a look
     * and/or a watermark to burn into the picture: the file is then a plain JPEG (an Ultra HDR gain map or HEIF cannot survive that).
     */
    fun save(context: Context, bytes: ByteArray, format: StillFormat, exifOrientation: Int?, squareCrop: Boolean, timeMs: Long, edit: PhotoEdit? = null, motion: ByteArray? = null, nameSuffix: String = "", done: (Result<Uri>) -> Unit) {
        val app = context.applicationContext
        saving++
        io.execute {
            val result = runCatching { write(app, bytes, format, exifOrientation, squareCrop, timeMs, edit, motion, nameSuffix) }
            main.post {
                saving--
                done(result)
            }
        }
    }

    private fun write(context: Context, bytes: ByteArray, wanted: StillFormat, exifOrientation: Int?, squareCrop: Boolean, timeMs: Long, edit: PhotoEdit?, motion: ByteArray?, nameSuffix: String = ""): Uri {
        // the 1:1 shape is cut out of the 4:3 photo; that makes a plain JPEG (an Ultra HDR file loses its gain map when re-encoded)
        val raw = wanted == StillFormat.DNG // a DNG is written as it is: no crop, the turn is already inside it
        // a look or watermark: the same effect shader as the viewfinder, on the finished photo; null (any problem) keeps the camera's own file
        val edited = if (!raw && edit != null) edited(context, bytes, exifOrientation, squareCrop, edit) else null
        val format = if ((squareCrop || edited != null) && !raw) StillFormat.JPEG else wanted
        val still = when {
            raw -> bytes
            edited != null -> edited
            squareCrop -> squared(context, bytes, exifOrientation)
            exifOrientation != null -> withOrientation(context, bytes, exifOrientation)
            else -> bytes
        }
        // a Live photo: the clip goes after the finished picture (anything that re-encodes the JPEG has already happened)
        val data = if (motion != null && format == StillFormat.JPEG) MotionPhoto.build(still, motion, 1_500_000) else still
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, PhotoRules.fileName(timeMs, format.extension, suffix = nameSuffix))
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

    /** The photo with its look and watermark burned in, turned upright, with the camera's tags (time, place, make) copied over; null when it could not be done. */
    private fun edited(context: Context, bytes: ByteArray, exifOrientation: Int?, squareCrop: Boolean, edit: PhotoEdit): ByteArray? {
        val tag = exifOrientation ?: runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val processed = PhotoLook.process(bytes, tag, edit.look, squareCrop, edit.overlay) ?: return null
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val source = dir.resolve("edit-in.jpg")
        val target = dir.resolve("edit-out.jpg")
        return try {
            source.writeBytes(bytes)
            target.writeBytes(processed)
            val from = ExifInterface(source.path)
            ExifInterface(target.path).apply {
                copyTags(from, this)
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString()) // already turned upright
                saveAttributes()
            }
            target.readBytes()
        } catch (_: Exception) {
            processed // the picture is fine without its tags
        } finally {
            source.delete()
            target.delete()
        }
    }

    private fun copyTags(from: ExifInterface, to: ExifInterface) {
        val kept = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
        )
        for (tag in kept) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
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
                copyTags(from, this)
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
