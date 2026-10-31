package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.os.SystemClock
import android.util.Log
import android.view.View
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * True per-zone video Ambilight, not an FFT/audio color cycle.
 * Every zone receives a color sampled from the corresponding screen edge.
 */
internal class VideoAmbientView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val bounds = RectF()
    private val target = FloatArray(VideoColorSampler.ZONES * 3)
    private val current = FloatArray(VideoColorSampler.ZONES * 3)
    private var shader: RuntimeShader? = null
    private var error: String? = null
    private var lastUpdate = 0L

    var brightness = 1.0f
        set(value) { field = value.coerceIn(.2f, 1.0f); invalidate() }
    var glow = .8f
        set(value) { field = value.coerceIn(.0f, 1.0f); invalidate() }
    var smoothing = .55f
        set(value) { field = value.coerceIn(.05f, 1f) }
    var spread = 1f
        set(value) { field = value.coerceIn(.5f, 2f); invalidate() }

    val rendererStatus: String get() =
        if (error != null) "Video GPU failed: $error" else if (shader != null) "Video GPU active" else "Initializing GPU"

    fun updateColors(colors: IntArray) {
        if (colors.size != VideoColorSampler.ZONES) return
        for (i in colors.indices) {
            val offset = i * 3
            target[offset] = Color.red(colors[i]) / 255f
            target[offset + 1] = Color.green(colors[i]) / 255f
            target[offset + 2] = Color.blue(colors[i]) / 255f
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || !canvas.isHardwareAccelerated || error != null) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastUpdate == 0L) .033f else ((now - lastUpdate) / 1000f).coerceIn(0f, .09f)
        lastUpdate = now
        val factor = 1f - exp(-dt * (18f - 15f * smoothing))
        for (i in current.indices) {
            current[i] += (target[i] - current[i]) * factor
        }
        try {
            val gpu = shader ?: RuntimeShader(SOURCE).also { shader = it }
            val density = resources.displayMetrics.density
            val insets = (4f * density).coerceAtLeast(1f)
            bounds.set(insets, insets, width - insets, height - insets)
            gpu.setFloatUniform("frame", bounds.left, bounds.top, bounds.right, bounds.bottom)
            gpu.setFloatUniform("corner", min(bounds.width(), bounds.height()) * .042f)
            gpu.setFloatUniform("density", density)
            gpu.setFloatUniform("brightness", brightness)
            gpu.setFloatUniform("glow", glow)
            gpu.setFloatUniform("spread", spread)
            for (i in 0 until VideoColorSampler.ZONES) {
                val off = i * 3
                gpu.setFloatUniform("zone$i", current[off], current[off + 1], current[off + 2])
            }
            paint.shader = gpu
            paint.alpha = 255
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
        } catch (failure: RuntimeException) {
            error = failure.message?.take(160) ?: failure.javaClass.simpleName
            Log.e("HALO-video", "Video shader failed", failure)
            paint.shader = null
            VideoAmbientRuntime.publish(false, "Video lighting GPU error: $error")
            return
        }
        if (isAttachedToWindow && visibility == VISIBLE) postInvalidateDelayed(33L)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lastUpdate = 0L
    }

    internal fun compileForTest() = RuntimeShader(SOURCE)

    private companion object {
        private val SOURCE = """
            uniform float4 frame;
            uniform float corner;
            uniform float density;
            uniform float brightness;
            uniform float glow;
            uniform float spread;
            uniform float3 zone0;
uniform float3 zone1;
uniform float3 zone2;
uniform float3 zone3;
uniform float3 zone4;
uniform float3 zone5;
uniform float3 zone6;
uniform float3 zone7;
uniform float3 zone8;
uniform float3 zone9;
uniform float3 zone10;
uniform float3 zone11;
uniform float3 zone12;
uniform float3 zone13;
uniform float3 zone14;
uniform float3 zone15;
            const float PI = 3.14159265;
            const float TAU = 6.28318530;

            float3 sourceColor(float index) {
    if (index < 0.5) return zone0;
    if (index < 1.5) return zone1;
    if (index < 2.5) return zone2;
    if (index < 3.5) return zone3;
    if (index < 4.5) return zone4;
    if (index < 5.5) return zone5;
    if (index < 6.5) return zone6;
    if (index < 7.5) return zone7;
    if (index < 8.5) return zone8;
    if (index < 9.5) return zone9;
    if (index < 10.5) return zone10;
    if (index < 11.5) return zone11;
    if (index < 12.5) return zone12;
    if (index < 13.5) return zone13;
    if (index < 14.5) return zone14;
                return zone15;
            }
            float screenDistance(float2 p) {
                float2 c = .5 * (frame.xy + frame.zw);
                float2 e = max((frame.zw - frame.xy) * .5, float2(1.0));
                float r = max(1.0, min(corner, min(e.x, e.y)));
                float2 q = abs(p-c) - (e-r);
                return length(max(q, float2(0.0))) + min(max(q.x,q.y),0.0) - r;
            }
            float perimeterPosition(float2 p) {
                float2 c = .5*(frame.xy+frame.zw);
                float2 e = max((frame.zw-frame.xy)*.5,float2(1.0));
                float r = max(1.0,min(corner,min(e.x,e.y)));
                float2 a = max(e-r,float2(.001));
                float h = 2.0*a.x, v=2.0*a.y, arc=.5*PI*r;
                float perimeter = 2.0*(h+v)+4.0*arc;
                float2 q=p-c, b=abs(q);
                float s=0.0;
                if(b.x>a.x && b.y>a.y) {
                    if(q.x>=0.0 && q.y<0.0) {
                        float ang=atan(q.y+a.y,q.x-a.x);
                        s=h+(ang+.5*PI)*r;
                    } else if(q.x>=0.0) {
                        float ang=atan(q.y-a.y,q.x-a.x);
                        s=h+arc+v+ang*r;
                    } else if(q.y>=0.0) {
                        float ang=atan(q.y-a.y,q.x+a.x);
                        s=h+arc+v+arc+h+(ang-.5*PI)*r;
                    } else {
                        float ang=atan(q.y+a.y,q.x+a.x);
                        s=h+arc+v+arc+h+arc+v+(ang+PI)*r;
                    }
                } else if(b.y>=a.y) {
                    if(q.y<0.0) s=q.x+a.x;
                    else s=h+arc+v+arc+(a.x-q.x);
                } else {
                    if(q.x>=0.0) s=h+arc+(q.y+a.y);
                    else s=h+arc+v+arc+h+arc+(a.y-q.y);
                }
                return fract(s/max(perimeter,1.0));
            }
            float3 sampleZone(float u) {
                float scaled = fract(u)*16.0;
                float idx = floor(scaled);
                float mixValue=fract(scaled);
                mixValue=mixValue*mixValue*(3.0-2.0*mixValue);
                float3 firstColor = sourceColor(idx);
                float3 secondColor = sourceColor(mod(idx+1.0,16.0));
                // Gamma-correct RGB mixing maintains perceived brightness at scene cuts.
                float3 linearA=pow(clamp(firstColor,0.0,1.0),float3(2.2));
                float3 linearB=pow(clamp(secondColor,0.0,1.0),float3(2.2));
                return pow(mix(linearA,linearB,mixValue),float3(.45454545));
            }
            half4 main(float2 position) {
                float distance=screenDistance(position);
                float widthPx=density*spread;
                if(abs(distance)>110.0*widthPx) return half4(0.0);
                float3 color=sampleZone(perimeterPosition(position));
                float light=max(color.r,max(color.g,color.b));
                if(light<.012) return half4(0.0);
                float core=exp(-pow((distance+3.0*widthPx)/(5.0*widthPx),2.0));
                float bloom=exp(-pow(distance/(23.0*widthPx),2.0));
                float a=clamp((core*.64 + bloom*(.10 + glow*.24))
                    *brightness*(.38+.62*light),0.0,.88);
                return half4(half3(clamp(color,0.0,1.0)*a),half(a));
            }
        """.trimIndent()
    }
}
