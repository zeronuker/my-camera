package com.claudeborne.camera.camera

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** What Master mode saves: a JPEG, a DNG, or both. */
enum class MasterFormat(val label: String) { JPG("JPG"), RAW("RAW"), RAW_JPG("RAW+JPG");

    val wantsRaw get() = this != JPG
    val wantsJpeg get() = this != RAW

    companion object {
        fun of(index: Int) = entries.getOrElse(index) { JPG }
    }
}

/** A white balance choice of the Pro controls: the camera's own, one of its lighting presets, or a colour temperature. */
sealed interface Wb {
    data object Auto : Wb
    data class Preset(val name: String, val awbMode: Int) : Wb
    data class Kelvin(val k: Int) : Wb
}

/** The manual choices of Master and Pro video. [manualExposure] means ISO and shutter are both set by hand (see [ProRules.seedExposure]). */
data class Pro(
    val manualExposure: Boolean = false,
    val iso: Int = 100,
    val exposureNs: Long = 8_000_000L,
    val wb: Wb = Wb.Auto,
    /** Focus distance in diopters (0 is far away, larger is closer); null leaves the focus to the camera. */
    val focusDiopters: Float? = null,
    val faceMetering: Boolean = false,
) {
    val isManual get() = manualExposure || wb != Wb.Auto || focusDiopters != null
}

/** What the open camera allows for manual control, from its characteristics. A range of 0 means "not supported": the control is hidden. */
data class ProCaps(
    val isoMin: Int = 0, val isoMax: Int = 0, val exposureMinNs: Long = 0, val exposureMaxNs: Long = 0,
    val minFocusDiopters: Float = 0f, val awbModes: Set<Int> = emptySet(), val manualPost: Boolean = false,
    val rawSize: Pair<Int, Int>? = null, val faceDetect: Boolean = false, val colorCal: ProRules.ColorCal? = null,
) {
    val exposure get() = isoMax > 0 && exposureMaxNs > 0
    val focus get() = minFocusDiopters > 0f
    val raw get() = rawSize != null
    /** A colour temperature dial needs the manual colour controls and the camera's calibration. */
    val kelvin get() = manualPost && colorCal != null
}

/** Pure maths for the Pro controls: the lists the dials step through, their labels, and the white balance gains. */
object ProRules {
    private val ISO = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600)
    private val SHUTTER_DENOMINATORS = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3)
    private val SHUTTER_SECONDS = listOf(0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0)

    /** Longest exposure used in the live picture: a longer one would freeze the viewfinder. The photo still gets the real value. */
    const val PREVIEW_MAX_NS = 250_000_000L

    /** ISO values from the usual list that the camera supports, with its own limits as the ends. */
    fun isoStops(min: Int, max: Int): List<Int> {
        if (max <= 0 || min <= 0 || min > max) return emptyList()
        return (listOf(min) + ISO.filter { it in (min + 1) until max } + max).distinct()
    }

    /** Shutter times in ns, shortest first, from the usual list that the camera supports. */
    fun shutterStops(minNs: Long, maxNs: Long): List<Long> {
        if (minNs <= 0 || maxNs < minNs) return emptyList()
        val all = SHUTTER_DENOMINATORS.map { 1_000_000_000L / it } + SHUTTER_SECONDS.map { (it * 1e9).toLong() }
        return all.filter { it in minNs..maxNs }.distinct().ifEmpty { listOf(minNs) }
    }

    /** `1/250` or `2"` (seconds). */
    fun shutterLabel(ns: Long): String {
        val seconds = ns / 1e9
        return if (seconds < 0.55) "1/${(1.0 / seconds).roundToInt()}" else "${"%.1f".format(java.util.Locale.US, seconds).removeSuffix(".0")}\""
    }

    /** The shutter as a film shutter angle (360 degrees is exposing for the whole frame time), as video people read it. */
    fun shutterAngle(ns: Long, fps: Int): Int = (360.0 * ns / 1e9 * fps).roundToInt().coerceAtLeast(1)

    /** Index of the entry nearest to [value] (in stops, so 1/30 is nearer to 1/60 than 1/15 would be to it). */
    fun nearest(list: List<Long>, value: Long): Int = list.indices.minByOrNull { abs(ln(list[it].toDouble() / value)) } ?: 0

    fun nearest(list: List<Int>, value: Int): Int = list.indices.minByOrNull { abs(ln(list[it].toDouble() / value)) } ?: 0

    /**
     * Pro exposure is "both by hand": the first time the user sets ISO or shutter, both start at what the camera was using, so the
     * picture does not jump. [liveIso] and [liveNs] come from the last preview frame.
     */
    fun seedExposure(pro: Pro, caps: ProCaps, liveIso: Int, liveNs: Long): Pro {
        if (pro.manualExposure || !caps.exposure) return pro
        val iso = isoStops(caps.isoMin, caps.isoMax).let { it[nearest(it, liveIso.coerceAtLeast(1))] }
        val ns = shutterStops(caps.exposureMinNs, caps.exposureMaxNs).let { it[nearest(it, liveNs.coerceAtLeast(1))] }
        return pro.copy(manualExposure = true, iso = iso, exposureNs = ns)
    }

    /** The exposure time to ask for: video cannot expose longer than one frame, the live picture no longer than [PREVIEW_MAX_NS]. */
    fun usableExposure(ns: Long, videoFps: Int?, forStill: Boolean): Long = when {
        videoFps != null -> minOf(ns, 1_000_000_000L / videoFps)
        forStill -> ns
        else -> minOf(ns, PREVIEW_MAX_NS)
    }

    /** Frame duration to ask for so a long exposure is allowed (never shorter than the usual 1/30 s). */
    fun frameDuration(exposureNs: Long): Long = maxOf(exposureNs, 33_333_333L)

    val KELVIN_MIN = 2500
    val KELVIN_MAX = 9000
    private const val KELVIN_STEP = 250

    val kelvins: List<Int> = (KELVIN_MIN..KELVIN_MAX step KELVIN_STEP).toList()

    /** The colour calibration a camera ships for raw files: two matrices (XYZ to camera) for two light sources. Row-major, 9 numbers each. */
    class ColorCal(val matrix1: List<Float>, val matrix2: List<Float>, val illuminant1: Int, val illuminant2: Int)

    /** Colour temperature of an Android `SENSOR_REFERENCE_ILLUMINANT` code. */
    fun illuminantKelvin(code: Int): Int = when (code) {
        17, 3 -> 2856
        18 -> 4874
        19 -> 6774
        20, 1, 9 -> 5503
        21 -> 6504
        22 -> 7504
        23 -> 5003
        10 -> 6500
        11 -> 7500
        else -> 5000
    }

    /** CIE XYZ (Y = 1) of a black body at [k] kelvin, from Kim's approximation of the Planckian locus. */
    fun whiteXyz(k: Int): Triple<Double, Double, Double> {
        val t = k.coerceIn(1667, 25000).toDouble()
        val x = if (t <= 4000) -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
        else -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        val y = when {
            t <= 2222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return Triple(x / y, 1.0, (1 - x - y) / y)
    }

    /**
     * Gains for the red and blue channel (green is 1) that make light of colour temperature [k] come out neutral on this sensor:
     * the camera's own calibration matrices are blended for [k], and the camera's reading of a white at [k] is turned around.
     */
    fun kelvinGains(k: Int, cal: ColorCal): Pair<Float, Float> {
        val t1 = illuminantKelvin(cal.illuminant1).toDouble()
        val t2 = illuminantKelvin(cal.illuminant2).toDouble()
        val w = if (t1 == t2) 0.0 else ((1.0 / k - 1.0 / t2) / (1.0 / t1 - 1.0 / t2)).coerceIn(0.0, 1.0)
        val m = DoubleArray(9) { w * cal.matrix1[it] + (1 - w) * cal.matrix2[it] }
        val (x, y, z) = whiteXyz(k)
        val r = m[0] * x + m[1] * y + m[2] * z
        val g = m[3] * x + m[4] * y + m[5] * z
        val b = m[6] * x + m[7] * y + m[8] * z
        return (g / r).toFloat().coerceIn(0.2f, 8f) to (g / b).toFloat().coerceIn(0.2f, 8f)
    }

    /** The lighting presets the camera lists, in the order a person expects. */
    fun wbPresets(awbModes: Set<Int>): List<Wb.Preset> = listOf(
        "Incandescent" to 2, "Fluorescent" to 3, "Daylight" to 5, "Cloudy" to 6,
    ).filter { it.second in awbModes }.map { Wb.Preset(it.first, it.second) }

    /** Every white balance the dial offers, auto first. */
    fun wbChoices(awbModes: Set<Int>, kelvin: Boolean): List<Wb> =
        listOf<Wb>(Wb.Auto) + wbPresets(awbModes) + (if (kelvin) kelvins.map { Wb.Kelvin(it) } else emptyList())

    fun wbLabel(wb: Wb) = when (wb) {
        Wb.Auto -> "AUTO"
        is Wb.Preset -> wb.name.uppercase()
        is Wb.Kelvin -> "${wb.k}K"
    }

    /** Focus dial positions (diopters), far to near: 0 is infinity, the last is the closest the lens can focus. */
    fun focusSteps(minFocusDiopters: Float, count: Int = 41): List<Float> =
        if (minFocusDiopters <= 0f) emptyList() else List(count) { minFocusDiopters * it / (count - 1) }

    fun focusLabel(diopters: Float): String = when {
        diopters < 0.05f -> "INF"
        else -> {
            val cm = 100f / diopters
            if (cm >= 100f) "%.1f m".format(java.util.Locale.US, cm / 100f) else "${cm.roundToInt()} cm"
        }
    }

    /** The face rectangle to meter on from the camera's list of faces: the largest one, or null when there are none. */
    fun largestFace(faces: List<IntArray>): IntArray? = faces.maxByOrNull { (it[2] - it[0]).toLong() * (it[3] - it[1]) }
}
