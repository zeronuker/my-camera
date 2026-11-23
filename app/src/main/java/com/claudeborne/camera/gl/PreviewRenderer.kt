package com.claudeborne.camera.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The one preview path (CLAUDE.md): camera frames arrive in a [SurfaceTexture] and are drawn to the screen here.
 * Filters, recording and dual view plug into this class in later phases.
 *
 * Switching camera: [freeze] keeps the last picture on screen, then [unfreeze] fades it into the new camera's first frame,
 * so a switch is never a black flash.
 */
class PreviewRenderer(
    private val requestRender: () -> Unit,
    private val onTexture: (SurfaceTexture) -> Unit,
) : GLSurfaceView.Renderer {
    @Volatile var sensorOrientation = 90
    @Volatile var mirror = false
    @Volatile var bufferWidth = 1920
    @Volatile var bufferHeight = 1440

    private val newFrame = AtomicBoolean(false)
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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        runCatching { surfaceTexture?.release() }
        hasFrame = false
        holding = false
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
        st.setOnFrameAvailableListener { newFrame.set(true); requestRender() }
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
        val st = surfaceTexture ?: return
        val gotFrame = newFrame.getAndSet(false)
        if (gotFrame) {
            st.updateTexImage()
            st.getTransformMatrix(transform)
            hasFrame = true
        }
        if (freezeRequested) {
            freezeRequested = false
            if (hasFrame) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, holdFbo)
                drawLive()
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                holding = true
                fadeStartNs = 0L
            }
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!holding) {
            if (hasFrame) drawLive()
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
            drawLive()
            return
        }
        drawLive()
        drawHeld(1f - t)
        requestRender() // keep drawing while the fade runs
    }

    private fun drawLive() {
        GLES20.glUseProgram(oesProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(oesProgram, "uTex"), 0)
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(oesProgram, "uM"), 1, false,
            PreviewMath.matrix(sensorOrientation, mirror, bufferWidth, bufferHeight, viewW, viewH), 0)
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
            void main() { gl_FragColor = vec4(texture2D(uTex, vUv).rgb, uAlpha); }
        """
    }
}
