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
            // Pixel-grid sized colour samples caused the previous mosaic look.
            // Brush size now controls only stroke geometry; pigment always comes
            // from a continuous, edge-aware sample of the source image.
            effect.setFloatUniform("brush", resources.displayMetrics.density * 7f *
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

        // Realtime Android AGSL shader. Unlike a painterly pixelation filter,
        // this NEVER quantizes coordinates or source colours into tile cells.
        // The source is filtered along the local edge tangent (edge-aware
        // brush direction), while overlapping brush-shape masks add impasto
        // grooves and grazing-light relief. No Blender/GPL code or assets are
        // bundled: the effect is implemented natively for Android's GPU.
        private val SOURCE = """
            uniform shader screen;
            uniform float2 resolution;
            uniform float brush;
            uniform float strength;
            uniform float relief;
            uniform int style;

            float hash21(float2 p) {
                p=fract(p*float2(.1031,.1030));
                p+=dot(p,p.yx+33.33);
                return fract((p.x+p.y)*p.x);
            }

            float2 safePx(float2 p) {
                return clamp(p,float2(.5),resolution-float2(.5));
            }

            float3 img(float2 p) {
                return float3(screen.eval(safePx(p)).rgb);
            }

            float lightness(float3 c) {
                return dot(c,float3(.2126,.7152,.0722));
            }

            float2 edgeFlow(float2 p,float radius) {
                float gx=lightness(img(p+float2(radius,0.0)))-
                         lightness(img(p-float2(radius,0.0)));
                float gy=lightness(img(p+float2(0.0,radius)))-
                         lightness(img(p-float2(0.0,radius)));
                // In detailed regions, flow runs parallel to image edges.
                // On flat colour areas, use one stable diagonal direction.
                float2 tangent=float2(-gy,gx);
                float magnitude=length(tangent);
                return normalize(tangent+float2(.7,.25)*max(.08-magnitude,0.0));
            }

            float samePigment(float3 a,float3 b) {
                float3 d=a-b;
                return 1.0/(1.0+34.0*dot(d,d));
            }

            // A continuous stroke-colour field: samples move ALONG contours
            // and do not cross strong edges. Crucially, no pixel / cell
            // centre ever becomes a flat-colour tile.
            float3 wetUnderpaint(float2 p,float2 flow,float radius,float3 original) {
                float3 c1=img(p+flow*radius);
                float3 c2=img(p-flow*radius);
                float3 c3=img(p+flow*radius*.42);
                float3 c4=img(p-flow*radius*.42);
                float w1=samePigment(original,c1)*.52;
                float w2=samePigment(original,c2)*.52;
                float w3=samePigment(original,c3)*.85;
                float w4=samePigment(original,c4)*.85;
                float sum=1.45+w1+w2+w3+w4;
                return (original*1.45+c1*w1+c2*w2+c3*w3+c4*w4)/sum;
            }

            // A 2D textured brush footprint; overlap comes from two
            // independent staggered layers. The brush mask affects height
            // and colour REFLECTION, not whether the underlying image exists.
            // This prevents mosaic-like hard boundaries and colour blocking.
            float3 brushMark(float2 p,float2 flow,float width,float2 offset) {
                float2 grid=floor(p/width+offset);
                float seed=hash21(grid+offset*13.0);
                float2 jitter=float2(hash21(grid+float2(8.1,3.7)),
                                     hash21(grid+float2(4.3,7.9)))-.5;
                float2 center=(grid+.5-offset)*width+jitter*width*.28;
                float angle=(hash21(grid+float2(21.8,7.6))-.5)*.65;
                float2 axis=normalize(flow+float2(-flow.y,flow.x)*angle);
                float2 normal=float2(-axis.y,axis.x);
                float2 local=p-center;
                float along=dot(local,axis);
                float side=dot(local,normal);
                float halfLong=width*(.66+.25*hash21(grid+float2(3.7,9.4)));
                float halfWide=width*(style==1?.40:.32);
                float tapered=1.0-.55*pow(clamp(abs(along)/halfLong,0.0,1.0),1.6);
                float irregular=sin(along*.33+seed*12.0)*.074+
                                 sin(along*.71+side*.15+seed*5.0)*.053;
                float contour=max(abs(along)/halfLong,
                                  abs(side)/(halfWide*tapered*(1.0+irregular)));
                float footprint=1.0-smoothstep(.79,1.06,contour);

                // Parallel longitudinal grooves, broken bristle edges and
                // small palette-knife highlights imply a raised oil film.
                float bristle=sin(side*1.75+along*.095+seed*15.0);
                float finer=sin(side*4.5-along*.13+seed*7.0);
                float grain=hash21(floor(p*.95)+grid*.19)-.5;
                float fibers=bristle*.68+finer*.23+grain*.09;
                float highSide=normal.x*(-.59)+normal.y*(-.81);
                float bevel=(smoothstep(.72,.92,contour)-
                             smoothstep(.92,1.05,contour))*footprint;
                float highlight=highSide*fibers*.20+
                                (side/max(halfWide,.1))*highSide*.12-
                                bevel*.18;
                float textureMask=footprint*(.90+.10*bristle);
                return float3(textureMask,highlight*footprint,grain*footprint);
            }

            half4 main(float2 xy) {
                float2 p=safePx(xy);
                float3 original=img(p);
                float radius=max(1.6,brush*.38);
                float2 flow=edgeFlow(p,radius);
                float3 wet=wetUnderpaint(p,flow,
                    max(1.5,brush*(style==1?.65:.47)),original);

                // Two physically different brush scales emulate long
                // palette-knife daubs over short bristle touches.
                float3 wide=brushMark(p,flow,max(9.0,brush*1.95),
                                      float2(.0,.0));
                float3 fine=brushMark(p,flow,max(5.0,brush*.85),
                                      float2(.37,.61));
                float coverage=clamp(wide.x*.74+fine.x*.52,0.0,1.0);
                float reliefShade=(wide.y*.76+fine.y*.43)*relief;
                float pigmentGrain=(wide.z+fine.z)*.08*relief;

                // No posterization. Preserve subtle camera/display gradients
                // and object silhouettes; the ART comes from the tangible
                // stroke shading and irregular paint deposit.
                float3 color=wet;
                if(style==1) color=mix(color,float3(.95,.90,.83),.075);
                if(style==2) {
                    float contrast=abs(lightness(img(p+float2(radius,0.0)))-
                                       lightness(img(p-float2(radius,0.0))));
                    color*=1.0-smoothstep(.16,.53,contrast)*.18;
                }
                color+=float3(reliefShade+pigmentGrain)*coverage;
                color=clamp(color,0.0,1.0);

                // Strength controls painterliness, not tile opacity. Maintain
                // full coverage while retaining enough local detail for real
                // apps, text, faces and moving video.
                float blend=strength*(style==1?.84:1.0);
                float3 finalColor=mix(original,color,blend);
                return half4(half3(finalColor),1.0);
            }
        """.trimIndent()
    }
}
