package com.claudeborne.camera.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What the watermark says and where it goes. [text] is already put together; empty means no watermark. */
class WatermarkSpec(val text: String, val size: Int, val position: Int)

/** The watermark of the Settings page: pure rules for its text and place, and the drawing of it. */
object Watermark {
    val SIZES = listOf("Small", "Medium", "Large")
    val POSITIONS = listOf("Bottom", "Bottom left", "Bottom right", "Top")
    val TARGETS = listOf("Photos", "Videos", "Photos & videos")

    /** "OnePlus 15" for this phone (its model number is CPH2747); other phones show their maker and model. */
    fun modelName(manufacturer: String, model: String): String {
        if (manufacturer.equals("OnePlus", ignoreCase = true) && model == "CPH2747") return "OnePlus 15"
        val maker = manufacturer.replaceFirstChar { it.uppercase() }
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
    }

    /** Whether the watermark goes on a photo or a video, from the "Add watermark to" choice. */
    fun appliesTo(target: Int, video: Boolean): Boolean = target == 2 || (target == 0 && !video) || (target == 1 && video)

    /** The lines joined into one: "Shot on OnePlus 15  ·  2026-11-23 15:42  ·  my text". */
    fun text(shotOn: Boolean, model: String, location: String?, timeMs: Long?, custom: String, zone: TimeZone = TimeZone.getDefault()): String {
        val parts = buildList {
            if (shotOn) add("Shot on $model")
            if (!location.isNullOrBlank()) add(location)
            if (timeMs != null) add(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = zone }.format(Date(timeMs)))
            if (custom.isNotBlank()) add(custom.trim())
        }
        return parts.joinToString("  ·  ")
    }

    /** "3.139° N, 101.687° E". */
    fun coordinates(lat: Double, lon: Double): String =
        "%.3f° %s, %.3f° %s".format(Locale.US, kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W")

    /** Text height in pixels for a picture whose shorter side is [shortSide]. */
    fun textSize(size: Int, shortSide: Int): Float = shortSide * when (size) { 0 -> 0.024f; 2 -> 0.04f; else -> 0.031f }

    /** The left and the baseline of the text, for a picture of [w] by [h] and a text [textW] wide. */
    fun place(position: Int, w: Int, h: Int, textW: Float, textSize: Float): Pair<Float, Float> {
        val margin = textSize * 1.4f
        return when (position) {
            1 -> margin to h - margin
            2 -> w - margin - textW to h - margin
            3 -> (w - textW) / 2f to margin + textSize
            else -> (w - textW) / 2f to h - margin
        }
    }

    /** Draws the text white with a soft dark shadow so it reads on any picture; a text too wide for the picture shrinks to fit. */
    fun draw(canvas: Canvas, w: Int, h: Int, spec: WatermarkSpec) {
        if (spec.text.isEmpty()) return
        var size = textSize(spec.size, minOf(w, h))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size
        }
        val limit = w * 0.92f
        val measured = paint.measureText(spec.text)
        if (measured > limit) { size *= limit / measured; paint.textSize = size }
        paint.setShadowLayer(size * 0.18f, 0f, size * 0.06f, Color.argb(160, 0, 0, 0))
        val width = paint.measureText(spec.text)
        val (x, y) = place(spec.position, w, h, width, size)
        canvas.drawText(spec.text, x, y, paint)
    }

    /** A transparent picture of [w] by [h] holding only the watermark, for the video encoder to lay over every frame. */
    fun overlay(w: Int, h: Int, spec: WatermarkSpec): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { draw(Canvas(it), w, h, spec) }
}
