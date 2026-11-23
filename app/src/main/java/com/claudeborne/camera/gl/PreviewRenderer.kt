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
import com.claudeborne.camera.look.Filters
import com.claudeborne.camera.look.Look
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
     * (paused, time-lapse skipping, before the start).
     */
    class Encoding(
        val surface: Surface, val width: Int, val height: Int, val turn: Int, val mirror: Boolean, val tenBit: Boolean,
        val gate: (frameTimestampNs: Long) -> Long,
        /** Pro video: the file gets the flat "log" look (see the shader below). */
        val log: Boolean = false,
        /** A transparent picture of the video's size laid over every frame (the watermark); null for none. */
        val overlay: Bitmap? = null,
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

    /** What is done to the picture besides showing it (filter, Soft light, style, tilt-shift...): live picture, video and snapshot alike. */
    @Volatile var look: Look = Look.NONE
    private var lutTex = 0
    private var overlayProgram = 0
    private var overlayTex = 0
    private var lutFilter = -1

    /** Pro assistants, drawn on the screen only: never into a video, a snapshot or a saved photo. */
    @Volatile var peaking = false
    @Volatile var zebra = false
    /** The screen shows the flat log look too (Pro video with the LUT preview off). */
    @Volatile var viewLog = false
    /** Colour of the focus peaking (the brand mint). */
    @Volatile var assistColor = floatArrayOf(0.25f, 0.88f, 0.77f)
    /** Receives a 64 bin brightness histogram of the live picture a few times a second while set. */
    @Volatile var histogram: ((IntArray) -> Unit)? = null
    private var histAt = 0L
    private var histTex = 0
    private var histFbo = 0
    private var histW = 0
    private var histH = 0

    private val pending = AtomicInteger(0) // camera frames not yet taken into the texture
    @Volatile private var encoding: Encoding? = null
    private var target: Target? = null
    private var snap: ((Bitmap?) -> Unit)? = null
    private var snapWidth = 0
    private var snapHeight = 0
    private var snapTurn = 0
    private var snapMirror = false
    private var snapTex = 0
    private var snapFbo = 0
    @Volatile private var freezeRequested = false
    @Volatile private var armed = false
    @Volatile private var frozenAtNs = 0L

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
        frozenAtNs = System.nanoTime()
        freezeRequested = true
        requestRender()
    }

    /** The new camera is streaming: fade the kept picture into its first frame. */
    fun unfreeze() { armed = true }

    /** Starts drawing every frame to [e] too. [ready] (called on the GL thread) says whether the encoder surface could be used. */
    fun startEncoding(e: Encoding, ready: (Boolean) -> Unit) = post {
        val ok = try { openTarget(e); e.overlay?.let(::uploadOverlay); true } catch (_: Exception) { closeTarget(); false }
        if (ok) encoding = e
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
        histTex = 0
        histFbo = 0
        histW = 0
        lutFilter = -1
        pending.set(0)
        oesProgram = program(VERTEX, OES_FRAGMENT)
        plainProgram = program(VERTEX, PLAIN_FRAGMENT)
        overlayProgram = program(VERTEX, OVERLAY_FRAGMENT)
        overlayTex = 0
        val ids = IntArray(4)
        GLES20.glGenTextures(3, ids, 0)
        oesTexture = ids[0]
        holdTexture = ids[1]
        lutTex = ids[2]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenFramebuffers(1, ids, 3)
        holdFbo = ids[3]
        val st = SurfaceTexture(oesTexture)
        st.setOnFrameAvailableListener {
            pending.incrementAndGet()
            val e = encoding
            if (e == null) {
                requestRender()
            } else {
                post { encodeFrame(e) } // every frame is handled on the GL thread, one by one, whatever the screen is doing
                requestRender()
            }
        }
        surfaceTexture = st
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
        drawScreen()
        maybeHistogram()
    }

    private fun drawScreen() {
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
        if (!armed && gotFrame && System.nanoTime() - frozenAtNs > FROZEN_MAX_NS) armed = true // nobody unfroze it: never leave the viewfinder stuck
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

    private fun uploadOverlay(bitmap: Bitmap) {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        overlayTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    /** The watermark, over the frame just drawn into the encoder. */
    private fun drawOverlay() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // the picture is premultiplied
        GLES20.glUseProgram(overlayProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(overlayProgram, "uTex"), 0)
        drawQuad(overlayProgram)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun closeTarget() {
        if (overlayTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(overlayTex), 0); overlayTex = 0 }
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
            drawOes(PreviewMath.matrix(sensorOrientation, e.mirror, bufferWidth, bufferHeight, e.width, e.height, e.turn), e.width, e.height, log = e.log)
            if (overlayTex != 0) drawOverlay()
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
            drawOes(PreviewMath.matrix(sensorOrientation, snapMirror, bufferWidth, bufferHeight, w, h, snapTurn), w, h)
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
        drawOes(PreviewMath.matrix(sensorOrientation, mirror, bufferWidth, bufferHeight, viewW, boxH), viewW, boxH, assist = true, log = viewLog)
    }

    /** Draws the current camera frame. [assist] adds the Pro assistants (screen only); [log] applies the flat log look. */
    private fun drawOes(matrix: FloatArray, outW: Int, outH: Int, assist: Boolean = false, log: Boolean = false) {
        GLES20.glUseProgram(oesProgram)
        applyLook(outW, outH)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(oesProgram, "uPeak"), if (assist && peaking) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(oesProgram, "uZebra"), if (assist && zebra) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(oesProgram, "uLog"), if (log) 1f else 0f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(oesProgram, "uPx"), 2.5f / bufferWidth.coerceAtLeast(1), 2.5f / bufferHeight.coerceAtLeast(1))
        val c = assistColor
        GLES20.glUniform3f(GLES20.glGetUniformLocation(oesProgram, "uAssist"), c[0], c[1], c[2])
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(oesProgram, "uTex"), 0)
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(oesProgram, "uM"), 1, false, matrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(oesProgram, "uSt"), 1, false, transform, 0)
        drawQuad(oesProgram)
    }

    /** Sets the look uniforms of the OES program (the program is already in use) and keeps the filter's table on texture unit 1. */
    private fun applyLook(outW: Int, outH: Int) {
        val look = look
        val v = Effects.Values(look, outW.toFloat() / outH.coerceAtLeast(1))
        fun loc(name: String) = GLES20.glGetUniformLocation(oesProgram, name)
        GLES20.glUniform1f(loc("uOn"), v.on)
        if (v.on > 0.5f) {
            if (look.filter > 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
                if (lutFilter != look.filter) {
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 16, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(Filters.lut(look.filter)))
                    lutFilter = look.filter
                }
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            }
            GLES20.glUniform1i(loc("uLut"), 1)
            GLES20.glUniform1f(loc("uFilter"), v.filter)
            GLES20.glUniform1f(loc("uSoft"), v.soft)
            GLES20.glUniform4f(loc("uStyle"), v.style[0], v.style[1], v.style[2], v.style[3])
            GLES20.glUniform1f(loc("uSharp"), v.sharp)
            GLES20.glUniform1f(loc("uVignette"), v.vignette)
            GLES20.glUniform1f(loc("uUnder"), v.under)
            GLES20.glUniform4f(loc("uTilt"), v.tilt[0], v.tilt[1], v.tilt[2], v.tilt[3])
            GLES20.glUniform2f(loc("uOut"), 1f / outW.coerceAtLeast(1), 1f / outH.coerceAtLeast(1))
            GLES20.glUniform1f(loc("uAspect"), v.aspect)
        }
    }

    /** About five times a second: draws the frame small into a scratch texture, reads it back and counts its brightness. */
    private fun maybeHistogram() {
        val sink = histogram ?: return
        if (!hasFrame) return
        val now = System.nanoTime()
        if (now - histAt < 200_000_000L) return
        histAt = now
        val w = 96
        val boxH = if (boxHeight in 1 until viewH) boxHeight else viewH
        val h = (w.toFloat() * boxH / viewW.coerceAtLeast(1)).toInt().coerceIn(16, 256)
        try {
            if (histTex == 0) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0); histTex = ids[0]
                GLES20.glGenFramebuffers(1, ids, 0); histFbo = ids[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTex)
            if (w != histW || h != histH) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                histW = w; histH = h
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, histFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, histTex, 0)
            GLES20.glViewport(0, 0, w, h)
            drawOes(PreviewMath.matrix(sensorOrientation, mirror, bufferWidth, bufferHeight, w, h), w, h)
            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val bytes = ByteArray(w * h * 4).also { pixels.get(it) }
            sink(Histogram.of(bytes))
        } catch (_: Throwable) {
            // the histogram is a nice-to-have: a failed read never touches the picture
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewW, viewH)
        }
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
        const val FROZEN_MAX_NS = 2_500_000_000L
        const val FADE_MS = 260f // same as MOTION_MS in ui/Motion.kt

        const val VERTEX = """
            attribute vec2 aPos;
            varying vec2 vUv;
            void main() { vUv = (aPos + 1.0) * 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        const val OES_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform samplerExternalOES uTex;
            uniform mat3 uM;
            uniform mat4 uSt;
            uniform float uOn;
            uniform float uPeak;
            uniform float uZebra;
            uniform float uLog;
            uniform vec2 uPx;
            uniform vec3 uAssist;
            vec3 texel(vec2 d) {
                vec2 b = (uM * vec3(d, 1.0)).xy;
                return texture2D(uTex, (uSt * vec4(b, 0.0, 1.0)).xy).rgb;
            }
            """ + Effects.UNIFORMS + Effects.FUNCTIONS + """
            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            void main() {
                vec2 b = (uM * vec3(vUv, 1.0)).xy;
                vec2 uv = (uSt * vec4(b, 0.0, 1.0)).xy;
                vec3 raw = texture2D(uTex, uv).rgb;
                vec3 c = raw;
                if (uOn > 0.5) c = effect(vUv);
                float l = luma(raw);
                if (uLog > 0.5) {
                    // our own flat profile: shadows lifted, highlights squeezed, colour pulled back; the grade restores it
                    vec3 f = log2(vec3(1.0) + 30.0 * c) / log2(31.0);
                    f = f * 0.86 + 0.06;
                    c = mix(vec3(luma(f)), f, 0.78);
                }
                if (uPeak > 0.5) {
                    float e = abs(4.0 * l - luma(texture2D(uTex, uv + vec2(uPx.x, 0.0)).rgb) - luma(texture2D(uTex, uv - vec2(uPx.x, 0.0)).rgb)
                        - luma(texture2D(uTex, uv + vec2(0.0, uPx.y)).rgb) - luma(texture2D(uTex, uv - vec2(0.0, uPx.y)).rgb));
                    if (e > 0.16) c = mix(c, uAssist, 0.9);
                }
                if (uZebra > 0.5 && luma(c) > 0.95) {
                    float s = step(0.5, fract((gl_FragCoord.x + gl_FragCoord.y) / 16.0));
                    c = mix(c, vec3(0.05), s * 0.85);
                }
                gl_FragColor = vec4(c, 1.0);
            }
        """

        const val OVERLAY_FRAGMENT = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vec2(vUv.x, 1.0 - vUv.y)); }
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
