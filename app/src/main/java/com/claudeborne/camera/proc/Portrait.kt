package com.claudeborne.camera.proc

import android.graphics.Bitmap
import android.graphics.Matrix
import com.claudeborne.camera.scan.DocScanner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Pure numbers of the portrait blur, unit tested. */
object PortraitRules {
    val F_NUMBERS = listOf(1.4f, 2.0f, 2.8f, 4.0f)

    fun label(index: Int) = "f/" + F_NUMBERS[index.coerceIn(0, F_NUMBERS.lastIndex)].toString().removeSuffix(".0").let { if (it.contains('.')) it else "$it.0" }

    /** Blur strength (the Gaussian's sigma) for an f-number: a wider aperture (smaller number) blurs the background more. [longEdge] is the picture's long side in pixels. */
    fun sigma(index: Int, longEdge: Int): Float = 0.016f * F_NUMBERS[0] / F_NUMBERS[index.coerceIn(0, F_NUMBERS.lastIndex)] * longEdge

    /** A person fills so much of the frame that the background blur would look wrong: ask for more distance. */
    fun tooClose(cover: Float) = cover > 0.55f
}

/**
 * Portrait blur with our own cut-out (ML Kit's bundled selfie segmentation finds the person): used on the front camera and wherever the
 * phone's BOKEH extension is missing or refused. The background is blurred evenly (there is no depth map), the person stays sharp, the edge is feathered.
 */
object Portrait {
    private val segmenter by lazy {
        Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).enableRawSizeMask().build())
    }

    private class MaskData(val values: FloatArray, val w: Int, val h: Int)

    /** The person mask (0 background .. 1 person) of [bitmap]; blocks, call it off the main thread. */
    private fun segment(bitmap: Bitmap): MaskData? = runCatching {
        val m = Tasks.await(segmenter.process(InputImage.fromBitmap(bitmap, 0)))
        val buf = m.buffer
        MaskData(FloatArray(m.width * m.height).also { buf.rewind(); buf.asFloatBuffer().get(it) }, m.width, m.height)
    }.getOrNull()

    /** The share of [bitmap] that is a person (0..1), or 0 when none is found. */
    fun cover(bitmap: Bitmap): Float {
        val m = segment(bitmap) ?: return 0f
        return m.values.count { it > 0.5f } / m.values.size.toFloat()
    }

    /** The portrait as an upright JPEG, or null when no person was found or OpenCV is missing. [mirror] flips it like a selfie preview. */
    fun apply(jpeg: ByteArray, rotation: Int, mirror: Boolean, fIndex: Int): ByteArray? {
        if (!DocScanner.available) return null
        var photo = Frames.decode(jpeg, rotation, 2560) ?: return null
        if (mirror) photo = Bitmap.createBitmap(photo, 0, 0, photo.width, photo.height, Matrix().apply { preScale(-1f, 1f) }, false)
        val small = Bitmap.createScaledBitmap(photo, 512.coerceAtMost(photo.width), (512.coerceAtMost(photo.width) * photo.height / photo.width), true)
        val mask = segment(small)
        small.recycle()
        if (mask == null || mask.values.count { it > 0.5f } < mask.values.size / 50) { photo.recycle(); return null } // under 2 % person: nothing to keep sharp
        val rgb = Frames.rgb(photo)
        val longEdge = maxOf(photo.width, photo.height)
        photo.recycle()
        val alpha = Mat(mask.h, mask.w, CvType.CV_32F).also { it.put(0, 0, mask.values) }
        val blurred = Mat()
        val a = Mat()
        val a3 = Mat()
        val sharpF = Mat()
        val blurF = Mat()
        val out = Mat()
        try {
            Imgproc.resize(alpha, a, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.GaussianBlur(a, a, Size(0.0, 0.0), longEdge * 0.002) // a soft edge, no cut-out halo
            // blur at a quarter of the size and scale back up: the same look for a fraction of the time
            val quarter = Mat()
            Imgproc.resize(rgb, quarter, Size(), 0.25, 0.25, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(quarter, quarter, Size(0.0, 0.0), PortraitRules.sigma(fIndex, longEdge) / 4.0)
            Imgproc.resize(quarter, blurred, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            quarter.release()
            rgb.convertTo(sharpF, CvType.CV_32FC3)
            blurred.convertTo(blurF, CvType.CV_32FC3)
            Core.merge(listOf(a, a, a), a3)
            Core.subtract(sharpF, blurF, sharpF)
            Core.multiply(sharpF, a3, sharpF)
            Core.add(blurF, sharpF, blurF)
            blurF.convertTo(out, CvType.CV_8UC3)
            val bitmap = Frames.bitmap(out)
            return Frames.jpeg(bitmap).also { bitmap.recycle() }
        } finally {
            rgb.release(); alpha.release(); blurred.release(); a.release(); a3.release(); sharpF.release(); blurF.release(); out.release()
        }
    }
}
