package com.claudeborne.camera.proc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.claudeborne.camera.scan.DocScanner
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import java.io.ByteArrayOutputStream

/** Decoding and encoding helpers shared by the multi-frame features. */
internal object Frames {
    /** A JPEG turned upright by [rotation] degrees and shrunk (if needed) so its long edge is at most [maxEdge] pixels. */
    fun decode(jpeg: ByteArray, rotation: Int, maxEdge: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return if (rotation % 360 == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true).also { raw.recycle() }
    }

    fun jpeg(bitmap: Bitmap, quality: Int = 95): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** An 8-bit three-channel (RGB) matrix of [bitmap]. */
    fun rgb(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        return rgb
    }

    fun bitmap(rgb8: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(rgb8, rgba, Imgproc.COLOR_RGB2RGBA)
        val out = Bitmap.createBitmap(rgb8.cols(), rgb8.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, out)
        rgba.release()
        return out
    }
}

/** Auto HDR: several exposures of one scene fused into one picture that keeps both the bright and the dark parts (exposure fusion, after aligning). */
object HdrMerge {
    /** The fused picture as an upright JPEG, or null when OpenCV is missing or fewer than two frames can be read. */
    fun merge(jpegs: List<ByteArray>, rotation: Int): ByteArray? {
        if (!DocScanner.available || jpegs.size < 2) return null
        val bitmaps = jpegs.mapNotNull { Frames.decode(it, rotation, 3072) }
        if (bitmaps.size < 2) return null
        val mats = bitmaps.map { Frames.rgb(it) }
        bitmaps.forEach { it.recycle() }
        val aligned = ArrayList<Mat>()
        val fused = Mat()
        val out8 = Mat()
        try {
            val align = Photo.createAlignMTB()
            aligned.add(mats[0])
            val gray0 = Mat().also { Imgproc.cvtColor(mats[0], it, Imgproc.COLOR_RGB2GRAY) }
            for (i in 1 until mats.size) { // line the other frames up with the first (a hand moves a little between frames)
                val gray = Mat().also { Imgproc.cvtColor(mats[i], it, Imgproc.COLOR_RGB2GRAY) }
                val moved = Mat()
                align.shiftMat(mats[i], moved, align.calculateShift(gray0, gray))
                gray.release()
                aligned.add(moved)
            }
            gray0.release()
            Photo.createMergeMertens().process(aligned, fused) // float, 0..1
            Core.multiply(fused, Scalar(255.0, 255.0, 255.0), fused)
            fused.convertTo(out8, CvType.CV_8UC3)
            val bitmap = Frames.bitmap(out8)
            return Frames.jpeg(bitmap).also { bitmap.recycle() }
        } finally {
            mats.forEach { it.release() }; aligned.drop(1).forEach { it.release() }; fused.release(); out8.release()
        }
    }
}

/**
 * Long exposure: frames added one by one into a running total, so only one frame is in memory at a time. [trails] keeps the brightest value
 * of each pixel (light trails, stars); otherwise the frames are averaged (silky water, smoothed crowds). The phone must not move.
 */
class FrameStack(private val trails: Boolean) {
    private var total: Mat? = null
    var count = 0
        private set

    @Synchronized
    fun add(jpeg: ByteArray, rotation: Int) {
        val bitmap = Frames.decode(jpeg, rotation, 3072) ?: return
        val rgb = Frames.rgb(bitmap)
        bitmap.recycle()
        val f = Mat()
        rgb.convertTo(f, CvType.CV_32FC3)
        rgb.release()
        val sum = total
        if (sum == null) total = f
        else {
            if (f.size() != sum.size()) { f.release(); return }
            if (trails) Core.max(sum, f, sum) else Core.add(sum, f, sum)
            f.release()
        }
        count++
    }

    @Synchronized
    fun result(): ByteArray? {
        val sum = total ?: return null
        if (!trails) Core.multiply(sum, Scalar.all(1.0 / count), sum)
        val out8 = Mat()
        sum.convertTo(out8, CvType.CV_8UC3)
        val bitmap = Frames.bitmap(out8)
        out8.release(); sum.release(); total = null
        return Frames.jpeg(bitmap).also { bitmap.recycle() }
    }
}

/**
 * Panorama of a sweep, joined by our own code (OpenCV's Android package has no stitcher): every frame is bent onto a cylinder (a turning
 * phone is a camera turning on the spot), neighbours are matched with ORB features to find how far each moved, and the frames are laid
 * on one canvas with soft edges so the seams do not show.
 */
object Panorama {
    sealed interface Result {
        class Done(val jpeg: ByteArray) : Result
        class Failed(val reason: String) : Result
    }

    private const val FOCAL = 0.667f // focal length in long-side widths: the 24 mm main lens sees 74 degrees along the long side

    /** Where each output pixel of the cylinder reads its colour from in the flat picture. */
    private fun cylinderMaps(w: Int, h: Int, f: Float): Pair<Mat, Mat> {
        val mx = FloatArray(w * h)
        val my = FloatArray(w * h)
        val cx = w / 2f
        val cy = h / 2f
        for (v in 0 until h) for (u in 0 until w) {
            val theta = (u - cx) / f
            mx[v * w + u] = (f * Math.tan(theta.toDouble())).toFloat() + cx
            my[v * w + u] = cy + (v - cy) / Math.cos(theta.toDouble()).toFloat()
        }
        val x = Mat(h, w, CvType.CV_32F).also { it.put(0, 0, mx) }
        val y = Mat(h, w, CvType.CV_32F).also { it.put(0, 0, my) }
        return x to y
    }

    private fun median(v: List<Double>) = v.sorted()[v.size / 2]

    fun stitch(jpegs: List<ByteArray>, rotation: Int): Result {
        if (!DocScanner.available || jpegs.size < 2) return Result.Failed("Need at least two pictures")
        val bitmaps = jpegs.mapNotNull { Frames.decode(it, rotation, 1280) }
        if (bitmaps.size < 2) return Result.Failed("Need at least two pictures")
        val w = bitmaps[0].width
        val h = bitmaps[0].height
        val (mapX, mapY) = cylinderMaps(w, h, FOCAL * maxOf(w, h))
        val frames = ArrayList<Mat>()
        val grays = ArrayList<Mat>()
        val keys = ArrayList<MatOfKeyPoint>()
        val descs = ArrayList<Mat>()
        val canvas = Mat()
        val weights = Mat()
        try {
            val orb = ORB.create(1500)
            for (b in bitmaps) {
                if (b.width != w || b.height != h) { b.recycle(); continue }
                val flat = Frames.rgb(b)
                b.recycle()
                val bent = Mat()
                Imgproc.remap(flat, bent, mapX, mapY, Imgproc.INTER_LINEAR)
                flat.release()
                val gray = Mat()
                Imgproc.cvtColor(bent, gray, Imgproc.COLOR_RGB2GRAY)
                val k = MatOfKeyPoint()
                val d = Mat()
                orb.detectAndCompute(gray, Mat(), k, d)
                frames.add(bent); grays.add(gray); keys.add(k); descs.add(d)
            }
            if (frames.size < 2) return Result.Failed("Need at least two pictures")
            // how far each frame sits from the first one
            val xs = DoubleArray(frames.size)
            val ys = DoubleArray(frames.size)
            val matcher = BFMatcher.create(Core.NORM_HAMMING, true)
            for (i in 1 until frames.size) {
                val m = MatOfDMatch()
                if (descs[i].empty() || descs[i - 1].empty()) return Result.Failed("Not enough detail to match the pictures")
                matcher.match(descs[i], descs[i - 1], m)
                val best = m.toArray().sortedBy { it.distance }.take(150)
                if (best.size < 12) return Result.Failed("The pictures do not overlap enough. Turn slower.")
                val cur = keys[i].toArray()
                val prev = keys[i - 1].toArray()
                val dx = best.map { prev[it.trainIdx].pt.x - cur[it.queryIdx].pt.x }
                val dy = best.map { prev[it.trainIdx].pt.y - cur[it.queryIdx].pt.y }
                xs[i] = xs[i - 1] + median(dx)
                ys[i] = ys[i - 1] + median(dy)
            }
            val minX = xs.min(); val minY = ys.min()
            val width = (xs.max() - minX).toInt() + w
            if (width > 12000) return Result.Failed("The panorama is too wide. Sweep a little shorter.")
            // keep only the rows every frame covers, so there are no black corners
            val top = (ys.max() - minY).toInt()
            val bottom = (ys.min() - minY).toInt() + h
            if (bottom - top < h / 3) return Result.Failed("The phone moved up and down too much. Keep it level.")
            val fullH = (ys.max() - minY).toInt() + h
            canvas.create(fullH, width, CvType.CV_32FC3); canvas.setTo(Scalar.all(0.0))
            weights.create(fullH, width, CvType.CV_32FC1); weights.setTo(Scalar.all(0.0))
            val ramp = Mat(h, w, CvType.CV_32FC1)
            val rv = FloatArray(w * h) { i -> val x = i % w; val y = i / w; (1f - Math.abs(2f * x / w - 1f)).coerceAtLeast(0.02f) * (1f - Math.abs(2f * y / h - 1f)).coerceAtLeast(0.02f) }
            ramp.put(0, 0, rv)
            val ramp3 = Mat()
            Core.merge(listOf(ramp, ramp, ramp), ramp3)
            for (i in frames.indices) {
                val x0 = (xs[i] - minX).toInt()
                val y0 = (ys[i] - minY).toInt()
                val f32 = Mat()
                frames[i].convertTo(f32, CvType.CV_32FC3)
                Core.multiply(f32, ramp3, f32)
                val roi = canvas.submat(y0, y0 + h, x0, x0 + w)
                Core.add(roi, f32, roi)
                val wroi = weights.submat(y0, y0 + h, x0, x0 + w)
                Core.add(wroi, ramp, wroi)
                f32.release()
            }
            ramp.release(); ramp3.release()
            val w3 = Mat()
            Core.merge(listOf(weights, weights, weights), w3)
            Core.divide(canvas, w3, canvas)
            w3.release()
            val out8 = Mat()
            canvas.submat(top, bottom, 0, width).convertTo(out8, CvType.CV_8UC3)
            val bitmap = Frames.bitmap(out8)
            out8.release()
            return Result.Done(Frames.jpeg(bitmap).also { bitmap.recycle() })
        } catch (e: Throwable) {
            return Result.Failed("Could not join the pictures")
        } finally {
            frames.forEach { it.release() }; grays.forEach { it.release() }; keys.forEach { it.release() }; descs.forEach { it.release() }
            mapX.release(); mapY.release(); canvas.release(); weights.release()
        }
    }
}
