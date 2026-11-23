package com.claudeborne.camera.face

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions

/**
 * Finds faces with ML Kit Face Mesh (bundled model, nothing leaves the phone) and keeps only the mesh points the retouch uses.
 * Blocks: call it off the main thread. Any failure means "no faces", never a crash.
 */
class FaceFinder {
    private val detector by lazy { FaceMeshDetection.getClient(FaceMeshDetectorOptions.Builder().setUseCase(FaceMeshDetectorOptions.FACE_MESH).build()) }

    /** The faces of an upright, unmirrored [bitmap], largest first. */
    fun find(bitmap: Bitmap): List<Face> = fake(bitmap.width, bitmap.height)?.let { listOf(it) } ?: try {
        val wanted = Face.NEEDED.toHashSet()
        Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0))).mapNotNull { mesh ->
            val points = HashMap<Int, Pair<Float, Float>>()
            for (p in mesh.allPoints) if (p.index in wanted) points[p.index] = p.position.x to p.position.y
            Face.fromPixels(points, bitmap.width, bitmap.height)
        }.sortedByDescending { it.height }
    } catch (_: Throwable) {
        emptyList()
    }

    fun close() { runCatching { detector.close() } }

    companion object {
        /**
         * Debug builds only (set by the scanner when the app is debuggable): a file holding "x y h" (the face's centre across and up the frame, and its
         * height, all as fractions) makes every look return one made-up face instead of running the detector. It lets the emulator, whose camera shows no
         * face, prove the warp, the smoothing and the saved photo.
         */
        @Volatile var debugFile: java.io.File? = null

        private fun fake(w: Int, h: Int): Face? {
            val f = debugFile?.takeIf { it.isFile } ?: return null
            val v = runCatching { f.readText().trim().split(Regex("[ \t]+")).map { it.toFloat() } }.getOrNull()?.takeIf { it.size == 3 } ?: return null
            val pixels = HashMap<Int, Pair<Float, Float>>()
            for ((i, p) in TEMPLATE) pixels[i] = (v[0] + p.first * v[2]) * h to (1f - (v[1] + p.second * v[2])) * h
            return Face.fromPixels(pixels, w, h)
        }

        /** A symmetric face in units of its own height, centred on (0,0), y up. */
        private val TEMPLATE: Map<Int, Pair<Float, Float>> = mapOf(
            10 to (0f to 0.5f), 152 to (0f to -0.5f),
            33 to (-0.2f to 0.1f), 133 to (-0.1f to 0.1f), 159 to (-0.15f to 0.125f), 145 to (-0.15f to 0.075f),
            362 to (0.1f to 0.1f), 263 to (0.2f to 0.1f), 386 to (0.15f to 0.125f), 374 to (0.15f to 0.075f),
            234 to (-0.42f to 0.0f), 454 to (0.42f to 0.0f), 172 to (-0.3f to -0.35f), 397 to (0.3f to -0.35f),
            132 to (-0.38f to -0.15f), 361 to (0.38f to -0.15f), 116 to (-0.3f to 0f), 345 to (0.3f to 0f),
            21 to (-0.35f to 0.3f), 251 to (0.35f to 0.3f), 129 to (-0.075f to -0.2f), 358 to (0.075f to -0.2f),
        )
    }
}
