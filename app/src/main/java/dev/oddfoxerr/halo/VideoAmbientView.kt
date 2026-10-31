package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

data class VideoAmbientStatus(
    val active: Boolean = false,
    val message: String = "Video Ambilight is off",
    val zones: List<Int> = List(VideoColorSampler.ZONE_COUNT) { Color.BLACK },
    val frameCount: Long = 0
)

/** Live colors and diagnostic status only; never stores images or screen content. */
object VideoAmbientRuntime {
    private val stream = MutableStateFlow(VideoAmbientStatus())
    val state = stream.asStateFlow()
    fun update(status: VideoAmbientStatus) { stream.value = status }
}

class VideoAmbientView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val region = RectF()
    private val clipping = Path()
    private var engine: RuntimeShader? = null
    private var palette: BitmapShader? = null
    private var current = IntArray(VideoColorSampler.ZONE_COUNT) { Color.BLACK }
    private var settings = MusicConfiguration.read(context)
    private var diagnostic = ""
    val status get() = diagnostic

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun configure(value: MusicConfiguration) {
        settings = value
        invalidate()
    }

    fun updateZones(zones: IntArray) {
        if (zones.size != VideoColorSampler.ZONE_COUNT) return
        current = zones.copyOf()
        val strip = IntArray(VideoColorSampler.ZONE_COUNT + 1) {
            current[it % VideoColorSampler.ZONE_COUNT]
        }
        // Sampling a 33-pixel linear strip interpolates smoothly between
        // neighboring video zones, including from the final zone to the first.
        val bitmap = Bitmap.createBitmap(strip, strip.size, 1, Bitmap.Config.ARGB_8888)
        palette = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width < 2 || height < 2 || !canvas.isHardwareAccelerated) return
        val strip = palette ?: return
        try {
            val shader = engine ?: RuntimeShader(SOURCE).also { engine = it }
            val density = resources.displayMetrics.density
            region.set(
                settings.leftInset * density, settings.topInset * density,
                width - settings.rightInset * density, height - settings.bottomInset * density
            )
            if (region.width() <= 2f || region.height() <= 2f) return
            val corner = min(settings.corner * density, min(region.width(), region.height()) / 2f)
            shader.setFloatUniform("frame", region.left, region.top, region.right, region.bottom)
            shader.setFloatUniform("radius", corner)
            shader.setFloatUniform("thickness", (settings.thickness * density).coerceIn(1f, 22f))
            shader.setFloatUniform("glow", settings.glow.coerceIn(0f, 1f))
            shader.setFloatUniform("brightness", settings.brightness.coerceIn(.2f, 1f))
            shader.setInputShader("videoColors", strip)
            clipping.reset()
            val reach = 62f * density
            if (settings.drawTop) clipping.addRect(0f, 0f, width.toFloat(),
                region.top + reach, Path.Direction.CW)
            if (settings.drawRight) clipping.addRect(region.right - reach, 0f,
                width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (settings.drawBottom) clipping.addRect(0f, region.bottom - reach,
                width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (settings.drawLeft) clipping.addRect(0f, 0f, region.left + reach,
                height.toFloat(), Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipping)
            paint.shader = shader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            canvas.restore()
            diagnostic = "GPU video colors active"
        } catch (error: RuntimeException) {
            diagnostic = error.message ?: "Video shader failed"
            Log.e("HALO", "Video Ambilight AGSL error", error)
            paint.shader = null
        }
    }

    companion object {
        /** Test this on the Android emulator: AGSL is compiled at runtime, not by Gradle. */
        internal fun compileForTest() = RuntimeShader(SOURCE)

        private val SOURCE = """
            uniform float4 frame;
            uniform float radius;
            uniform float thickness;
            uniform float glow;
            uniform float brightness;
            uniform shader videoColors;

            float roundedDistance(float2 p) {
                float2 center = (frame.xy + frame.zw) * 0.5;
                float2 extent = max((frame.zw-frame.xy)*0.5-radius,float2(1.0));
                float2 q = abs(p-center)-extent;
                return length(max(q,float2(0.0)))+min(max(q.x,q.y),0.0)-radius;
            }

            // Clockwise: eight zones on each side, linear interpolation between
            // sampled video pixels; corners share the same consecutive zone ordering.
            float edgeFraction(float2 p) {
                float l=frame.x, t=frame.y, r=frame.z, b=frame.w;
                float top=abs(p.y-t), right=abs(p.x-r);
                float bottom=abs(p.y-b), left=abs(p.x-l);
                float u=0.0;
                if(top<=right && top<=bottom && top<=left)
                    u=.25*clamp((p.x-l)/max(1.0,r-l),0.0,1.0);
                else if(right<=bottom && right<=left)
                    u=.25+.25*clamp((p.y-t)/max(1.0,b-t),0.0,1.0);
                else if(bottom<=left)
                    u=.50+.25*clamp((r-p.x)/max(1.0,r-l),0.0,1.0);
                else u=.75+.25*clamp((b-p.y)/max(1.0,b-t),0.0,1.0);
                return u;
            }

            half4 main(float2 p) {
                float d=roundedDistance(p);
                if(abs(d)>160.0) return half4(0.0);
                float u=edgeFraction(p);
                half3 captured=videoColors.eval(float2(.5+u*32.0,.5)).rgb;
                float3 rgb=float3(captured);
                float luma=dot(rgb,float3(.2126,.7152,.0722));
                float width=max(1.5,thickness*1.7);
                float crisp=exp(-pow(d/width,2.0)*1.75);
                float halo=exp(-pow(d/(width*4.0+12.0),2.0)*2.1);
                float a=clamp((crisp*.72+halo*(.08+.18*glow))
                    *brightness*clamp(luma*2.8,0.0,1.0),0.0,.84);
                // Explicit premultiplication prevents black fringes on transparent overlay.
                return half4(half3(rgb*a),half(a));
            }
        """.trimIndent()
    }
}
