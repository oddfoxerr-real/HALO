package dev.oddfoxerr.halo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Single-pass procedural edge visualizer for API 33+.
 *
 * Each preset is generated in fragment space from the rounded-frame signed distance,
 * edge-local coordinates and smoothed FFT bands. No pre-rendered lines, canvas glow
 * layers or legacy vector paths participate in the final GPU image.
 *
 * Keep the compiled shader for the lifetime of the View; only update uniforms per frame.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GpuEdgeRenderer {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isDither = true
        shader = this@GpuEdgeRenderer.shader
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        bounds: RectF,
        cornerRadius: Float,
        edges: BooleanArray,
        bands: FloatArray,
        bass: Float,
        phase: Float,
        timeSeconds: Float,
        mode: EdgeMode,
        density: Float,
        thickness: Float,
        spread: Float,
        glow: Float,
        brightness: Float,
        primary: Int,
        accent: Int
    ) {
        if (width <= 0 || height <= 0 || edges.none { it } || bounds.isEmpty) return
        shader.setFloatUniform("uResolution", width.toFloat(), height.toFloat())
        shader.setFloatUniform("uFrame", bounds.left, bounds.top, bounds.right, bounds.bottom)
        shader.setFloatUniform("uRadius", cornerRadius)
        shader.setFloatUniform("uSides",
            if (edges[0]) 1f else 0f, if (edges[1]) 1f else 0f,
            if (edges[2]) 1f else 0f, if (edges[3]) 1f else 0f)
        shader.setFloatUniform("uTime", timeSeconds)
        shader.setFloatUniform("uPhase", phase)
        shader.setFloatUniform("uBass", bass.coerceIn(0f, 1f))
        shader.setFloatUniform("uThickness", density * thickness.coerceIn(.6f, 3f))
        shader.setFloatUniform("uSpread", spread.coerceIn(.3f, 2.8f))
        shader.setFloatUniform("uGlow", glow.coerceIn(0f, 1f))
        shader.setFloatUniform("uBrightness", brightness.coerceIn(.2f, 1f))
        shader.setIntUniform("uMode", mode.ordinal)
        shader.setColorUniform("uPrimary", primary)
        shader.setColorUniform("uAccent", accent)
        shader.setFloatUniform("uBands", bands)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    companion object {
        // AGSL (SkSL subset): explicit premultiplication yields correct translucent overlays.
        private const val SOURCE = """
uniform float2 uResolution;
uniform float4 uFrame;
uniform float uRadius;
uniform float4 uSides;
uniform float uTime;
uniform float uPhase;
uniform float uBass;
uniform float uThickness;
uniform float uSpread;
uniform float uGlow;
uniform float uBrightness;
uniform float uBands[24];
uniform int uMode;
layout(color) uniform half4 uPrimary;
layout(color) uniform half4 uAccent;

float hash11(float p) {
    return fract(sin(p * 127.1) * 43758.5453);
}

float envelope(float x, float spread) {
    float normalized = x / max(.0001, spread);
    return exp(-normalized * normalized);
}

// 3D simplex noise adapted from Daniel Sandner's Audio Shader Studio
// shaders/geometric-patterns/fluidic-1.glsl (MIT, Copyright 2025 Daniel Sandner).
// See THIRD_PARTY_NOTICES.md for the license and upstream URL.
float3 mod289_3(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float4 mod289_4(float4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float4 permute4(float4 x) { return mod289_4(((x * 34.0) + 1.0) * x); }
float4 inverseSqrt4(float4 r) { return 1.79284291400159 - 0.85373472095314 * r; }
float noise3(float3 v) {
    float2 C = float2(1.0 / 6.0, 1.0 / 3.0);
    float4 D = float4(0.0, 0.5, 1.0, 2.0);
    float3 i = floor(v + dot(v, C.yyy));
    float3 x0 = v - i + dot(i, C.xxx);
    float3 g = step(x0.yzx, x0.xyz);
    float3 l = 1.0 - g;
    float3 i1 = min(g.xyz, l.zxy);
    float3 i2 = max(g.xyz, l.zxy);
    float3 x1 = x0 - i1 + C.xxx;
    float3 x2 = x0 - i2 + C.yyy;
    float3 x3 = x0 - D.yyy;
    i = mod289_3(i);
    float4 p = permute4(permute4(permute4(
        i.z + float4(0.0, i1.z, i2.z, 1.0)) +
        i.y + float4(0.0, i1.y, i2.y, 1.0)) +
        i.x + float4(0.0, i1.x, i2.x, 1.0));
    float n_ = .142857142857;
    float3 ns = n_ * D.wyz - D.xzx;
    float4 j = p - 49.0 * floor(p * ns.z * ns.z);
    float4 x_ = floor(j * ns.z);
    float4 y_ = floor(j - 7.0 * x_);
    float4 x = x_ * ns.x + ns.yyyy;
    float4 y = y_ * ns.x + ns.yyyy;
    float4 h = 1.0 - abs(x) - abs(y);
    float4 b0 = float4(x.xy, y.xy);
    float4 b1 = float4(x.zw, y.zw);
    float4 s0 = floor(b0) * 2.0 + 1.0;
    float4 s1 = floor(b1) * 2.0 + 1.0;
    float4 sh = -step(h, float4(0.0));
    float4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
    float4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
    float3 p0 = float3(a0.xy, h.x);
    float3 p1 = float3(a0.zw, h.y);
    float3 p2 = float3(a1.xy, h.z);
    float3 p3 = float3(a1.zw, h.w);
    float4 norm = inverseSqrt4(float4(
        dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
    p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;
    float4 m = max(0.6 - float4(
        dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
    m *= m;
    return 42.0 * dot(m * m,
        float4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}

float audioBand(float position) {
    float index = clamp(position, 0.0, 0.999999) * 23.0;
    int left = int(floor(index));
    int right = min(23, left + 1);
    float blend = smoothstep(0.0, 1.0, fract(index));
    return mix(uBands[left], uBands[right], blend);
}

half4 main(float2 fragCoord) {
    float2 center = (uFrame.xy + uFrame.zw) * .5;
    float2 halfBox = max((uFrame.zw - uFrame.xy) * .5, float2(1.0, 1.0));
    float radius = clamp(uRadius, 0.0, min(halfBox.x, halfBox.y));
    float2 q = abs(fragCoord - center) - halfBox + float2(radius);
    float signedDistance = length(max(q, float2(0.0, 0.0))) +
        min(max(q.x, q.y), 0.0) - radius;

    // Cull the transparent center immediately: a single shader pass, no full-screen
    // glow bitmap. Negative distance points towards the middle of the screen.
    float limit = (45.0 + 30.0 * uThickness) * uSpread;
    if (abs(signedDistance) > limit) {
        return half4(0.0);
    }

    // Physical top/right/bottom/left selection is independent of visualizer mode.
    float leftDistance = abs(fragCoord.x - uFrame.x);
    float rightDistance = abs(fragCoord.x - uFrame.z);
    float topDistance = abs(fragCoord.y - uFrame.y);
    float bottomDistance = abs(fragCoord.y - uFrame.w);
    float leftRight = min(leftDistance, rightDistance);
    float topBottom = min(topDistance, bottomDistance);
    float enabled = 1.0;
    float coordinate = 0.0;
    float side = 0.0;
    if (topBottom < leftRight) {
        if (topDistance < bottomDistance) {
            side = 0.0;
            enabled = uSides.x;
            coordinate = clamp((fragCoord.x - uFrame.x) / max(1.0, uFrame.z - uFrame.x), 0.0, 1.0);
        } else {
            side = 2.0;
            enabled = uSides.z;
            coordinate = 1.0 - clamp((fragCoord.x - uFrame.x) / max(1.0, uFrame.z - uFrame.x), 0.0, 1.0);
        }
    } else {
        if (rightDistance < leftDistance) {
            side = 1.0;
            enabled = uSides.y;
            coordinate = clamp((fragCoord.y - uFrame.y) / max(1.0, uFrame.w - uFrame.y), 0.0, 1.0);
        } else {
            side = 3.0;
            enabled = uSides.w;
            coordinate = 1.0 - clamp((fragCoord.y - uFrame.y) / max(1.0, uFrame.w - uFrame.y), 0.0, 1.0);
        }
    }
    if (enabled < .5) return half4(0.0);

    float u = (side + coordinate) * .25;
    float t = uTime;
    float drive = clamp(uBass, 0.0, 1.0);
    float spectrum = audioBand(fract(u * 2.0 + uPhase * .12));
    float wave = sin(u * 25.13274 - t * 1.3) * .5 + .5;
    float wobble = sin(u * 56.54867 + t * 1.1 + sin(t + u * 12.0)) * .5 + .5;
    float activity = clamp(.08 + spectrum * .9 + drive * .46, 0.0, 1.0);

    float baseWidth = (2.2 + 12.0 * activity) * uThickness * uSpread;
    float inner = max(0.0, -signedDistance);
    float outer = max(0.0, signedDistance);
    float core = 0.0;
    float plume = 0.0;
    float detail = 0.0;
    float motion = 0.0;
    float hueTravel = fract(u + t * .028);

    if (uMode == 0) { // Silk: broad, soft, fluid ribbon
        float front = baseWidth * (.6 + .55 * wave + spectrum * 1.1);
        core = envelope(inner - front * .48, front * .35 + 1.4);
        plume = envelope(inner - front * .65, front * .72 + 5.0);
        motion = wave;
    } else if (uMode == 1) { // Spectrum: individually animated liquid columns
        float bin = floor(coordinate * 42.0);
        float discrete = audioBand(fract((bin + side * 42.0) / 168.0 * 2.4));
        float column = smoothstep(.02, .17, fract(coordinate * 42.0)) *
            (1.0 - smoothstep(.72, .96, fract(coordinate * 42.0)));
        float length = (3.0 + discrete * 56.0) * uThickness * uSpread;
        core = column * (1.0 - smoothstep(length - 1.5, length + 2.0, inner));
        plume = column * envelope(inner - length * .45, length * .5 + 5.0);
        motion = discrete;
    } else if (uMode == 2 || uMode == 6) { // Orbit and Comet: flowing heads, fading tails
        float head = fract(t * (uMode == 2 ? .085 : .15) + uPhase * .25);
        float behind = fract(head - u + 1.0);
        float tail = exp(-behind * (uMode == 2 ? 26.0 : 12.0));
        core = tail * envelope(inner - baseWidth * .34, baseWidth * .3 + 1.2);
        plume = tail * envelope(inner - baseWidth * .55, baseWidth + 5.0);
        motion = tail;
        hueTravel = head;
    } else if (uMode == 3) { // Pulse: large, soft envelope on bass hits
        float width = baseWidth * (.4 + drive * 2.3);
        core = envelope(inner - width * .33, width * .4 + 1.4);
        plume = envelope(inner - width * .6, width * 1.15 + 6.0);
        motion = drive;
    } else if (uMode == 4) { // Chainsaw: staggered teeth advance with the motor, not static strokes
        float toothPhase = fract(u * 192.0 - t * (.4 + drive * 2.4));
        float tooth = smoothstep(.05, .29, toothPhase) * (1.0 - smoothstep(.47, .84, toothPhase));
        float bite = baseWidth * (.25 + tooth * (.8 + drive * 1.8));
        core = (1.0 - smoothstep(bite, bite + 1.4, inner)) * (.35 + .65 * tooth);
        plume = envelope(inner - bite * .4, bite + 3.0) * (.25 + drive);
        detail = tooth * drive * .65;
        motion = tooth;
    } else if (uMode == 5) { // Aurora: licensed simplex turbulence, not a sine-line drawing
        float3 domain = float3(u * 8.0, signedDistance * .012, t * .17);
        float low = noise3(domain);
        float fine = noise3(domain * 2.3 + float3(2.0, 4.0, 1.0));
        float crest = smoothstep(-.45, .65, low * .72 + fine * .28);
        float range = baseWidth * (.6 + crest * 2.1);
        core = envelope(inner - range * .65, range * .43 + 2.0) * (.55 + crest * .45);
        plume = envelope(inner - range * .8, range * 1.3 + 10.0);
        motion = crest;
    } else if (uMode == 7) { // Twin streams, counter-rotating
        float forward = exp(-fract(t * .10 - u + 1.0) * 22.0);
        float reverse = exp(-fract(-t * .13 - u + .5 + 2.0) * 22.0);
        float travel = max(forward, reverse);
        core = travel * envelope(inner - baseWidth * .25, baseWidth * .36 + 1.2);
        plume = travel * envelope(inner - baseWidth * .6, baseWidth + 5.0);
        motion = travel;
    } else if (uMode == 8) { // Sparks: beat-driven irregular electrical discharges
        float cell = floor(u * 72.0);
        float burst = hash11(cell + floor(t * 7.0) * 17.0);
        float centerSpark = hash11(cell + 91.0);
        float xDist = abs(fract(u * 72.0) - centerSpark);
        float hit = (1.0 - smoothstep(.0, .25, xDist)) *
            smoothstep(.47, .92, burst * (.25 + activity));
        float reach = baseWidth * (1.0 + 1.5 * burst);
        core = hit * envelope(inner - reach * .5, reach * .48 + 1.0);
        plume = hit * envelope(inner - reach * .5, reach * .9 + 3.0);
        detail = hit;
        motion = hit;
    } else if (uMode == 9) { // Pixel rush: stepped blocks with fine border bevel
        float cell = floor(u * 80.0 - t * 7.0);
        float grid = fract(u * 80.0 - t * 7.0);
        float gaps = smoothstep(.06, .12, grid) * (1.0 - smoothstep(.86, .95, grid));
        float height = (1.0 + floor(activity * 6.0)) * 4.4 * uThickness;
        float vertical = smoothstep(.03, .19, fract(inner / max(2.0, 4.4 * uThickness))) *
            (1.0 - smoothstep(.76, .96, fract(inner / max(2.0, 4.4 * uThickness))));
        core = gaps * vertical * (1.0 - smoothstep(height, height + 1.0, inner));
        plume = core * .45;
        motion = hash11(cell);
    } else if (uMode == 10) { // Neon circuit: continuous bright conductive tracks and propagating nodes
        float node = pow(max(0.0, sin(u * 75.398 - t * 4.0)), 10.0);
        core = envelope(inner - baseWidth * .22, 1.3 + node * 2.0);
        plume = envelope(inner - baseWidth * (.2 + node * .6), 4.0 + node * baseWidth);
        detail = node * (.3 + drive);
        motion = node;
    } else if (uMode == 11) { // Rainfall: thin liquid droplets moving inward
        float cell = floor(u * 56.0);
        float jitter = hash11(cell);
        float droplet = 1.0 - smoothstep(.015, .15, abs(fract(u * 56.0) - jitter));
        float fall = fract(t * (.35 + .3 * jitter) + jitter);
        float centerRain = fall * (4.0 + baseWidth * 3.2);
        core = droplet * envelope(inner - centerRain, 2.0 + activity * 3.0);
        plume = droplet * envelope(inner - centerRain, 7.0 + activity * 9.0);
        motion = fall;
    } else if (uMode == 12) { // Helix: two interleaving luminous ribbons
        float helix = sin(u * 100.53 - t * 3.0);
        float laneA = baseWidth * (.8 + helix * .7);
        float laneB = baseWidth * (.8 - helix * .7);
        core = max(envelope(inner - laneA, 2.0), envelope(inner - laneB, 2.0)) * (.45 + activity * .55);
        plume = max(envelope(inner - laneA, 7.5), envelope(inner - laneB, 7.5));
        motion = helix * .5 + .5;
    } else if (uMode == 13) { // Bloom: expanding round pulses distributed along the border
        float petals = pow(max(0.0, sin(u * 50.265 - t * 1.7)), 5.0);
        float scale = baseWidth * (.5 + drive * 2.0);
        core = petals * envelope(inner - scale * .5, 2.0 + scale * .2);
        plume = petals * envelope(inner - scale * .75, scale + 6.0);
        motion = petals;
    } else { // Prism: chromatic refraction bands slide under audio
        float spectral = sin(u * 144.5 + t * 2.3 + sin(u * 39.0)) * .5 + .5;
        float target = baseWidth * (.25 + spectral * 1.4);
        core = envelope(inner - target, 2.2 + activity * 2.5);
        plume = envelope(inner - target, 6.0 + activity * 8.0);
        detail = spectral * activity;
        motion = spectral;
        hueTravel = fract(u * 3.0 + t * .06 + spectral * .08);
    }

    // A real light distribution, not painted outline geometry. The particle field
    // attenuates smoothly in two dimensions and never paints an opaque screen.
    float exterior = envelope(outer, 3.5 + uGlow * 10.0);
    float edge = envelope(inner, 2.0 + uThickness * 1.3);
    float luminosity = (core * .72 + plume * .38 + detail * .17 +
        edge * (.05 + drive * .12));
    luminosity *= exterior;
    float alpha = clamp(luminosity * uBrightness, 0.0, .94);

    float palette = sin((hueTravel + motion * .13) * 6.283185) * .5 + .5;
    float3 primary = float3(uPrimary.rgb);
    float3 accent = float3(uAccent.rgb);
    float3 pigment = mix(primary, accent, smoothstep(.12, .9, palette));
    float whitening = clamp(core * (.13 + activity * .24) + detail * .35, 0.0, .75);
    pigment = mix(pigment, float3(1.0, 1.0, 1.0), whitening);
    pigment = clamp(pigment * (1.08 + .15 * activity), 0.0, 1.0);
    return half4(half3(pigment * alpha), half(alpha));
}
"""
    }
}
