package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import kotlin.math.max

/** Full-frame, locally rendered painting styles. No saved screen captures. */
enum class PaintedStyle(val label: String) {
    OIL("Oil / Impasto"),
    WATERCOLOR("Watercolour"),
    INK("Ink & paint")
}

data class PaintedScreenPreferences(
    val style: PaintedStyle = PaintedStyle.OIL,
    val strength: Float = .96f,
    val brushSize: Float = 1.12f,
    val relief: Float = 1f
) {
    fun save(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString("style", style.name)
            .putFloat("strength", strength.coerceIn(.35f, 1f))
            .putFloat("brush_size", brushSize.coerceIn(.65f, 2.3f))
            .putFloat("relief", relief.coerceIn(.15f, 1.6f))
            .apply()
    }

    companion object {
        private const val STORE = "halo_painted_screen"

        fun read(context: Context): PaintedScreenPreferences {
            val saved = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val style = PaintedStyle.entries.firstOrNull {
                it.name == saved.getString("style", PaintedStyle.OIL.name)
            } ?: PaintedStyle.OIL
            fun readValue(key: String, fallback: Float, lower: Float, upper: Float): Float {
                val value = saved.getFloat(key, fallback)
                return if (value.isFinite()) value.coerceIn(lower, upper) else fallback
            }
            return PaintedScreenPreferences(
                style = style,
                strength = readValue("strength", .96f, .35f, 1f),
                brushSize = readValue("brush_size", 1.12f, .65f, 2.3f),
                relief = readValue("relief", 1f, .15f, 1.6f)
            )
        }
    }
}

/** Copies screen pixels respecting Image.Plane padding and pixel stride. */
internal object PaintedFrameDecoder {
    fun decode(
        buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int
    ): Bitmap? {
        if (width !in 16..1024 || height !in 16..2048 ||
            pixelStride < 4 || rowStride < width * pixelStride) return null
        val end = (height - 1L) * rowStride + (width - 1L) * pixelStride + 3L
        if (end >= buffer.limit()) return null

        val pixels = IntArray(width * height)
        var populated = 0
        var luminance = 0L
        for (y in 0 until height) {
            val offset = y * rowStride
            for (x in 0 until width) {
                val i = offset + x * pixelStride
                val r = buffer.get(i).toInt() and 255
                val g = buffer.get(i + 1).toInt() and 255
                val b = buffer.get(i + 2).toInt() and 255
                pixels[y * width + x] = -0x1000000 or (r shl 16) or (g shl 8) or b
                if ((x and 7) == 0 && (y and 7) == 0) {
                    luminance += max(r, max(g, b)).toLong()
                    populated++
                }
            }
        }
        // Secure / DRM windows can return black. Never leave a stale screenshot
        // painted over another application when the capture becomes restricted.
        if (populated == 0 || luminance < populated * 5L) return null
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}

/** RuntimeShader-based palette-knife impasto. Touch passes through to the app. */
internal class PaintedScreenView(context: Context) : View(context) {
    private var currentFrame: Bitmap? = null
    private var preferences: PaintedScreenPreferences = PaintedScreenPreferences.read(context)
    private val output = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var shader: RuntimeShader? = null
    private var shaderUnavailable = false

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun configure(value: PaintedScreenPreferences) {
        preferences = value
        invalidate()
    }

    fun setFrame(frame: Bitmap?) {
        val old = currentFrame
        currentFrame = frame
        invalidate()
        // RenderThread may be consuming the prior bitmap in a BitmapShader.
        // A short deferred recycle avoids use-after-recycle on GPU frames.
        if (old != null && old !== frame) {
            postDelayed({ if (!old.isRecycled) old.recycle() }, 1000L)
        }
    }

    fun clear() {
        setFrame(null)
        output.shader = null
        shader = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = currentFrame ?: return
        if (width <= 0 || height <= 0) return

        // Missing hardware acceleration fails open; never draw an unprocessed,
        // opaque screenshot over the UI.
        if (Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated || shaderUnavailable)
            return

        matrix.reset()
        matrix.setScale(width.toFloat() / frame.width, height.toFloat() / frame.height)
        val source = BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        source.setLocalMatrix(matrix)
        try {
            val effect = shader ?: RuntimeShader(SOURCE).also { shader = it }
            effect.setInputShader("screen", source)
            effect.setFloatUniform("resolution", width.toFloat(), height.toFloat())
            effect.setFloatUniform("brush", resources.displayMetrics.density * 25f *
                preferences.brushSize)
            effect.setFloatUniform("strength", preferences.strength)
            effect.setFloatUniform("relief", preferences.relief)
            effect.setIntUniform("style", preferences.style.ordinal)
            output.shader = effect
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), output)
            output.shader = null
        } catch (failure: RuntimeException) {
            shaderUnavailable = true
            output.shader = null
            Log.e("HALO", "Impasto GPU shader unavailable", failure)
        }
    }

    companion object {
        @RequiresApi(33)
        internal fun compileForTest(): RuntimeShader = RuntimeShader(SOURCE)

        // Broad, solid pigment patches and visible directional impasto ridges:
        //   1. A coarse colour underpainting covers the *entire* screen.
        //   2. Large palette-knife marks follow local image gradients.
        //   3. Offset, smaller marks break up the tessellation without stippling.
        //   4. Raised paint receives a consistent grazing light, rim shadow
        //      and parallel bristle ridges. Stamps are anchored in screen space
        //      rather than time so stationary content does not shimmer.
        private val SOURCE = """
            uniform shader screen;
            uniform float2 resolution;
            uniform float brush;
            uniform float strength;
            uniform float relief;
            uniform int style;

            float hash21(float2 p) {
                // Inexpensive polynomial hash instead of a per-pixel sine.
                // RuntimeShader paints the full screen, so this matters on A56.
                p=fract(p*float2(.1031,.1030));
                p+=dot(p,p.yx+33.33);
                return fract((p.x+p.y)*p.x);
            }

            float2 clipPx(float2 p) {
                return clamp(p,float2(0.5),resolution-float2(0.5));
            }

            float3 imageColor(float2 p) {
                return float3(screen.eval(clipPx(p)).rgb);
            }

            float lum(float3 p) {
                return dot(p,float3(.2126,.7152,.0722));
            }

            float3 pigment(float3 c,float steps) {
                // A painter selects colour mixes, not every RGB value from
                // the camera. Quantisation is gentle enough to retain shape.
                return floor(clamp(c,0.0,1.0)*steps+.5)/steps;
            }

            float2 stampCenter(float2 id,float size,float2 offset) {
                float2 jitter=float2(hash21(id+float2(13.1,7.7)),
                                     hash21(id+float2(71.4,23.6)))-.5;
                return (id+.5-offset)*size+jitter*size*.28;
            }

            // Solid paint deposit with irregular palette-knife edges and
            // shallow physical relief. rgb stores the lit pigment, a is the
            // brush's occupied coverage against the underlying colour.
            float4 stroke(float2 p,float size,float2 offset,float2 flow,float weight) {
                float2 id=floor(p/size+offset);
                float seed=hash21(id+offset*17.0);
                float2 center=stampCenter(id,size,offset);
                float turn=(hash21(id+float2(2.7,9.3))-.5)*.68;
                float2 alongAxis=normalize(flow+float2(-flow.y,flow.x)*turn);
                float2 acrossAxis=float2(-alongAxis.y,alongAxis.x);
                float2 q=p-center;
                float along=dot(q,alongAxis);
                float across=dot(q,acrossAxis);

                float halfLength=size*(.69+.35*hash21(id+float2(7.2,2.1)));
                float halfWidth=size*(.34+.12*hash21(id+float2(3.9,8.0)));
                if (style==1) halfWidth*=1.15;
                float t=clamp(abs(along)/max(halfLength,.01),0.0,1.0);
                float taper=1.0-.48*t*t;
                float jagged=sin(along*.23+seed*12.0)*.075+
                             sin(along*.48+across*.10+seed*19.0)*.038;
                float span=max(.1,halfWidth*taper*(1.0+jagged));
                float radial=max(abs(along)/max(halfLength,.01),abs(across)/span);
                float uneven=sin(along*.37+seed*8.0)*.026+
                             sin(across*.32+seed*13.0)*.022;
                float boundary=radial+uneven;
                float mask=1.0-smoothstep(.84,1.03,boundary);

                // Gradients across the physical raised ridges catch light;
                // a slight dark rim suggests thick paint piled over paint.
                float grooves=sin(across*.44+along*.055+seed*16.0);
                grooves+=.42*sin(across*1.13-along*.12+seed*29.0);
                float smallFiber=hash21(floor(p*.55)+id*.13)-.5;
                float grazing=(acrossAxis.x*(-.63)+acrossAxis.y*(-.77));
                float ridgeLight=grooves*grazing*.19+
                                 smallFiber*.055+
                                 (across/max(span,.01))*grazing*.11;
                float rim=1.0-smoothstep(.74,.98,boundary);
                float bevelShadow=(1.0-rim)*mask*.16;
                float wetHighlight=max(0.0,grooves*grazing)*.055;
                float depthFactor=style==1?.13:style==2?.62:1.0;

                float3 color=pigment(imageColor(center),style==2?7.0:11.0);
                color=clamp(color*(.96+weight*seed*.065)+
                    float3(1.0)*(.08+ridgeLight*.95+wetHighlight)*relief*depthFactor-
                    float3(bevelShadow*relief*depthFactor),0.0,1.0);
                if (style==1) color=mix(color,float3(.94,.90,.84),.085);
                return float4(color,mask);
            }

            half4 main(float2 xy) {
                float2 p=clipPx(xy);
                float big=max(14.0,brush);
                float2 coarseId=floor(p/big);
                float2 center=stampCenter(coarseId,big,float2(0.0));
                float radius=big*.42;
                float gx=lum(imageColor(center+float2(radius,0.0)))-
                         lum(imageColor(center-float2(radius,0.0)));
                float gy=lum(imageColor(center+float2(0.0,radius)))-
                         lum(imageColor(center-float2(0.0,radius)));
                float2 flow=float2(-gy,gx);
                float2 fallback=normalize(float2(
                    hash21(coarseId+float2(17.3,4.6))-.5,
                    hash21(coarseId+float2(5.1,19.8))-.5)+float2(.001,0.0));
                flow=normalize(flow+fallback*max(.13-length(flow),0.0));

                // A fully covered underpainting is essential. The old effect
                // had mostly transparent streaks over the raw screen and did
                // not resemble the thick oil paint in the reference.
                float3 original=imageColor(p);
                float2 washId=floor(p/(big*.62)+float2(.21,.47));
                float2 washCenter=stampCenter(washId,big*.62,float2(.21,.47));
                float3 base=pigment(imageColor(washCenter),style==2?8.0:12.0);
                float3 coarse=pigment(imageColor(center),style==2?7.0:10.0);
                float3 painted=mix(base,coarse,.46);

                float4 large=stroke(p,big,float2(0.0),flow,1.0);
                float4 detail=stroke(p,big*.54,float2(.36,.58),flow,.55);

                painted=mix(painted,large.rgb,large.a*.97);
                painted=mix(painted,detail.rgb,detail.a*(style==1?.37:.63));

                // Brush-body bands and specks remain visible even in the
                // underpaint, giving the canvas a thick uneven paint surface.
                float paper=hash21(floor(p*.67))-.5;
                float canvasGrooves=sin(p.x*.27+sin(p.y*.16)*1.1);
                float texture=(paper*.043+canvasGrooves*.021)*relief;
                if(style==0) painted*=.99+texture;
                if(style==1) painted=mix(painted,float3(.92,.86,.75),.11+paper*.03);
                if(style==2) {
                    float contrast=length(float2(gx,gy));
                    painted*=1.0-smoothstep(.10,.37,contrast)*.16;
                }

                painted=clamp(painted,0.0,1.0);
                float3 result=mix(original,painted,strength);
                // Fully opaque painted image, including between the strokes.
                return half4(half3(result),1.0);
            }
        """.trimIndent()
    }
}
