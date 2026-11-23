package com.claudeborne.camera.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig as Egl14Config
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The one preview path (CLAUDE.md): camera frames arrive in a [SurfaceTexture] and are drawn to the screen here.
 * Filters, recording and dual view plug into this class in later phases.
 *
 * Switching camera: [freeze] keeps the last picture on screen, then [unfreeze] fades it into the new camera's first frame,
 * so a switch is never a black flash.
 *
 * Recording: [startEncoding] makes every camera frame be drawn a second time into the video encoder's surface, by the same
 * shader and the same crop maths as the screen, so the file shows exactly what the viewfinder shows.
 */
class PreviewRenderer(
    private val requestRender: () -> Unit,
    private val onTexture: (SurfaceTexture) -> Unit,
    /** Runs a block on the GL thread (GLSurfaceView.queueEvent). */
    private val post: (() -> Unit) -> Unit = { },
) : GLSurfaceView.Renderer {
    /**
     * Where the same camera frame is drawn a second time: the video encoder's input surface. [turn] is the clockwise turn of the
     * upright picture for a phone held sideways, [gate] returns the presentation time (ns) to give a frame or -1 to leave it out
     * (paused, time-lapse skipping, before the start). [screenEvery] draws only every n-th frame to the screen (120 and 240 fps).
     */
    class Encoding(
        val surface: Surface, val width: Int, val height: Int, val turn: Int, val mirror: Boolean, val tenBit: Boolean,
        val screenEvery: Int, val gate: (frameTimestampNs: Long) -> Long,
    )

    private class Target(val display: EGLDisplay, val context: EGLContext, val surface: EGLSurface)

    @Volatile var sensorOrientation = 90
    @Volatile var mirror = false
    @Volatile var bufferWidth = 1920
    @Volatile var bufferHeight = 1440
    /** The band of the screen the picture fills, in pixels from the top; a height of 0 means the whole screen. */
    @Volatile var boxTop = 0
    @Volatile var boxHeight = 0
    private var holdShare = 1f

    private val pending = AtomicInteger(0) // camera frames not yet taken into the texture
    @Volatile private var encoding: Encoding? = null
    private var target: Target? = null
    private var sinceScreen = 0
    private var snap: ((Bitmap?) -> Unit)? = null
    private var snapWidth = 0
    private var snapHeight = 0
    private var snapTurn = 0
    private var snapMirror = false
    private var snapTex = 0
    private var snapFbo = 0
    @Volatile private var freezeRequested = false
    @Volatile private var armed = false

    private var surfaceTexture: SurfaceTexture? = null
    private var hasFrame = false
    private val transform = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private var viewW = 1
    private var viewH = 1

    private var oesTexture = 0
    private var oesProgram = 0
    private var plainProgram = 0
    private var holdTexture = 0
    private var holdFbo = 0
    private var holding = false
    private var fadeStartNs = 0L

    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    /** Keeps the current picture on screen until [unfreeze]. */
    fun freeze() {
        armed = false
        freezeRequested = true
        requestRender()
    }

    /** The new camera is streaming: fade the kept picture into its first frame. */
    fun unfreeze() { armed = true }

    /** Starts drawing every frame to [e] too. [ready] (called on the GL thread) says whether the encoder surface could be used. */
    fun startEncoding(e: Encoding, ready: (Boolean) -> Unit) = post {
        val ok = try { openTarget(e); true } catch (_: Exception) { closeTarget(); false }
        if (ok) { sinceScreen = 0; encoding = e }
        ready(ok)
    }

    /** Stops drawing to the encoder and frees its surface; [done] runs on the GL thread afterwards. */
    fun stopEncoding(done: () -> Unit) {
        encoding = null
        post { closeTarget(); done() }
    }

    /** One picture of the current frame as the video sees it, for the photo button while recording; null when it failed. */
    fun snapshot(width: Int, height: Int, turn: Int, mirror: Boolean, done: (Bitmap?) -> Unit) {
        post { snap = done; snapWidth = width; snapHeight = height; snapTurn = turn; snapMirror = mirror }
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        runCatching { surfaceTexture?.release() }
        hasFrame = false
        holding = false
        snapTex = 0
        snapFbo = 0
        pending.set(0)
        oesProgram = program(VERTEX, OES_FRAGMENT)
        plainProgram = program(VERTEX, PLAIN_FRAGMENT)
        val ids = IntArray(3)
        GLES20.glGenTextures(2, ids, 0)
        oesTexture = ids[0]
        holdTexture = ids[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenFramebuffers(1, ids, 2)
        holdFbo = ids[2]
        val st = SurfaceTexture(oesTexture)
        st.setOnFrameAvailableListener {
            pending.incrementAndGet()
            val e = encoding
            if (e == null) {
                requestRender()
            } else {
                post { encodeFrame(e) } // every frame is handled on the GL thread, one by one, whatever the screen is doing
                if (++sinceScreen >= e.screenEvery) { sinceScreen = 0; requestRender() }
            }
        }
        surfaceTexture = st
        // the screen must not wait for the display: 120 and 240 fps frames would pile up behind it
        runCatching { EGL14.eglSwapInterval(EGL14.eglGetCurrentDisplay(), 0) }
        onTexture(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES20.glViewport(0, 0, width, height)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, holdTexture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, holdFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, holdTexture, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        holding = false // the kept picture is gone with the old size
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        var gotFrame = false
        if (encoding == null) { // while recording, the encoder events take every frame (see encodeFrame)
            gotFrame = takeFrame(st)
            while (pending.get() > 0 && takeFrame(st)) { /* show the newest */ }
        }
        takeSnapshot()
        val boxH = if (boxHeight in 1 until viewH) boxHeight else viewH // the picture is drawn only inside this band of the screen
        if (freezeRequested) {
            freezeRequested = false
            if (hasFrame) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, holdFbo)
                GLES20.glViewport(0, 0, viewW, boxH)
                drawLive(boxH)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                holdShare = boxH.toFloat() / viewH
                holding = true
                fadeStartNs = 0L
            }
        }
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(0, (viewH - boxTop - boxH).coerceAtLeast(0), viewW, boxH)
        if (!holding) {
            if (hasFrame) drawLive(boxH)
            return
        }
        if (armed && gotFrame && fadeStartNs == 0L) fadeStartNs = System.nanoTime()
        if (fadeStartNs == 0L) {
            drawHeld(1f)
            return
        }
        val t = (System.nanoTime() - fadeStartNs) / (FADE_MS * 1_000_000f)
        if (t >= 1f) {
            holding = false
            drawLive(boxH)
            return
        }
        drawLive(boxH)
        drawHeld(1f - t)
        requestRender() // keep drawing while the fade runs
    }

    /** Moves the oldest waiting camera frame into the texture. False when there is none. */
    private fun takeFrame(st: SurfaceTexture): Boolean {
        if (pending.get() <= 0) return false
        pending.decrementAndGet()
        return try {
            st.updateTexImage()
            st.getTransformMatrix(transform)
            hasFrame = true
            true
        } catch (_: Exception) {
            false // the texture was released while the camera closed
        }
    }

    private fun openTarget(e: Encoding) {
        val display = EGL14.eglGetCurrentDisplay()
        val shared = EGL14.eglGetCurrentContext()
        val bits = if (e.tenBit) 10 else 8
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, bits, EGL14.EGL_GREEN_SIZE, bits, EGL14.EGL_BLUE_SIZE, bits, EGL14.EGL_ALPHA_SIZE, if (e.tenBit) 2 else 8,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<Egl14Config>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) throw IllegalStateException("no recordable surface format")
        val config = configs[0]!!
        // its own context in the same share group: a 10-bit surface cannot be current in the 8-bit context of the screen
        val context = EGL14.eglCreateContext(display, config, shared, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("no GL context for the encoder")
        val surface = EGL14.eglCreateWindowSurface(display, config, e.surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(display, context)
            throw IllegalStateException("no surface for the encoder")
        }
        target = Target(display, context, surface)
    }

    private fun closeTarget() {
        val t = target ?: return
        target = null
        EGL14.eglDestroySurface(t.display, t.surface)
        EGL14.eglDestroyContext(t.display, t.context)
    }

    /** GL thread: takes the next camera frame and draws it into the encoder's surface with the time the recorder gave it. */
    private fun encodeFrame(e: Encoding) {
        val st = surfaceTexture ?: return
        val t = target
        if (encoding !== e || t == null) { takeFrame(st); return } // recording ended: keep the texture moving for the screen
        if (!takeFrame(st)) return
        val pts = e.gate(st.timestamp)
        if (pts < 0) return
        val screen = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val screenRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val screenContext = EGL14.eglGetCurrentContext()
        try {
            if (!EGL14.eglMakeCurrent(t.display, t.surface, t.surface, t.context)) return
            GLES20.glViewport(0, 0, e.width, e.height)
            drawOes(PreviewMath.matrix(sensorOrientation, e.mirror, bufferWidth, bufferHeight, e.width, e.height, e.turn))
            EGLExt.eglPresentationTimeANDROID(t.display, t.surface, pts)
            EGL14.eglSwapBuffers(t.display, t.surface)
        } finally {
            EGL14.eglMakeCurrent(t.display, screen, screenRead, screenContext)
        }
    }

    private fun takeSnapshot() {
        val done = snap ?: return
        if (!hasFrame) return
        snap = null
        val w = snapWidth
        val h = snapHeight
        var bitmap: Bitmap? = null
        try {
            if (snapTex == 0) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0); snapTex = ids[0]
                GLES20.glGenFramebuffers(1, ids, 0); snapFbo = ids[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, snapTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, snapFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, snapTex, 0)
            GLES20.glViewport(0, 0, w, h)
            drawOes(PreviewMath.matrix(sensorOrientation, snapMirror, bufferWidth, bufferHeight, w, h, snapTurn))
            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(pixels)
            val flip = android.graphics.Matrix().apply { preScale(1f, -1f) } // GL reads from the bottom row up
            bitmap = Bitmap.createBitmap(raw, 0, 0, w, h, flip, false)
            raw.recycle()
        } catch (_: Throwable) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
        GLES20.glViewport(0, 0, viewW, viewH)
        done(bitmap)
    }

    private fun drawLive(boxH: Int) {
        drawOes(PreviewMath.matrix(sensorOrientation, mirror, bufferWidth, bufferHeight, viewW, boxH))
    }

    private fun drawOes(matrix: FloatArray) {
        GLES20.glUseProgram(oesProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(oesProgram, "uTex"), 0)
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(oesProgram, "uM"), 1, false, matrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(oesProgram, "uSt"), 1, false, transform, 0)
        drawQuad(oesProgram)
    }

    private fun drawHeld(alpha: Float) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(plainProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, holdTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(plainProgram, "uTex"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(plainProgram, "uAlpha"), alpha)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(plainProgram, "uShare"), holdShare)
        drawQuad(plainProgram)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawQuad(program: Int) {
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
    }

    private fun program(vertex: String, fragment: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        return p
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val FADE_MS = 260f // same as MOTION_MS in ui/Motion.kt

        const val VERTEX = """
            attribute vec2 aPos;
            varying vec2 vUv;
            void main() { vUv = (aPos + 1.0) * 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        const val OES_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTex;
            uniform mat3 uM;
            uniform mat4 uSt;
            void main() {
                vec2 b = (uM * vec3(vUv, 1.0)).xy;
                gl_FragColor = texture2D(uTex, (uSt * vec4(b, 0.0, 1.0)).xy);
            }
        """

        const val PLAIN_FRAGMENT = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform float uAlpha;
            uniform float uShare;
            void main() { gl_FragColor = vec4(texture2D(uTex, vec2(vUv.x, vUv.y * uShare)).rgb, uAlpha); }
        """
    }
}
