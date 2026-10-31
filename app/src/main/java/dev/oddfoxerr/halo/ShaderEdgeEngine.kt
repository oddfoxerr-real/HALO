package dev.oddfoxerr.halo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.max

/**
 * Native AGSL (GPU) design engine. Unlike the previous Canvas pipeline, light and geometry
 * are generated together in one procedural field with transparent pixels inside the display.
 * Built from scratch using public Android RuntimeShader APIs, not copied shaders/assets.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class ShaderEdgeEngine {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private var shader: RuntimeShader? = null
    private var failed = false

    fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        bounds: RectF,
        radius: Float,
        mode: EdgeMode,
        bands: FloatArray,
        bass: Float,
        phase: Float,
        seconds: Float,
        thickness: Float,
        spread: Float,
        glow: Float,
        brightness: Float,
        primary: Int,
        secondary: Int
    ): Boolean {
        if (failed || width <= 0 || height <= 0) return false
        try {
            val runtime = shader ?: RuntimeShader(SOURCE).also { shader = it }
            runtime.setFloatUniform("resolution", width.toFloat(), height.toFloat())
            runtime.setFloatUniform("frame", bounds.left, bounds.top, bounds.right, bounds.bottom)
            runtime.setFloatUniform("radius", radius)
            runtime.setFloatUniform("seconds", seconds)
            runtime.setFloatUniform("phase", phase)
            runtime.setFloatUniform("bass", bass.coerceIn(0f, 1f))
            runtime.setFloatUniform("thickness", thickness)
            runtime.setFloatUniform("spread", spread)
            runtime.setFloatUniform("glow", glow)
            runtime.setFloatUniform("brightness", brightness)
            runtime.setIntUniform("design", mode.ordinal)
            val frequencies = FloatArray(4)
            for (i in bands.indices) {
                val index = (i * 4 / max(1, bands.size)).coerceIn(0, 3)
                frequencies[index] = max(frequencies[index], bands[i].coerceIn(0f, 1f))
            }
            runtime.setFloatUniform("audio", frequencies)
            runtime.setFloatUniform("first", Color.red(primary) / 255f,
                Color.green(primary) / 255f, Color.blue(primary) / 255f)
            runtime.setFloatUniform("second", Color.red(secondary) / 255f,
                Color.green(secondary) / 255f, Color.blue(secondary) / 255f)
            paint.shader = runtime
            paint.alpha = 255
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            return true
        } catch (_: RuntimeException) {
            // Shader compile errors or incompatible GPU drivers use the existing native fallback.
            failed = true
            paint.shader = null
            return false
        }
    }

    private companion object {
        private val SOURCE = """
            uniform float2 resolution;
            uniform float4 frame;
            uniform float radius;
            uniform float seconds;
            uniform float phase;
            uniform float bass;
            uniform float4 audio;
            uniform float thickness;
            uniform float spread;
            uniform float glow;
            uniform float brightness;
            uniform float3 first;
            uniform float3 second;
            uniform int design;

            float hash(float2 p) {
                return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
            }

            float noise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                float2 u = f*f*(3.0-2.0*f);
                float a=hash(i), b=hash(i+float2(1,0));
                float c=hash(i+float2(0,1)), d=hash(i+float2(1,1));
                return mix(mix(a,b,u.x),mix(c,d,u.x),u.y);
            }

            float roundedDistance(float2 p) {
                float2 center = 0.5 * (frame.xy + frame.zw);
                float2 extent = max((frame.zw - frame.xy)*0.5 - radius, float2(1.0));
                float2 q = abs(p-center) - extent;
                return length(max(q, float2(0.0))) + min(max(q.x,q.y),0.0) - radius;
            }

            float harmonic(float x) {
                float4 a = audio;
                float low = mix(a.x,a.y,smoothstep(0.0,0.5,x));
                float high = mix(a.z,a.w,smoothstep(0.5,1.0,x));
                return mix(low,high,smoothstep(0.20,0.80,x));
            }

            half4 main(float2 px) {
                float2 uv = px / max(resolution, float2(1.0));
                float2 center = 0.5*(frame.xy+frame.zw);
                float2 normalized = (px-center)/max((frame.zw-frame.xy)*0.5,float2(1.0));
                float perimeterPos = fract(atan(normalized.y,normalized.x)/6.2831853 + 0.5);
                float d = roundedDistance(px);
                float beat = clamp(bass,0.0,1.0);
                float movement = seconds * 0.30;
                float energy = harmonic(perimeterPos);
                float swirl = noise(float2(perimeterPos*14.0-seconds*.55,seconds*.24));
                float detail = noise(float2(perimeterPos*43.0+seconds*.20,seconds*.40));
                float grain = swirl*0.76+detail*0.24;
                float front = 1.8 * thickness;
                float size = (5.0 + 12.0*energy + 7.0*beat) * spread * thickness;

                // Distinct motion systems, not a uniform stroke with different names.
                if (design == 0) { // Silk - smooth folded satin wave
                    size *= .55 + .65 * grain;
                    d += sin(perimeterPos*46.0-seconds*3.1)*energy*6.0*spread;
                } else if (design == 1) { // Spectrum - smoothly sampled spectral columns
                    size *= .20 + 1.20 * energy;
                    d += sin(perimeterPos*95.0)*energy*2.6;
                } else if (design == 2 || design == 6) { // Orbit / Comet - travelling energy
                    float delta = abs(fract(perimeterPos - movement + 0.5)-0.5);
                    size *= .08 + 2.6 * exp(-delta*delta*220.0) * (.45+beat);
                } else if (design == 3) { // Pulse - expanding interference
                    d += sin(d*.16 - seconds*7.0)*beat*6.0;
                    size *= .45 + 1.25*beat;
                } else if (design == 4) { // Chain - forged rhythmic teeth
                    size *= .5 + .5 * smoothstep(.35,.8,sin(perimeterPos*230.0-seconds*9.0));
                } else if (design == 5) { // Aurora - cloth-like drifting spectral curtains
                    size *= .6 + grain*1.5;
                    d += sin(perimeterPos*19.0-seconds*.8)*size*.35;
                } else if (design == 7) { // Twin streams
                    float path1=exp(-pow(abs(fract(perimeterPos-movement+0.5)-0.5)*17.0,2.0));
                    float path2=exp(-pow(abs(fract(perimeterPos+movement+0.5)-0.5)*17.0,2.0));
                    size *= .08 + (path1+path2)*1.9;
                } else if (design == 8) { // Sparks - scattered electrical crackles
                    size *= .10 + pow(detail,7.0)*3.5*max(energy,beat);
                } else if (design == 9) { // Pixels - discrete fluorescent mosaic
                    size *= .18+floor(energy*8.0)*.14;
                } else if (design == 10) { // Neon circuit
                    size *= .44+step(.70,noise(float2(perimeterPos*34.0,seconds*.18)))*1.1;
                } else if (design == 11) { // Rainfall
                    size *= .3 + pow(grain,3.0)*1.6;
                    d += sin(perimeterPos*83.0-seconds*7.0)*energy*5.0;
                } else if (design == 12) { // Helix
                    d += sin(perimeterPos*70.0-seconds*4.0)*size*.42;
                    size *= .55+energy*.5;
                } else if (design == 13) { // Bloom
                    size *= .24+beat*1.9;
                    d += sin(perimeterPos*32.0-seconds*2.0)*energy*7.0;
                } else { // Prism - refractive facets
                    size *= .43 + abs(sin(perimeterPos*38.0-seconds*.8))*.8;
                }

                // Procedural volume and specular caustics:
                // transparent interior, flowing colored body, sharp white refraction.
                float mask = exp(-pow(max(abs(d)-front,0.0)/max(1.0,size),2.0)*2.6);
                float core = exp(-pow(d/max(1.0,front),2.0)*2.5);
                float halo = exp(-pow(d/max(2.0,size*2.8),2.0)*2.2)*(.05+.19*glow);
                float refraction = pow(max(0.0,sin(perimeterPos*37.0 + movement*5.0 + grain*2.1)),14.0);
                float highlight = core*(.24+.49*energy+.18*refraction);
                float chroma = fract(perimeterPos*1.9-seconds*.05+grain*.2);
                float3 color = mix(first,second,smoothstep(.12,.88,chroma));
                color += first * exp(-pow((d+size*.20)/max(1.0,size*.45),2.0))*.19;
                color = mix(color,float3(1.0),clamp(highlight,0.0,.55));
                float alpha = clamp((mask*.52 + core*.48 + halo)*brightness,0.0,.94);
                // Only the nearby edge is illuminated; screen center stays transparent.
                alpha *= 1.0 - smoothstep(size*2.5+9.0,size*4.5+22.0,abs(d));
                return half4(half3(color)*half(alpha), half(alpha));
            }
        """.trimIndent()
    }
}
