package com.claudeborne.camera.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLUtils
import com.claudeborne.camera.capture.AspectMath
import com.claudeborne.camera.look.Filters
import com.claudeborne.camera.look.Look
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** EXIF orientation tag as a turn (degrees, clockwise) and a left-right flip done after the turn. */
object ExifTurn {
    fun of(tag: Int): Pair<Int, Boolean> = when (tag) {
        2 -> 0 to true
        3 -> 180 to false
        4 -> 180 to true
        5 -> 90 to true
        6 -> 90 to false
        7 -> 270 to true
        8 -> 270 to false
        else -> 0 to false
    }
}

/**
 * Saved photos go through the same effect code as the live picture: the photo is decoded, turned upright, drawn once through the
 * shader on a hidden GL surface, optionally cut square and stamped (watermark), and written as a plain JPEG. Any failure returns
 * null, and the caller saves the camera's own file instead: a photo is never lost to an effect.
 */
object PhotoLook {
    /** [overlay] draws on top of the finished upright picture (the watermark); it gets the canvas and the picture size. */
    fun process(bytes: ByteArray, exifTag: Int, look: Look, square: Boolean, overlay: ((Canvas, Int, Int) -> Unit)?): ByteArray? {
        return try {
        var picture = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val (turn, flip) = ExifTurn.of(exifTag)
        if (turn != 0 || flip) {
            val m = Matrix().apply { postRotate(turn.toFloat()); if (flip) postScale(-1f, 1f) }
            val turned = Bitmap.createBitmap(picture, 0, 0, picture.width, picture.height, m, true)
            if (turned !== picture) picture.recycle()
            picture = turned
        }
        if (!look.isIdentity) picture = effect(picture, look) ?: return null
        if (square) {
            val (left, top, side) = AspectMath.squareCrop(picture.width, picture.height)
            val cut = Bitmap.createBitmap(picture, left, top, side, side)
            if (cut !== picture) picture.recycle()
            picture = cut
        }
        if (overlay != null) {
            if (!picture.isMutable) {
                val editable = picture.copy(Bitmap.Config.ARGB_8888, true)
                picture.recycle()
                picture = editable
            }
            overlay(Canvas(picture), picture.width, picture.height)
        }
        ByteArrayOutputStream(picture.byteCount / 6).also { picture.compress(Bitmap.CompressFormat.JPEG, 97, it); picture.recycle() }.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    /** Runs [source] through the effect shader on an offscreen surface. Returns the new picture (and recycles [source]), or null. */
    private fun effect(source: Bitmap, look: Look): Bitmap? {
        val w = source.width
        val h = source.height
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        val ids = IntArray(3)
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            val maxSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
            if (maxOf(w, h) > maxSize[0]) return null

            GLES20.glGenTextures(3, ids, 0)
            fun texture(id: Int) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            texture(ids[0])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, source, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            texture(ids[1])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 16, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(Filters.lut(look.filter)))
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            // the output picture
            val target = IntArray(1)
            GLES20.glGenFramebuffers(1, target, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[2])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ids[2], 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) return null
            GLES20.glViewport(0, 0, w, h)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]) // unit 0 samples the photo again (it was last bound to the output)

            val program = GLES20.glCreateProgram()
            val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_FLIPPED) ?: return null
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT) ?: return null
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) return null
            GLES20.glUseProgram(program)
            val v = Effects.Values(look, w.toFloat() / h)
            fun loc(name: String) = GLES20.glGetUniformLocation(program, name)
            GLES20.glUniform1i(loc("uTex"), 0)
            GLES20.glUniform1i(loc("uLut"), 1)
            GLES20.glUniform1f(loc("uFilter"), v.filter)
            GLES20.glUniform1f(loc("uSoft"), v.soft)
            GLES20.glUniform4f(loc("uStyle"), v.style[0], v.style[1], v.style[2], v.style[3])
            GLES20.glUniform1f(loc("uSharp"), v.sharp)
            GLES20.glUniform1f(loc("uVignette"), v.vignette)
            GLES20.glUniform1f(loc("uUnder"), v.under)
            GLES20.glUniform4f(loc("uTilt"), v.tilt[0], v.tilt[1], v.tilt[2], v.tilt[3])
            GLES20.glUniform2f(loc("uOut"), 1f / w, 1f / h)
            GLES20.glUniform1f(loc("uAspect"), v.aspect)
            val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return null
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.copyPixelsFromBuffer(pixels)
            source.recycle()
            return out
        } finally {
            runCatching { GLES20.glDeleteTextures(3, ids, 0) }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun compile(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        return if (ok[0] == 0) null else shader
    }

    // the picture is drawn upside down into the target so that its first row is the top row of the photo (see the read-back above)
    private const val VERTEX_FLIPPED = """
        attribute vec2 aPos;
        varying vec2 vUv;
        void main() { vUv = (aPos + 1.0) * 0.5; gl_Position = vec4(aPos.x, -aPos.y, 0.0, 1.0); }
    """

    private const val FRAGMENT = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        varying vec2 vUv;
        uniform sampler2D uTex;
        vec3 texel(vec2 d) { return texture2D(uTex, vec2(d.x, 1.0 - d.y)).rgb; }
        """ + Effects.UNIFORMS + Effects.FUNCTIONS + """
        void main() { gl_FragColor = vec4(effect(vUv), 1.0); }
    """
}
