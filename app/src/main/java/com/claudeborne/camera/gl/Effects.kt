package com.claudeborne.camera.gl

import com.claudeborne.camera.look.Look
import com.claudeborne.camera.look.SoftLight
import kotlin.math.PI

/**
 * The look of a picture as shader code, shared by the live picture, the video encoder and the offscreen pass that treats saved
 * photos, so all three show the same thing. The host shader supplies `vec3 texel(vec2 d)`: the colour of the camera picture at
 * the point [d] of the upright frame (0..1, y up).
 */
internal object Effects {
    const val UNIFORMS = """
        uniform float uFilter;
        uniform sampler2D uLut;
        uniform float uSoft;
        uniform vec4 uStyle;
        uniform float uSharp;
        uniform float uVignette;
        uniform float uUnder;
        uniform vec4 uTilt;
        uniform vec2 uOut;
        uniform float uAspect;
        // video depth of field: a person mask (1 = person) in full-frame coordinates; the background is blurred where it is 0
        uniform sampler2D uMask;
        uniform float uDof;
        uniform float uDofR;
        vec2 gMaskUv;
        // Retouch: skin ovals (centre, half axes; turn) in frame-height units, and the amounts
        uniform vec4 uSkinA[2];
        uniform vec4 uSkinB[2];
        uniform float uSkinN;
        uniform float uSmooth;
        uniform float uToneAmt;
        uniform float uSmoothR;
    """

    /** The face warp: the host puts this before its `texel`, which reads the camera at `warpB(b)` for the frame point `b` (0..1, y up, never mirrored). */
    const val WARP = """
        uniform vec4 uOpA[36];
        uniform vec4 uOpB[36];
        uniform float uOpN;
        uniform float uFAsp;
        vec2 warpB(vec2 b) {
            if (uOpN < 0.5) return b;
            vec2 pa = b * vec2(uFAsp, 1.0);
            vec2 q = pa;
            for (int i = 0; i < 36; i++) {
                if (float(i) >= uOpN) break;
                vec4 A = uOpA[i];
                vec2 d = pa - A.xy;
                float t = length(d) / A.z;
                if (t < 1.0) {
                    float k = 1.0 - t * t;
                    k *= k;
                    float ca = cos(A.w);
                    float sa = sin(A.w);
                    vec4 B = uOpB[i];
                    vec2 l = vec2(ca * d.x + sa * d.y, -sa * d.x + ca * d.y) * B.zw;
                    q -= k * (B.xy + vec2(ca * l.x - sa * l.y, sa * l.x + ca * l.y));
                }
            }
            return q / vec2(uFAsp, 1.0);
        }
    """

    const val FUNCTIONS = """
        const vec3 LUMA = vec3(0.299, 0.587, 0.114);

        vec3 lut(vec3 c) {
            c = clamp(c, 0.0, 1.0);
            float b = c.b * 15.0;
            float b0 = floor(b);
            float b1 = min(b0 + 1.0, 15.0);
            float f = b - b0;
            vec2 a = vec2((b0 * 16.0 + c.r * 15.0 + 0.5) / 256.0, (c.g * 15.0 + 0.5) / 16.0);
            vec2 d = vec2((b1 * 16.0 + c.r * 15.0 + 0.5) / 256.0, (c.g * 15.0 + 0.5) / 16.0);
            return mix(texture2D(uLut, a).rgb, texture2D(uLut, d).rgb, f);
        }

        // Retouch skin: 1 inside a face oval where the colour is skin-like, softly at the edges
        float skinMask(vec2 b) {
            vec2 pa = b * vec2(uFAsp, 1.0);
            float m = 0.0;
            for (int i = 0; i < 2; i++) {
                if (float(i) >= uSkinN) break;
                vec4 A = uSkinA[i];
                vec2 d = pa - A.xy;
                float ca = cos(uSkinB[i].x);
                float sa = sin(uSkinB[i].x);
                vec2 l = vec2(ca * d.x + sa * d.y, -sa * d.x + ca * d.y) / A.zw;
                m = max(m, 1.0 - smoothstep(0.72, 1.0, length(l)));
            }
            return m;
        }

        // Smooths skin without blurring edges (eyes, lips, hair): neighbours count less the more their brightness differs
        vec3 retouchSkin(vec3 c) {
            vec2 bw = warpB(gMaskUv);
            float m = skinMask(bw);
            if (m < 0.01) return c;
            float cb = 0.5 - 0.168736 * c.r - 0.331264 * c.g + 0.5 * c.b;
            float cr = 0.5 + 0.5 * c.r - 0.418688 * c.g - 0.081312 * c.b;
            float skin = smoothstep(0.28, 0.32, cb) * (1.0 - smoothstep(0.49, 0.53, cb)) * smoothstep(0.50, 0.54, cr) * (1.0 - smoothstep(0.66, 0.72, cr));
            m *= skin;
            if (m < 0.01) return c;
            if (uSmooth > 0.001) {
                float l0 = dot(c, LUMA);
                float sigma = 0.05 + 0.10 * uSmooth;
                vec3 acc = c;
                float wsum = 1.0;
                for (int i = 1; i < 13; i++) {
                    float k = float(i);
                    float a = k * 2.399963;
                    vec2 off = vec2(cos(a), sin(a)) * sqrt(k / 12.0) * uSmoothR;
                    vec3 s = texelRaw(bw + off / vec2(uFAsp, 1.0));
                    float dl = dot(s, LUMA) - l0;
                    float w = exp(-dl * dl / (2.0 * sigma * sigma));
                    acc += s * w;
                    wsum += w;
                }
                c = mix(c, acc / wsum, clamp(uSmooth * 1.2, 0.0, 1.0) * m);
            }
            if (uToneAmt > 0.001) {
                // an even, slightly brighter and warmer skin
                c = mix(c, clamp(c * 1.06 + vec3(0.04, 0.034, 0.028), 0.0, 1.0), uToneAmt * m);
            }
            return c;
        }

        // Tilt-shift: blur grows with the distance from a band that can be moved and turned
        vec3 base(vec2 d) {
            if (uDof > 0.5) {
                float m = texture2D(uMask, gMaskUv).r;
                float amount = 1.0 - smoothstep(0.3, 0.7, m);
                if (amount < 0.03) return texel(d);
                float radius = amount * uDofR;
                vec3 acc = texel(d);
                for (int i = 1; i < 16; i++) {
                    float k = float(i);
                    float a = k * 2.399963;
                    vec2 off = vec2(cos(a), sin(a)) * sqrt(k / 16.0) * radius;
                    acc += texel(d + off * vec2(1.0 / uAspect, 1.0));
                }
                return acc / 16.0;
            }
            if (uTilt.w < 0.5) return texel(d);
            vec2 p = (d - vec2(0.5, uTilt.x)) * vec2(uAspect, 1.0);
            float dist = abs(-p.x * sin(uTilt.z) + p.y * cos(uTilt.z));
            float amount = smoothstep(uTilt.y, uTilt.y + 0.2, dist);
            if (amount < 0.02) return texel(d);
            float radius = amount * 0.022;
            vec3 acc = texel(d);
            for (int i = 1; i < 16; i++) {
                float k = float(i);
                float a = k * 2.399963;
                vec2 off = vec2(cos(a), sin(a)) * sqrt(k / 16.0) * radius;
                acc += texel(d + off * vec2(1.0 / uAspect, 1.0));
            }
            return acc / 16.0;
        }

        vec3 effect(vec2 d) {
            vec3 c = base(d);
            if (uSkinN > 0.5) c = retouchSkin(c);
            if (uUnder > 0.5) {
                // water takes the red away first: put it back, calm the blue and green
                c *= vec3(1.6, 1.05, 0.8);
                c = (c - 0.5) * 1.12 + 0.52;
            }
            if (uSharp > 0.001) {
                vec3 n = (texel(d + vec2(uOut.x, 0.0)) + texel(d - vec2(uOut.x, 0.0)) + texel(d + vec2(0.0, uOut.y)) + texel(d - vec2(0.0, uOut.y))) * 0.25;
                c += (c - n) * uSharp * 2.2;
            }
            if (uSoft > 0.001) {
                // Soft light: the bright parts bleed a wide halo, the blacks lift a little and the contrast falls
                vec3 glow = vec3(0.0);
                for (int i = 0; i < 24; i++) {
                    float k = float(i) + 0.5;
                    float a = k * 2.399963;
                    vec2 off = vec2(cos(a), sin(a)) * sqrt(k / 24.0) * 0.05;
                    vec3 s = texel(d + off * vec2(1.0 / uAspect, 1.0));
                    glow += max(s - 0.5, vec3(0.0)) * 2.0;
                }
                glow /= 24.0;
                c = 1.0 - (1.0 - c) * (1.0 - clamp(glow * uSoft * 1.8, 0.0, 1.0));
                c += 0.035 * uSoft;
                c = mix(c, vec3(0.5), 0.1 * uSoft);
            }
            float l = dot(c, LUMA);
            c = (c - 0.5) * (1.0 + uStyle.x * 0.45) + 0.5;
            c = mix(vec3(dot(c, LUMA)), c, 1.0 + uStyle.y * 0.5);
            c *= vec3(1.0 + uStyle.z * 0.1, 1.0 - uStyle.w * 0.07, 1.0 - uStyle.z * 0.1);
            if (uFilter > 0.5) c = lut(c);
            if (uVignette > 0.5) c *= 1.0 - 0.38 * smoothstep(0.3, 0.8, length((d - 0.5) * vec2(1.0, 1.15)));
            return clamp(c, 0.0, 1.0);
        }
    """

    /** What the shader needs to know about a [look]; the host sets these uniforms on its program. */
    class Values(look: Look, val aspect: Float) {
        val on = if (look.isIdentity) 0f else 1f
        val filter = if (look.filter > 0) 1f else 0f
        val soft = SoftLight.strength(look.soft)
        val style = floatArrayOf(look.tone / 10f, look.sat / 10f, look.warm / 10f, look.tint / 10f)
        val sharp = look.sharp / 10f
        val vignette = if (look.vignette) 1f else 0f
        val under = if (look.underwater) 1f else 0f
        val tilt = look.tilt?.let { floatArrayOf(it.centre, it.half, (it.angle * PI / 180.0).toFloat(), 1f) } ?: floatArrayOf(0.5f, 0.1f, 0f, 0f)
    }
}
