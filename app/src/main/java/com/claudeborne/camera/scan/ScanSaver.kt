package com.claudeborne.camera.scan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException

/** Saves scanned pages as one PDF in `Documents/Scans` (JPEG pages go to `DCIM/Camera` through the photo saver). */
object ScanSaver {
    private const val PDF_EDGE = 2200 // about 190 dpi on A4: sharp text, a file that is not huge

    /** [progress] is called with 0..1 after each page. Blocks: call it off the main thread. */
    fun savePdf(context: Context, pages: List<ByteArray>, timeMs: Long, progress: (Float) -> Unit): Uri {
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { i, jpeg ->
                val bitmap = decode(jpeg) ?: throw IOException("could not read page ${i + 1}")
                val (pw, ph) = DocRules.pdfPage(bitmap.width, bitmap.height)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                page.canvas.drawBitmap(bitmap, null, Rect(0, 0, pw, ph), Paint(Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page)
                bitmap.recycle()
                progress((i + 1f) / pages.size)
            }
            val resolver = context.contentResolver
            val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(files, ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, DocRules.pdfName(timeMs))
                put(MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/Scans")
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }) ?: throw IOException("could not create the file")
            try {
                resolver.openOutputStream(uri)?.use { pdf.writeTo(it) } ?: throw IOException("could not open the file")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) } // no half file left behind
                throw e
            }
            return uri
        } finally {
            pdf.close()
        }
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= PDF_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
