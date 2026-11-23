package com.claudeborne.camera.gl

/** Counts how bright the pixels of a small RGBA picture are, in 64 bins from black to white. */
object Histogram {
    const val BINS = 64

    fun of(rgba: ByteArray): IntArray {
        val bins = IntArray(BINS)
        var i = 0
        while (i + 3 < rgba.size) {
            val r = rgba[i].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            val luma = (299 * r + 587 * g + 114 * b) / 1000 // 0..255
            bins[(luma * (BINS - 1) + 127) / 255]++
            i += 4
        }
        return bins
    }
}
