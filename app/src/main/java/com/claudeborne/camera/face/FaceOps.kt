package com.claudeborne.camera.face

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/** A point in "frame height" units: x runs 0..aspect across the upright frame, y runs 0..1 upwards (so circles stay round). */
data class V(val x: Float, val y: Float) {
    operator fun plus(o: V) = V(x + o.x, y + o.y)
    operator fun minus(o: V) = V(x - o.x, y - o.y)
    operator fun times(k: Float) = V(x * k, y * k)
    fun length() = hypot(x, y)
    fun dot(o: V) = x * o.x + y * o.y
    fun unit(): V { val l = length(); return if (l < 1e-6f) V(0f, 0f) else V(x / l, y / l) }
}

/** The mesh points of one face that the retouch needs, in frame height units, plus the aspect (width / height) of the frame they came from. */
class Face(val pts: Map<Int, V>, val aspect: Float) {
    private fun p(i: Int) = pts.getValue(i)
    private fun pair(a: Int, b: Int) = listOf(p(a), p(b))

    val top = p(TOP)
    val chin = p(CHIN)
    val height = (top - chin).length()
    val centre = (top + chin) * 0.5f
    val up = (top - chin).unit()
    /** The direction across the face, and the turn (radians) from the frame's x axis to it. */
    val side = V(up.y, -up.x)
    val angle = atan2(side.y, side.x)
    val eyes = listOf(avg(33, 133, 159, 145), avg(362, 263, 386, 374))
    val edges = pair(234, 454)
    val width = (p(234) - p(454)).length()
    val jaw = pair(172, 397)
    val cheek = pair(132, 361)
    val bone = pair(116, 345)
    val temple = pair(21, 251)
    val alar = pair(129, 358)

    private fun avg(vararg ids: Int): V { var s = V(0f, 0f); ids.forEach { s += p(it) }; return s * (1f / ids.size) }

    /** The unit vector from [point] towards the line down the middle of the face. */
    fun inward(point: V): V {
        val foot = centre + up * (point - centre).dot(up)
        return (foot - point).unit()
    }

    companion object {
        const val TOP = 10
        const val CHIN = 152
        /** Every mesh index the geometry above needs. */
        val NEEDED = intArrayOf(TOP, CHIN, 33, 133, 159, 145, 362, 263, 386, 374, 234, 454, 172, 397, 132, 361, 116, 345, 21, 251, 129, 358)

        /** From mesh points given in image pixels (x right, y down) of a [w] x [h] upright frame; null when a needed point is missing or the face is degenerate. */
        fun fromPixels(points: Map<Int, Pair<Float, Float>>, w: Int, h: Int): Face? {
            if (w <= 0 || h <= 0 || NEEDED.any { it !in points }) return null
            val map = HashMap<Int, V>()
            for (i in NEEDED) points.getValue(i).let { (x, y) -> map[i] = V(x / h, 1f - y / h) }
            val face = Face(map, w.toFloat() / h)
            return if (face.height < 0.02f || face.width < 0.01f) null else face
        }
    }
}

/** What the shader gets: up to [MAX_OPS] local warps (see Effects.WARP), up to [MAX_FACES] skin ovals, and the skin amounts. */
class Packed(
    val count: Int, val opA: FloatArray, val opB: FloatArray,
    val faces: Int, val skinA: FloatArray, val skinB: FloatArray,
    val aspect: Float, val smooth: Float, val tone: Float, val radius: Float,
) {
    companion object {
        const val MAX_OPS = 36
        const val MAX_FACES = 2
        fun empty() = Packed(0, FloatArray(MAX_OPS * 4), FloatArray(MAX_OPS * 4), 0, FloatArray(MAX_FACES * 4), FloatArray(MAX_FACES * 4), 1f, 0f, 0f, 0.01f)
    }
}

/**
 * Turns faces and slider values into shader data. Every warp is "content near c within radius r moves by v and is scaled by s along
 * a direction turned by angle": the shader reads the source at p - f * (v + R s R^-1 (p - c)), f falling smoothly from 1 at c to 0 at r.
 * All sizes are fractions of the face height, so a slider means the same on a close face and a far one.
 */
object RetouchOps {
    fun pack(faces: List<Face>, r: Retouch, frameAspect: Float): Packed {
        val use = faces.sortedByDescending { it.height }.take(Packed.MAX_FACES)
        if (use.isEmpty() || !r.active) return Packed.empty()
        val a = FloatArray(Packed.MAX_OPS * 4)
        val b = FloatArray(Packed.MAX_OPS * 4)
        var n = 0
        fun add(c: V, radius: Float, angle: Float, v: V = V(0f, 0f), sx: Float = 0f, sy: Float = 0f) {
            if (n >= Packed.MAX_OPS || radius <= 1e-4f) return
            a[n * 4] = c.x; a[n * 4 + 1] = c.y; a[n * 4 + 2] = radius; a[n * 4 + 3] = angle
            b[n * 4] = v.x; b[n * 4 + 1] = v.y; b[n * 4 + 2] = sx; b[n * 4 + 3] = sy
            n++
        }
        val skinA = FloatArray(Packed.MAX_FACES * 4)
        val skinB = FloatArray(Packed.MAX_FACES * 4)
        val frameCentre = V(frameAspect / 2f, 0.5f)
        val halfDiag = frameCentre.length()
        fun amount(v: Int) = v / 100f
        use.forEachIndexed { i, f ->
            val h = f.height
            skinA[i * 4] = f.centre.x; skinA[i * 4 + 1] = f.centre.y; skinA[i * 4 + 2] = f.width / 2f; skinA[i * 4 + 3] = h / 2f * 1.05f
            skinB[i * 4] = f.angle
            if (r.warps) {
                if (r.head > 0) add(f.centre, h * 1.5f, f.angle, sx = -0.07f * amount(r.head), sy = -0.07f * amount(r.head))
                if (r.eyes > 0) {
                    val eyeR = (f.eyes[0] - f.eyes[1]).length() * 0.32f
                    f.eyes.forEach { add(it, eyeR, 0f, sx = 0.4f * amount(r.eyes), sy = 0.4f * amount(r.eyes)) }
                }
                if (r.cheeks > 0) f.cheek.forEach { add(it, h * 0.32f, f.angle, f.inward(it) * (0.06f * h * amount(r.cheeks))) }
                if (r.width > 0) (f.edges + f.jaw).forEach { add(it, h * 0.30f, f.angle, f.inward(it) * (0.055f * h * amount(r.width))) }
                if (r.cheekbones > 0) f.bone.forEach { add(it, h * 0.22f, f.angle, f.inward(it) * (0.035f * h * amount(r.cheekbones))) }
                if (r.temples > 0) f.temple.forEach { add(it, h * 0.22f, f.angle, f.inward(it) * (-0.03f * h * amount(r.temples))) }
                if (r.hairline > 0) add(f.top + f.up * (0.02f * h), h * 0.30f, f.angle, f.up * (-0.06f * h * amount(r.hairline)))
                if (r.length > 0) add(f.chin, h * 0.40f, f.angle, f.up * (0.06f * h * amount(r.length)))
                if (r.nose > 0) {
                    val w = (f.alar[0] - f.alar[1]).length()
                    add((f.alar[0] + f.alar[1]) * 0.5f, w * 1.25f, f.angle, sx = -0.22f * amount(r.nose))
                }
            }
            if (r.correct > 0f) distortion(f, frameCentre, halfDiag, r.correct)?.let { add(it.c, it.radius, it.angle, sx = it.s) }
        }
        return Packed(
            n, a, b, use.size, skinA, skinB, frameAspect,
            smooth = amount(r.texture), tone = amount(r.tone), radius = use.first().height * 0.035f,
        )
    }

    internal data class Squeeze(val c: V, val radius: Float, val angle: Float, val s: Float)

    /** A rectilinear ultra wide shows a face near the edge stretched along the line from the frame's middle by 1/cos(theta); this squeezes it back. Null near the middle. */
    internal fun distortion(f: Face, frameCentre: V, halfDiag: Float, halfDiagDeg: Float): Squeeze? {
        val off = f.centre - frameCentre
        val rn = off.length() / max(halfDiag, 1e-4f)
        if (rn < 0.15f) return null
        val tanTheta = rn * tan(halfDiagDeg * PI.toFloat() / 180f)
        val cosTheta = 1f / sqrt(1f + tanTheta * tanTheta)
        return Squeeze(f.centre, f.height, atan2(off.y, off.x), 1f - 1f / cosTheta)
    }

    /** The half diagonal angle of the view for a zoom ratio on the ultra wide end of the rear camera; 0 once the zoom is on the main lens. Approximate, checked by eye on the phone. */
    fun edgeAngle(zoom: Float): Float = ((1f - zoom) / 0.33f * 55f).coerceIn(0f, 55f)

    /** The warp factor at distance [t] (0 at the centre to 1 at the edge), the same curve as the shader. */
    fun falloff(t: Float): Float = if (t >= 1f) 0f else (1f - t * t).let { it * it }
}
