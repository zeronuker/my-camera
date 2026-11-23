package com.claudeborne.camera.look


/** The blur band of Tilt-shift: [centre] is the height of the sharp band's middle (0 bottom, 1 top), [half] its half width, [angle] its turn in degrees. */
data class Tilt(val centre: Float = 0.5f, val half: Float = 0.12f, val angle: Float = 0f)

/**
 * Everything the GL pipeline can do to a picture beyond showing it: the chosen filter, Soft light and the Master style controls,
 * Underwater colour correction, and the Tilt-shift band. The same [Look] is used for the live picture, a video and a saved photo, so
 * all three match. Style controls are whole numbers from -10 to 10 (sharpness 0 to 10), 0 meaning "as the camera made it".
 */
data class Look(
    /** Index into [Filters.all]; 0 is Original. */
    val filter: Int = 0,
    /** Soft light position 0..9 (None, two notches, Weak, two notches, Medium, two notches, Strong). */
    val soft: Int = 0,
    val tone: Int = 0, val sat: Int = 0, val warm: Int = 0, val tint: Int = 0, val sharp: Int = 0,
    val vignette: Boolean = false,
    val underwater: Boolean = false,
    val tilt: Tilt? = null,
) {
    val isIdentity get() = this == NONE

    /** A scene's tone preset added on top (Stage, Silhouette, Fireworks). */
    fun withScene(scene: Scene): Look = copy(
        tone = (tone + scene.tone).coerceIn(-10, 10), sat = (sat + scene.sat).coerceIn(-10, 10), warm = (warm + scene.warm).coerceIn(-10, 10),
    )

    companion object {
        const val SOFT_MAX = 9
        val NONE = Look()
    }
}

/** A scene: an exposure shift and a tone preset ([ev] stops, the others in style steps). */
data class Scene(val name: String, val ev: Float, val tone: Int, val sat: Int, val warm: Int) {
    companion object {
        val all = listOf(
            Scene("None", 0f, 0, 0, 0),
            Scene("Stage", -1.3f, 3, 2, 0), // bright lights on a dark stage: expose for the lights, keep the dark
            Scene("Silhouette", -1.7f, 5, 1, 3), // expose for the sky; the subject falls dark against a warm glow
            Scene("Fireworks", -2.3f, 4, 4, 0), // dark sky, bright bursts
        )
    }
}

/** A Master styles page: a named set of the style controls. */
data class StylePreset(val name: String, val tone: Int, val sat: Int, val warm: Int, val tint: Int, val sharp: Int, val soft: Int) {
    companion object {
        val all = listOf(
            StylePreset("Standard", 0, 0, 0, 0, 0, 0),
            StylePreset("Vivid", 2, 4, 0, 0, 2, 0),
            StylePreset("Soft", -2, -2, 1, 0, 0, 3),
            StylePreset("Warm", 1, 1, 4, 1, 0, 0),
            StylePreset("Cool", 1, 0, -4, -1, 0, 0),
            StylePreset("Mono", 3, -10, 0, 0, 2, 0),
        )
    }
}

/**
 * A filter: a small colour grade of our own, baked into a 16 x 16 x 16 look-up table for the GPU. Values are multipliers
 * (1 = unchanged) except [lift] and [fade] (0 = unchanged) and the tints (added at the dark / bright end).
 */
class FilterDef(
    val name: String, val note: String,
    val sat: Float = 1f, val contrast: Float = 1f, val bright: Float = 0f,
    val r: Float = 1f, val g: Float = 1f, val b: Float = 1f,
    val lift: Float = 0f, val fade: Float = 0f,
    val shadow: Triple<Float, Float, Float> = Triple(0f, 0f, 0f), val high: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
    val mono: Boolean = false,
) {
    /** The grade for one colour (all values 0..1). */
    fun grade(ri: Float, gi: Float, bi: Float): FloatArray {
        var x = ri * r
        var y = gi * g
        var z = bi * b
        val gain = 1f + bright
        x *= gain; y *= gain; z *= gain
        var l = 0.299f * x + 0.587f * y + 0.114f * z
        if (mono) { x = l; y = l; z = l } else {
            x = l + (x - l) * sat; y = l + (y - l) * sat; z = l + (z - l) * sat
        }
        x = (x - 0.5f) * contrast + 0.5f; y = (y - 0.5f) * contrast + 0.5f; z = (z - 0.5f) * contrast + 0.5f
        l = (0.299f * x + 0.587f * y + 0.114f * z).coerceIn(0f, 1f)
        val dark = (1f - l) * (1f - l)
        val light = l * l
        x += shadow.first * dark + high.first * light
        y += shadow.second * dark + high.second * light
        z += shadow.third * dark + high.third * light
        // lifted blacks and a faded top: the film look
        x = lift + x * (1f - lift - fade * 0.5f) + fade * 0.25f
        y = lift + y * (1f - lift - fade * 0.5f) + fade * 0.25f
        z = lift + z * (1f - lift - fade * 0.5f) + fade * 0.25f
        return floatArrayOf(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), z.coerceIn(0f, 1f))
    }
}

/** Original and our own looks, with names of our own (never a film or phone brand). */
object Filters {
    val all: List<FilterDef> = listOf(
        FilterDef("Original", "The picture as the camera made it."),
        FilterDef("Amber", "Warm and a little faded, like an old print.", sat = 1.1f, contrast = 1.08f, r = 1.07f, b = 0.92f, lift = 0.02f, high = Triple(0.03f, 0.01f, -0.03f)),
        FilterDef("Sage", "Muted greens and soft shadows.", sat = 0.85f, g = 1.05f, r = 0.98f, lift = 0.03f, fade = 0.04f),
        FilterDef("Dusk", "Teal shadows and orange highlights.", sat = 1.05f, contrast = 1.1f, shadow = Triple(-0.02f, 0.03f, 0.06f), high = Triple(0.06f, 0.02f, -0.04f)),
        FilterDef("Frost", "Cool, bright and clean.", sat = 0.9f, bright = 0.05f, r = 0.95f, b = 1.1f),
        FilterDef("Flash cool", "Hard direct flash on a cold night.", sat = 1.05f, contrast = 1.22f, bright = 0.08f, r = 0.96f, b = 1.08f),
        FilterDef("Flash warm", "Hard direct flash with a warm cast.", sat = 1.05f, contrast = 1.22f, bright = 0.08f, r = 1.08f, b = 0.92f),
        FilterDef("Mono", "Black and white with gentle contrast.", contrast = 1.05f, mono = true),
        FilterDef("Noir", "Black and white, deep and dramatic.", contrast = 1.35f, bright = -0.05f, mono = true),
        FilterDef("Fade", "Washed out and light.", sat = 0.8f, contrast = 0.92f, lift = 0.08f, fade = 0.1f),
        FilterDef("Vivid", "Rich, saturated colour.", sat = 1.45f, contrast = 1.1f),
        FilterDef("Pop", "Punchy colour and contrast.", sat = 1.25f, contrast = 1.2f, bright = 0.03f),
        FilterDef("Food", "Warm and appetising.", sat = 1.3f, contrast = 1.1f, bright = 0.04f, r = 1.06f, g = 1.02f, b = 0.92f),
        FilterDef("Lagoon", "Aqua shadows, peach skin.", sat = 1.1f, shadow = Triple(-0.03f, 0.04f, 0.05f), high = Triple(0.05f, 0.02f, 0f)),
        FilterDef("Matte", "Flat blacks and calm colour.", sat = 0.9f, contrast = 0.9f, lift = 0.05f, fade = 0.08f),
    )

    const val SIZE = 16

    /** The 16 x 16 x 16 table as a 256 x 16 strip of RGBA bytes: x = blue * 16 + red, y = green. */
    fun lut(index: Int): ByteArray {
        val def = all[index.coerceIn(0, all.lastIndex)]
        val out = ByteArray(SIZE * SIZE * SIZE * 4)
        for (g in 0 until SIZE) for (b in 0 until SIZE) for (r in 0 until SIZE) {
            val c = def.grade(r / (SIZE - 1f), g / (SIZE - 1f), b / (SIZE - 1f))
            val at = (g * SIZE * SIZE + b * SIZE + r) * 4
            out[at] = (c[0] * 255f + 0.5f).toInt().toByte()
            out[at + 1] = (c[1] * 255f + 0.5f).toInt().toByte()
            out[at + 2] = (c[2] * 255f + 0.5f).toInt().toByte()
            out[at + 3] = 255.toByte()
        }
        return out
    }
}

/** Soft light: the 10 positions and what they mean in the shader. */
object SoftLight {
    /** 0 (None) to 1 (Strong). */
    fun strength(position: Int): Float = position.coerceIn(0, Look.SOFT_MAX) / Look.SOFT_MAX.toFloat()

    /** The name the stock app shows for a position; the notches in between show only a number. */
    fun label(position: Int): String = when (position.coerceIn(0, Look.SOFT_MAX)) {
        0 -> "None"; 3 -> "Weak"; 6 -> "Medium"; 9 -> "Strong"
        else -> "${position.coerceIn(0, Look.SOFT_MAX)}"
    }
}

/** The Tilt-shift band keeps inside the picture and a usable size. */
object TiltRules {
    fun clamp(t: Tilt): Tilt = Tilt(t.centre.coerceIn(0.05f, 0.95f), t.half.coerceIn(0.04f, 0.4f), ((t.angle % 180f) + 180f) % 180f)

    fun resize(t: Tilt, scale: Float): Tilt = clamp(t.copy(half = t.half * scale))
}

