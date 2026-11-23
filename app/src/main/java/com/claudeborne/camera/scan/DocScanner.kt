package com.claudeborne.camera.scan

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/** How a flattened page is cleaned up. */
enum class Enhance(val label: String) { COLOUR("COLOUR"), GREY("GREY"), BW("B&W") }

/** Document edge finding, flattening and clean-up with OpenCV. Everything here is slow: call it off the main thread. */
object DocScanner {
    /** False when OpenCV's native library could not be loaded; the Scan Docs mode is then not offered. */
    val available: Boolean by lazy { runCatching { OpenCVLoader.initLocal() }.getOrDefault(false) }

    /** The document's four corners in [src] (as fractions), or null when no plausible page edge is found. */
    fun findQuad(src: Bitmap): Quad? {
        if (!available) return null
        val k = 600f / max(src.width, src.height)
        val small = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(8), (src.height * k).toInt().coerceAtLeast(8), true) else src
        val rgba = Mat()
        val gray = Mat()
        val edges = Mat()
        try {
            Utils.bitmapToMat(small, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            var best: Quad? = null
            // two ways to see the page: its outline (Canny) and its brightness against the table (Otsu)
            for (way in 0..1) {
                if (way == 0) {
                    val median = median(gray)
                    Imgproc.Canny(gray, edges, 0.66 * median, 1.33 * median)
                    Imgproc.dilate(edges, edges, kernel)
                } else {
                    Imgproc.threshold(gray, edges, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                    Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
                }
                val q = biggestQuad(edges, small.width, small.height)
                if (q != null && (best == null || q.area > best.area)) best = q
            }
            return best
        } finally {
            rgba.release(); gray.release(); edges.release()
            if (small !== src) small.recycle()
        }
    }

    private fun median(gray: Mat): Double {
        val sample = Mat()
        Imgproc.resize(gray, sample, Size(64.0, 64.0))
        val bytes = ByteArray(64 * 64)
        sample.get(0, 0, bytes)
        sample.release()
        return bytes.map { it.toInt() and 0xFF }.sorted()[bytes.size / 2].toDouble().coerceIn(20.0, 235.0)
    }

    private fun biggestQuad(binary: Mat, w: Int, h: Int): Quad? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary.clone(), contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        var best: Quad? = null
        for (c in contours.sortedByDescending { Imgproc.contourArea(it) }.take(8)) {
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            for (eps in doubleArrayOf(0.02, 0.035, 0.05)) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, eps * peri, true)
                val pts = approx.toArray()
                approx.release()
                if (pts.size == 4) {
                    val q = DocRules.order(pts.map { Pt((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f)) })
                    if (DocRules.plausible(q)) { if (best == null || q.area > best.area) best = q; break }
                }
            }
            c2f.release()
        }
        contours.forEach { it.release() }
        return best
    }

    /** The page inside [quad], made flat and square on, at most [maxEdge] pixels on its long side. */
    fun flatten(src: Bitmap, quad: Quad, maxEdge: Int): Bitmap {
        val (ow, oh) = DocRules.outputSize(quad, src.width, src.height, maxEdge)
        val from = Mat()
        val to = Mat()
        Utils.bitmapToMat(src, from)
        val sp = MatOfPoint2f(*quad.points.map { Point(it.x * src.width.toDouble(), it.y * src.height.toDouble()) }.toTypedArray())
        val dp = MatOfPoint2f(Point(0.0, 0.0), Point(ow - 1.0, 0.0), Point(ow - 1.0, oh - 1.0), Point(0.0, oh - 1.0))
        val m = Imgproc.getPerspectiveTransform(sp, dp)
        Imgproc.warpPerspective(from, to, m, Size(ow.toDouble(), oh.toDouble()), Imgproc.INTER_CUBIC)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(to, out)
        from.release(); to.release(); sp.release(); dp.release(); m.release()
        return out
    }

    /** Evens out the light on the page (shadows, a tinted lamp) so the paper turns white; GREY drops the colour, B&W makes it ink on white. */
    fun enhance(src: Bitmap, how: Enhance): Bitmap {
        val rgba = Mat()
        val rgb = Mat()
        val out = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            if (how == Enhance.BW) {
                val gray = Mat()
                Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
                Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
                val block = (max(src.width, src.height) / 40).let { if (it % 2 == 0) it + 1 else it }.coerceIn(21, 71)
                Imgproc.adaptiveThreshold(gray, gray, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 14.0)
                Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGBA)
                gray.release()
            } else {
                val base = Mat()
                if (how == Enhance.GREY) {
                    Imgproc.cvtColor(rgb, base, Imgproc.COLOR_RGB2GRAY)
                    Imgproc.cvtColor(base, base, Imgproc.COLOR_GRAY2RGB)
                } else {
                    rgb.copyTo(base)
                }
                val background = background(base)
                val flat = Mat()
                Core.divide(base, background, flat, 255.0)
                Imgproc.cvtColor(flat, out, Imgproc.COLOR_RGB2RGBA)
                base.release(); background.release(); flat.release()
            }
            val bitmap = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, bitmap)
            return bitmap
        } finally {
            rgba.release(); rgb.release(); out.release()
        }
    }

    /** The light of the page without its writing: shrink, grow the bright parts over the ink, blur, scale back up. */
    private fun background(img: Mat): Mat {
        val small = Mat()
        val big = Mat()
        Imgproc.resize(img, small, Size(), 0.125, 0.125, Imgproc.INTER_AREA)
        Imgproc.dilate(small, small, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(11.0, 11.0)))
        Imgproc.GaussianBlur(small, small, Size(9.0, 9.0), 0.0)
        Imgproc.resize(small, big, img.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        return big
    }
}
