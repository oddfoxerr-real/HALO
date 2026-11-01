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
        currentFrame = frame
        invalidate()
    }

    fun clear() {
        currentFrame = null
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
            // No fake CPU brush animation. This path only preserves the captured image.
            output.shader = source
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), output)
            output.shader = null
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

            float noise(float2 p) {
                return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
            }
            float light(float3 c) {
                return dot(c, float3(.2126, .7152, .0722));
            }
            half4 main(float2 xy) {
                float2 p = clamp(xy, float2(0.0), resolution);
                float cell = max(3.0, brush * (style == 1 ? 1.35 : 1.0));
                float2 tile = floor(p / cell);
                float random = noise(tile);
                float rotation = random * 6.2831853;
                float2 direction = float2(cos(rotation), sin(rotation));
                float2 tangent = float2(-direction.y, direction.x);
                float2 displaced = p + direction * (random - .5) * cell * .7;
                float3 original = float3(screen.eval(p).rgb);
                float3 pigment = float3(screen.eval(displaced).rgb) * .38;
                pigment += float3(screen.eval(displaced + direction * cell * .6).rgb) * .19;
                pigment += float3(screen.eval(displaced - direction * cell * .6).rgb) * .19;
                pigment += float3(screen.eval(displaced + tangent * cell * .55).rgb) * .12;
                pigment += float3(screen.eval(displaced - tangent * cell * .55).rgb) * .12;

                float levels = style == 2 ? 5.0 : (style == 1 ? 11.0 : 8.0);
                pigment = floor(clamp(pigment, 0.0, 1.0) * levels + .5) / levels;
                float stroke = sin(dot(p, direction) * .75 + random * 9.0);
                float fibers = noise(floor(p * .48)) - .5;
                pigment *= 1.0 + (.026 * stroke + .033 * fibers) * strength;
                if (style == 1) {
                    // Pale washes and soft blooms.
                    pigment = mix(pigment, float3(1.0), .095);
                } else {
                    float gradX = abs(light(float3(screen.eval(p + float2(cell, 0.0)).rgb)) -
                                      light(float3(screen.eval(p - float2(cell, 0.0)).rgb)));
                    float gradY = abs(light(float3(screen.eval(p + float2(0.0, cell)).rgb)) -
                                      light(float3(screen.eval(p - float2(0.0, cell)).rgb)));
                    float contour = smoothstep(.085, .27, gradX + gradY);
                    pigment *= 1.0 - contour * (style == 2 ? .45 : .09);
                }
                float3 result = mix(original, clamp(pigment, 0.0, 1.0), strength);
                return half4(half3(result), 1.0);
            }
        """.trimIndent()
    }
}
