package com.claudeborne.camera.scan

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.hypot

/** What an open palm looks like in the 21 hand landmarks MediaPipe reports. Pure maths, unit tested. */
object PalmRules {
    private val FINGERS = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18) // fingertip and the middle joint of the same finger

    private fun dist(a: Pt, b: Pt) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    /** [l] are the landmarks in pixels (not fractions, so the picture's shape does not skew the distances). All four fingers are stretched out and the thumb is away from the hand. */
    fun isOpenPalm(l: List<Pt>): Boolean {
        if (l.size < 21) return false
        val wrist = l[0]
        val size = dist(wrist, l[9]) // wrist to the base of the middle finger
        if (size < 8.0) return false // too small or too far to be sure
        val fingersOut = FINGERS.all { (tip, joint) -> dist(l[tip], wrist) > 1.15 * dist(l[joint], wrist) }
        val thumbOut = dist(l[4], l[5]) > 0.6 * size
        return fingersOut && thumbOut
    }
}

/** MediaPipe's hand landmarker (bundled model `hand_landmarker.task`), used for the "show palm" timer. */
class HandScanner(context: Context) {
    companion object {
        /** False where MediaPipe has no native library for this phone (the emulator, which is x86_64): the palm control is then hidden. */
        val supported: Boolean by lazy { runCatching { System.loadLibrary("mediapipe_tasks_vision_jni"); true }.getOrDefault(false) }
    }

    private val app = context.applicationContext
    private val landmarker by lazy {
        HandLandmarker.createFromOptions(
            app,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.6f)
                .build(),
        )
    }

    /** True when an open palm is in [bitmap]. False on any problem (model missing, no hand). Blocks: call it off the main thread. */
    fun seesOpenPalm(bitmap: Bitmap): Boolean = runCatching {
        val hand = landmarker.detect(BitmapImageBuilder(bitmap).build()).landmarks().firstOrNull() ?: return@runCatching false
        PalmRules.isOpenPalm(hand.map { Pt(it.x() * bitmap.width, it.y() * bitmap.height) })
    }.getOrDefault(false)

    fun close() { runCatching { landmarker.close() } }
}
