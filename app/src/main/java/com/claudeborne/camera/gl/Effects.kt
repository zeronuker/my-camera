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
