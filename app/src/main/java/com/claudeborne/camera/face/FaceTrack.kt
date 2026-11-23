package com.claudeborne.camera.face

/**
 * Keeps faces steady between looks: each new face is matched with the nearest face of the last look and its points are moved only
 * part of the way, so the warp does not tremble. A face that is not near any earlier one starts afresh; a look that finds no face
 * is kept for a moment (a blink of the detector is not a lost face).
 */
class FaceTrack(private val keep: Int = 3, private val follow: Float = 0.6f) {
    private var last: List<Face> = emptyList()
    private var misses = 0

    fun update(found: List<Face>): List<Face> {
        if (found.isEmpty()) {
            if (++misses > keep) last = emptyList()
            return last
        }
        misses = 0
        last = found.map { f ->
            val before = last.minByOrNull { (it.centre - f.centre).length() }
            if (before == null || (before.centre - f.centre).length() > f.height * 0.5f) f else blend(before, f)
        }
        return last
    }

    fun reset() { last = emptyList(); misses = 0 }

    private fun blend(old: Face, new: Face): Face =
        Face(new.pts.mapValues { (k, v) -> old.pts[k]?.let { o -> o + (v - o) * follow } ?: v }, new.aspect)
}
