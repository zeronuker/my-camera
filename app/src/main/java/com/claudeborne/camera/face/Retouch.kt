package com.claudeborne.camera.face

/**
 * The Retouch sliders, each 0 (off) to 100. They all push one way, as in the stock app: skin gets smoother and brighter, the eyes
 * bigger, the head, face, chin, hairline, nose and cheeks smaller or slimmer, the temples fuller.
 * [correct] is the half diagonal angle (degrees) of an ultra wide view when faces near its edges should be straightened (0 = off).
 */
data class Retouch(
    val texture: Int = 0, val tone: Int = 0, val cheeks: Int = 0, val head: Int = 0, val eyes: Int = 0, val width: Int = 0,
    val length: Int = 0, val hairline: Int = 0, val temples: Int = 0, val cheekbones: Int = 0, val nose: Int = 0,
    val correct: Float = 0f,
) {
    /** True when any slider is above zero (distortion correction counts separately). */
    val sliders get() = texture + tone + cheeks + head + eyes + width + length + hairline + temples + cheekbones + nose > 0
    val active get() = sliders || correct > 0f

    /** The size and shape sliders (everything except the two skin ones). */
    val warps get() = cheeks + head + eyes + width + length + hairline + temples + cheekbones + nose > 0

    companion object {
        val NONE = Retouch()
        val NATURAL = Retouch(texture = 30, tone = 20, head = 8, eyes = 15, width = 12, cheekbones = 8, nose = 8)
        val CLASSIC = Retouch(texture = 60, tone = 40, cheeks = 25, head = 15, eyes = 30, width = 28, length = 12, hairline = 12, temples = 12, cheekbones = 22, nose = 22)
    }
}

/** One slider of the panel: its name as the stock app shows it, and how to read and write it. */
enum class RetouchSlider(val title: String) {
    TEXTURE("Skin texture"), TONE("Skin tone"), CHEEKS("Cheeks"), HEAD("Head size"), EYES("Eye size"), WIDTH("Face width"),
    LENGTH("Face length"), HAIRLINE("Hairline"), TEMPLES("Temples"), CHEEKBONES("Cheekbones"), NOSE("Nose");

    fun get(r: Retouch): Int = when (this) {
        TEXTURE -> r.texture; TONE -> r.tone; CHEEKS -> r.cheeks; HEAD -> r.head; EYES -> r.eyes; WIDTH -> r.width
        LENGTH -> r.length; HAIRLINE -> r.hairline; TEMPLES -> r.temples; CHEEKBONES -> r.cheekbones; NOSE -> r.nose
    }

    fun set(r: Retouch, v: Int): Retouch {
        val x = v.coerceIn(0, 100)
        return when (this) {
            TEXTURE -> r.copy(texture = x); TONE -> r.copy(tone = x); CHEEKS -> r.copy(cheeks = x); HEAD -> r.copy(head = x)
            EYES -> r.copy(eyes = x); WIDTH -> r.copy(width = x); LENGTH -> r.copy(length = x); HAIRLINE -> r.copy(hairline = x)
            TEMPLES -> r.copy(temples = x); CHEEKBONES -> r.copy(cheekbones = x); NOSE -> r.copy(nose = x)
        }
    }
}

/** The Original / Natural / Classic buttons. */
enum class RetouchPreset(val title: String, val values: Retouch) {
    ORIGINAL("Original", Retouch.NONE), NATURAL("Natural", Retouch.NATURAL), CLASSIC("Classic", Retouch.CLASSIC);

    companion object {
        /** The preset whose sliders equal [r], or null when the sliders were changed by hand. */
        fun of(r: Retouch): RetouchPreset? = entries.firstOrNull { it.values == r.copy(correct = 0f) }
    }
}

/** Stored as one text so the choice survives a restart: the eleven sliders, comma separated. */
object RetouchStore {
    fun encode(r: Retouch): String = RetouchSlider.entries.joinToString(",") { it.get(r).toString() }

    fun decode(text: String): Retouch {
        val parts = text.split(',').mapNotNull { it.trim().toIntOrNull() }
        var r = Retouch.NONE
        if (parts.size == RetouchSlider.entries.size) RetouchSlider.entries.forEachIndexed { i, s -> r = s.set(r, parts[i]) }
        return r
    }
}
