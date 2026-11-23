package com.claudeborne.camera.gl

import android.opengl.GLES20
import com.claudeborne.camera.face.Packed

/** Hands the retouch data to a program that uses [Effects.WARP] (the program must be in use). */
internal object FaceUniforms {
    fun apply(program: Int, p: Packed) {
        fun loc(name: String) = GLES20.glGetUniformLocation(program, name)
        GLES20.glUniform1f(loc("uOpN"), p.count.toFloat())
        GLES20.glUniform1f(loc("uFAsp"), p.aspect)
        if (p.count > 0) {
            GLES20.glUniform4fv(loc("uOpA"), Packed.MAX_OPS, p.opA, 0)
            GLES20.glUniform4fv(loc("uOpB"), Packed.MAX_OPS, p.opB, 0)
        }
        GLES20.glUniform1f(loc("uSkinN"), if (p.smooth > 0f || p.tone > 0f) p.faces.toFloat() else 0f)
        if (p.faces > 0) {
            GLES20.glUniform4fv(loc("uSkinA"), Packed.MAX_FACES, p.skinA, 0)
            GLES20.glUniform4fv(loc("uSkinB"), Packed.MAX_FACES, p.skinB, 0)
        }
        GLES20.glUniform1f(loc("uSmooth"), p.smooth)
        GLES20.glUniform1f(loc("uToneAmt"), p.tone)
        GLES20.glUniform1f(loc("uSmoothR"), p.radius)
    }
}
