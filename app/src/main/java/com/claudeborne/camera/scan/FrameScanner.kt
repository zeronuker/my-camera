package com.claudeborne.camera.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What one look at a live frame found: a QR code's text, and the lines of text with how many characters they hold. */
class FrameHit(val qr: String?, val lines: List<Frac>, val chars: Int, val quad: Quad? = null, val palm: Boolean = false, val cover: Float = 0f)

/**
 * QR and text finding with ML Kit (bundled models). Frames come from the GL pipeline as small RGBA copies; one is looked at
 * at a time on its own thread and frames that arrive meanwhile are dropped, so the viewfinder is never held up.
 */
class FrameScanner(context: android.content.Context) {
    private val hands by lazy { HandScanner(context) }
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val barcodes by lazy { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val chinese by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    /** [rgba] is a GL read-back (bottom row first). [done] runs on the scanner thread. Returns false when the last frame is still being looked at. */
    fun look(rgba: ByteArray, w: Int, h: Int, wantQr: Boolean, wantText: Boolean, wantDoc: Boolean = false, wantPalm: Boolean = false, wantCover: Boolean = false, done: (FrameHit) -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        worker.execute {
            try {
                val bitmap = upright(rgba, w, h)
                if (wantDoc) { // the page outline needs no ML Kit
                    val quad = runCatching { DocScanner.findQuad(bitmap) }.getOrNull()
                    bitmap.recycle()
                    done(FrameHit(null, emptyList(), 0, quad))
                    return@execute
                }
                if (wantCover) { // portrait: how much of the frame is a person
                    val cover = runCatching { com.claudeborne.camera.proc.Portrait.cover(bitmap) }.getOrDefault(0f)
                    bitmap.recycle()
                    done(FrameHit(null, emptyList(), 0, null, false, cover))
                    return@execute
                }
                val palm = wantPalm && hands.seesOpenPalm(bitmap)
                val image = InputImage.fromBitmap(bitmap, 0)
                val qr = if (wantQr) runCatching { Tasks.await(barcodes.process(image)).firstNotNullOfOrNull { it.rawValue } }.getOrNull() else null
                var lines = emptyList<Frac>()
                var chars = 0
                if (wantText && qr == null) runCatching {
                    val found = Tasks.await(latin.process(image)).textBlocks.flatMap { it.lines }
                    chars = found.sumOf { it.text.length }
                    lines = found.mapNotNull { l -> l.boundingBox?.let { Frac(it.left / w.toFloat(), it.top / h.toFloat(), it.right / w.toFloat(), it.bottom / h.toFloat()) } }
                }
                bitmap.recycle()
                done(FrameHit(qr, lines, chars, null, palm))
            } catch (_: Throwable) {
                done(FrameHit(null, emptyList(), 0))
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    /** The person mask of a GL read-back frame (see [com.claudeborne.camera.proc.Portrait.maskBytes]); frames that arrive while it works are dropped. [done] runs on the scanner thread. */
    fun mask(rgba: ByteArray, w: Int, h: Int, done: (ByteArray, Int, Int) -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        worker.execute {
            try {
                val bitmap = upright(rgba, w, h)
                val m = runCatching { com.claudeborne.camera.proc.Portrait.maskBytes(bitmap) }.getOrNull()
                bitmap.recycle()
                if (m != null) done(m.first, m.second, m.third)
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    /** Reads all the text of a photo (JPEG bytes turned by [rotation] degrees to be upright). Blocks: call it off the main thread. Empty when none. */
    fun readPhoto(jpeg: ByteArray, rotation: Int): String {
        val bitmap = decode(jpeg) ?: return ""
        return try {
            val image = InputImage.fromBitmap(bitmap, rotation)
            val a = runCatching { Tasks.await(latin.process(image)).text }.getOrDefault("")
            val b = runCatching { Tasks.await(chinese.process(image)).text }.getOrDefault("")
            TextRules.pick(a, b).trim()
        } finally {
            bitmap.recycle()
        }
    }

    fun close() {
        worker.shutdown()
        runCatching { barcodes.close(); latin.close(); chinese.close(); hands.close() }
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        var sample = 1
        while (o.outWidth / sample > 4200 || o.outHeight / sample > 4200) sample *= 2
        return android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun upright(rgba: ByteArray, w: Int, h: Int): Bitmap {
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        val flipped = Bitmap.createBitmap(raw, 0, 0, w, h, Matrix().apply { preScale(1f, -1f) }, false) // GL reads from the bottom row up
        raw.recycle()
        return flipped
    }
}
