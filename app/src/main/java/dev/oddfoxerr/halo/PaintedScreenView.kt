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

/** Optional, ephemeral screen treatment. Frames exist in RAM only and are never persisted. */
enum class PaintedStyle(val label: String) {
    OIL("Oil paint"),
    WATERCOLOR("Watercolour"),
    INK("Ink & paint")
}

data class PaintedScreenPreferences(
    val style: PaintedStyle = PaintedStyle.OIL,
    val strength: Float = .82f
) {
    fun save(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString("style", style.name)
            .putFloat("strength", strength.coerceIn(.35f, 1f))
            .apply()
    }

    companion object {
        private const val STORE = "halo_painted_screen"

        fun read(context: Context): PaintedScreenPreferences {
            val saved = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val style = PaintedStyle.entries.firstOrNull {
                it.name == saved.getString("style", PaintedStyle.OIL.name)
            } ?: PaintedStyle.OIL
            val value = saved.getFloat("strength", .82f)
            return PaintedScreenPreferences(style, if (value.isFinite()) value.coerceIn(.35f, 1f) else .82f)
        }
    }
}

/**
 * Decodes Android's RGBA_8888 Image.Plane without assuming packed row stride.
 * One bounded bitmap per accepted frame; no storage access or network path.
 */
internal object PaintedFrameDecoder {
    fun decode(
        buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int
    ): Bitmap? {
        if (width !in 16..1024 || height !in 16..2048 ||
            pixelStride < 4 || rowStride < width * pixelStride) return null
        val end = (height - 1L) * rowStride + (width - 1L) * pixelStride + 3L
        if (end >= buffer.limit()) return null

        val pixels = IntArray(width * height)
        // Read the exact buffer coordinates. Image.Plane may pad each scan line.
        var populated = 0
        var luminance = 0L
        var y = 0
        while (y < height) {
            var x = 0
            val offset = y * rowStride
            while (x < width) {
                val i = offset + x * pixelStride
                val r = buffer.get(i).toInt() and 255
                val g = buffer.get(i + 1).toInt() and 255
                val b = buffer.get(i + 2).toInt() and 255
                pixels[y * width + x] = -0x1000000 or (r shl 16) or (g shl 8) or b
                if ((x and 7) == 0 && (y and 7) == 0) {
                    luminance += max(r, max(g, b)).toLong()
                    populated++
                }
                x++
            }
            y++
        }
        // Secure / DRM screens can be black. Do not paint a black privacy mask
        // over them, and do not recycle a stale frame from another application.
        if (populated == 0 || luminance < populated * 5L) return null
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}

/** GPU-painted appearance, not a colored border or a stored video playback. */
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
        // RenderThread may still be sampling the previous BitmapShader.
        // Defer recycling until the GPU has had several display frames to finish.
        if (old != null && old !== frame) postDelayed({ if (!old.isRecycled) old.recycle() }, 1000L)
    }

    fun clear() {
        setFrame(null)
        output.shader = null
        shader = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val picture = currentFrame ?: return
        if (width <= 0 || height <= 0) return
        matrix.reset()
        matrix.setScale(width.toFloat() / picture.width, height.toFloat() / picture.height)
        val source = BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        source.setLocalMatrix(matrix)

        if (Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated || shaderUnavailable) {
            // A plain full-screen screenshot would cover UI and cause input lag.
            // Fail open: the original app remains visible underneath HALO.
            return
        }
        try {
            val effect = shader ?: RuntimeShader(SOURCE).also { shader = it }
            effect.setInputShader("screen", source)
            effect.setFloatUniform("resolution", width.toFloat(), height.toFloat())
            effect.setFloatUniform("brush", resources.displayMetrics.density * 4.8f)
            effect.setFloatUniform("strength", preferences.strength)
            effect.setIntUniform("style", preferences.style.ordinal)
            output.shader = effect
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), output)
            output.shader = null
        } catch (failure: RuntimeException) {
            shaderUnavailable = true
            output.shader = null
            Log.e("HALO", "Painterly GPU shader unavailable", failure)
            // Keep the system interactable rather than painting incorrect pixels.
        }
    }

    companion object {
        // Coarse brush marks, blended pigment, reduced tonal steps, optional ink contours.
        // The shader samples its own live source texture: no bitmap readbacks per pixel.
        @RequiresApi(33)
        internal fun compileForTest(): RuntimeShader = RuntimeShader(SOURCE)

        private val SOURCE = """
            uniform shader screen;
            uniform float2 resolution;
            uniform float brush;
            uniform float strength;
            uniform int style;
            float hash21(float2 p) {
                return fract(sin(dot(p,float2(127.1,311.7)))*43758.5453);
            }
            float brightnessOf(float3 c) {
                return dot(c,float3(.2126,.7152,.0722));
            }
            // Every stamp is a real elongated, tapered procedural brush mark.
            // Local gradient rotates its bristles along the picture's features;
            // the tile ID anchors strokes so still scenes never shimmer randomly.
            float4 brushStroke(float2 p, float cell, float2 offset) {
                float2 id=floor(p/cell+offset);
                float r=hash21(id);
                float2 center=(id+.5-offset)*cell;
                center+=float2(hash21(id+11.7)-.5,hash21(id+28.3)-.5)*cell*.22;
                float radius=max(cell*.35,2.0);
                float2 dx=float2(radius,0.0);
                float2 dy=float2(0.0,radius);
                float gx=brightnessOf(float3(screen.eval(center+dx).rgb)) -
                         brightnessOf(float3(screen.eval(center-dx).rgb));
                float gy=brightnessOf(float3(screen.eval(center+dy).rgb)) -
                         brightnessOf(float3(screen.eval(center-dy).rgb));
                float2 orientation=float2(-gy,gx);
                float magnitude=length(orientation);
                float angle=6.2831853*hash21(id+5.9);
                float2 fallback=float2(cos(angle),sin(angle));
                float2 axis=normalize(orientation+fallback*max(.055-magnitude,0.0));
                float2 across=float2(-axis.y,axis.x);
                float2 q=p-center;
                float along=dot(q,axis);
                float side=dot(q,across);
                float halfLength=cell*(.45+.28*hash21(id+2.3));
                float halfWidth=cell*(style==1?.23:.16);
                halfWidth*=.75+.45*hash21(id+4.2);
                float taper=1.0-.42*pow(clamp(abs(along)/max(halfLength,.01),0.0,1.0),2.0);
                float uneven=sin(along*.37+r*9.0)*.12+sin(along*.83+side*.4+r*17.0)*.075;
                float width=halfWidth*taper*(1.0+uneven);
                float longitudinal=1.0-smoothstep(halfLength*.65,halfLength,abs(along));
                float lateral=1.0-smoothstep(width*.45,width,abs(side));
                float mask=longitudinal*lateral;
                // Multiple separated bristles with varied opacity produce actual
                // painted filament marks, not quantized flat pixel blocks.
                float bristle=sin(side*2.25+along*.21+r*25.0)*
                               sin(side*4.35-along*.11+r*6.0);
                mask*=clamp(.68+.32*bristle,0.0,1.0);
                float grain=hash21(floor(p*.7)+id*.21);
                mask*=.77+.23*grain;
                float3 pigment=float3(screen.eval(center).rgb);
                float levels=style==2?7.0:style==1?12.0:10.0;
                pigment=floor(clamp(pigment,0.0,1.0)*levels+.5)/levels;
                if(style==1) pigment=mix(pigment,float3(.94,.90,.85),.16);
                float high=sin(side*1.8+along*.13);
                pigment=clamp(pigment*(.89+.15*high),0.0,1.0);
                if(style==2) {
                    float outline=smoothstep(.11,.36,magnitude);
                    pigment*=1.0-.28*outline;
                }
                return float4(pigment,mask);
            }
            half4 main(float2 xy) {
                float2 p=clamp(xy,float2(0.0),resolution);
                float base=max(7.0,brush*2.55);
                float4 large=brushStroke(p,base,float2(.0,.0));
                float4 detail=brushStroke(p,base*.48,float2(.36,.58));
                float a=large.a*.80+detail.a*.42*(1.0-large.a);
                float3 pigment=mix(large.rgb,detail.rgb,clamp(detail.a*.50,0.0,1.0));
                // Watercolour uses thin translucent washes; oil has pronounced
                // impasto; ink has more contrast. Transparent gaps deliberately
                // expose the real live app and preserve its crisp text / controls.
                float styleOpacity=style==1?.57:style==2?.90:.84;
                a=clamp(a*strength*styleOpacity,0.0,.81);
                return half4(half3(pigment*a),half(a));
            }
        """.trimIndent()
    }
}
