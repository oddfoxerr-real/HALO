package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A genuine GPU-powered image-space painting shader.
 *
 * Its source is a real bitmap (photograph or user-consented MediaProjection
 * frame). The same GPU renderer is used in HALO's viewer and in a
 * permissioned, touch-through screen-sized window. It cannot replace the
 * system compositor or paint apps that refuse MediaProjection capture.
 */
internal class PaintShaderView(context: Context) : View(context) {
    companion object {
        // AGSL evaluates at the original bitmap's pixel coordinates.
        // Tangent-directed multi-tap sampling creates a continuous, edge-aware
        // impasto treatment. Unlike the former brush-stamp overlay, each
        // original source pixel is actually transformed.
        const val PROGRAM = """
uniform shader source;
uniform float2 resolution;
uniform float brushSize;
uniform float strength;
uniform float relief;
uniform float paintStyle;

float luminance(half3 c) {
    return dot(float3(c), float3(0.2126, 0.7152, 0.0722));
}

float hash21(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

float2 bounds(float2 p, float2 hi) {
    return clamp(p, float2(0.5, 0.5), hi);
}

/**
 * Distinct physical brush dabs, not a blurred or displaced screenshot.
 * Strokes have staggered positions, directional flow, irregular tapered
 * silhouettes, bristle separation and raised-edge lighting.
 */
half4 main(float2 coordinate) {
    float2 hi = max(float2(0.5, 0.5), resolution - float2(0.5, 0.5));
    float2 p = bounds(coordinate, hi);
    half4 original = source.eval(p);

    float radius = max(3.0, brushSize * 5.0);
    half3 underpainting =
        source.eval(p).rgb * 0.30
        + source.eval(bounds(p + float2(radius, 0.0), hi)).rgb * 0.175
        + source.eval(bounds(p - float2(radius, 0.0), hi)).rgb * 0.175
        + source.eval(bounds(p + float2(0.0, radius), hi)).rgb * 0.175
        + source.eval(bounds(p - float2(0.0, radius), hi)).rgb * 0.175;

    float gx = luminance(source.eval(bounds(p + float2(radius, 0.0), hi)).rgb)
             - luminance(source.eval(bounds(p - float2(radius, 0.0), hi)).rgb);
    float gy = luminance(source.eval(bounds(p + float2(0.0, radius), hi)).rgb)
             - luminance(source.eval(bounds(p - float2(0.0, radius), hi)).rgb);
    float2 perpendicular = float2(-gy, gx);
    float edgeAmount = length(perpendicular);
    float2 flow = edgeAmount > 0.001
        ? perpendicular / edgeAmount : float2(1.0, 0.0);

    // At full display scale, one visible oil stroke occupies approximately
    // 30-100 physical pixels, rather than a single pixel-art block.
    float gridSize = max(12.0, brushSize * 19.0);
    float2 tile = floor(p / gridSize);
    float alignment = clamp(edgeAmount * 9.0, 0.0, 0.84);
    float watercolor = 1.0 - step(0.5, abs(paintStyle - 1.0));
    float ink = step(1.5, paintStyle);

    half3 accumulatedPigment = half3(0.0);
    float accumulatedCoverage = 0.0;
    float accumulatedRelief = 0.0;

    for (int iy = -1; iy <= 1; iy++) {
        for (int ix = -1; ix <= 1; ix++) {
            float2 cell = tile + float2(float(ix), float(iy));
            float seed = hash21(cell + float2(5.37, 14.91));
            float seed2 = hash21(cell + float2(41.71, 9.52));
            float seed3 = hash21(cell + float2(2.91, 37.62));

            // Jitter and rotation hide the sampling grid entirely.
            float2 center = (cell + float2(0.5, 0.5) +
                float2(seed - 0.5, seed2 - 0.5) * 0.54) * gridSize;
            float angle = seed3 * 6.2831853;
            float2 randomAxis = float2(cos(angle), sin(angle));
            float2 axis = normalize(mix(randomAxis, flow, alignment));
            float2 side = float2(-axis.y, axis.x);
            float2 displacement = p - center;

            float halfLength = gridSize * (0.82 + 0.60 * seed);
            float halfWidth = gridSize * (0.27 + 0.16 * seed2);
            float u = dot(displacement, axis) / halfLength;
            float v = dot(displacement, side) / halfWidth;
            float silhouette = u*u + v*v*(0.81 + 0.32*abs(u));

            // Uneven brush tips + split bristles are stable across frames.
            float bristles =
                sin(v * 27.0 + sin(u * 13.0 + seed * 7.0) * 2.3) * 0.12
                + sin(v * 63.0 - u * 17.0 + seed2 * 12.0) * 0.051;
            float brokenEdge =
                0.073 * sin(v * 15.0 + u * 11.0 + seed3 * 5.0)
                + 0.047 * sin(v * 38.0 - u * 25.0 + seed * 8.0);
            float silhouetteMask =
                1.0 - smoothstep(0.70, 1.05,
                    silhouette + brokenEdge + bristles * 0.33);
            float strokeAlpha = silhouetteMask *
                (1.0 - watercolor * 0.24);
            if (strokeAlpha > 0.003) {
                float2 pigmentPoint = bounds(center + axis * halfLength * 0.12, hi);
                half3 color = source.eval(pigmentPoint).rgb;
                float pigmentShift = (seed2 - 0.5) * 0.15 +
                    (seed3 - 0.5) * 0.08;
                color = clamp(color * half(1.0 + pigmentShift),
                              half3(0.0), half3(1.0));

                float upperRidge = smoothstep(0.51, 0.87, silhouette)
                    * (1.0 - smoothstep(0.89, 1.13, silhouette));
                float paintHeight =
                    bristles * 0.94 + (0.5 - v) * 0.12 +
                    upperRidge * 0.28 + (seed - 0.5) * 0.09;
                float lighting = 1.0 + relief *
                    paintHeight * (0.37 - watercolor * 0.23 + ink * 0.08);
                color = clamp(color * half(lighting),
                              half3(0.0), half3(1.0));
                if (ink > 0.5) {
                    color *= half(0.80 + 0.15 *
                        (1.0 - smoothstep(0.53, 1.02, silhouette)));
                }
                accumulatedPigment += color * half(strokeAlpha);
                accumulatedCoverage += strokeAlpha;
                accumulatedRelief += paintHeight * strokeAlpha;
            }
        }
    }

    float coverage = 1.0 - exp(-accumulatedCoverage * 2.6);
    half3 strokeColor = accumulatedPigment /
        half(max(0.001, accumulatedCoverage));
    // A softer painted undercoat fills gaps between the raised oil dabs.
    half3 painted = mix(underpainting, strokeColor, half(coverage));

    float localHeight = accumulatedRelief /
        max(0.001, accumulatedCoverage);
    float grain = hash21(floor(p / max(1.8, brushSize * 1.4)) +
        float2(15.7, 4.3)) - 0.5;
    float surfaceLight = 1.0 + relief * (
        localHeight * 0.15 + grain * (0.075 - watercolor * 0.038));
    painted = clamp(painted * half(surfaceLight),
                    half3(0.0), half3(1.0));

    // Nearly full-strength painting is intentional: the previous shader
    // retained most of the original pixels and looked unfiltered.
    float mixAmount = clamp(strength * 1.07, 0.0, 1.0);
    return half4(mix(original.rgb, painted, half(mixAmount)), original.a);
}
"""
    }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dest = RectF()
    private var sourceBitmap: Bitmap? = null
    private var gpuShader: RuntimeShader? = null
    private var failedGpuShader = false
    var onShaderFailure: ((String) -> Unit)? = null
    private var preferences = PaintedScreenPreferences()
    private var fullBleed = false

    /** Full-screen display crops to the selected app instead of letterboxing. */
    fun setFullBleed(enabled: Boolean) {
        if (fullBleed != enabled) {
            fullBleed = enabled
            invalidate()
        }
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun configure(next: PaintedScreenPreferences) {
        if (preferences != next) {
            preferences = next
            invalidate()
        }
    }

    /** Bitmap ownership transfers to this View; call only on its UI thread. */
    fun show(bitmap: Bitmap?) {
        if (sourceBitmap === bitmap) return
        val old = sourceBitmap
        sourceBitmap = bitmap
        invalidate()
        if (old != null && !old.isRecycled) {
            // RenderThread may still be sampling the previous BitmapShader.
            postDelayed({ if (!old.isRecycled) old.recycle() }, 1000L)
        }
    }

    fun clearSource() {
        show(null)
    }

    override fun onDetachedFromWindow() {
        clearSource()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = sourceBitmap ?: return
        if (image.isRecycled || width <= 0 || height <= 0) return
        val save = canvas.save()
        try {
            val scale = if (fullBleed)
                maxOf(width.toFloat() / image.width, height.toFloat() / image.height)
            else
                minOf(width.toFloat() / image.width, height.toFloat() / image.height)
            val drawingWidth = image.width * scale
            val drawingHeight = image.height * scale
            canvas.translate((width - drawingWidth) * .5f, (height - drawingHeight) * .5f)
            canvas.scale(scale, scale)
            dest.set(0f, 0f, image.width.toFloat(), image.height.toFloat())
            if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated && !failedGpuShader) {
                try {
                    val active = gpuShader ?: RuntimeShader(PROGRAM).also { gpuShader = it }
                    active.setInputShader("source", BitmapShader(
                        image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
                    ))
                    active.setFloatUniform("resolution", image.width.toFloat(), image.height.toFloat())
                    active.setFloatUniform("brushSize", preferences.brushSize.coerceIn(.65f, 2.3f))
                    active.setFloatUniform("strength", preferences.strength.coerceIn(.35f, 1f))
                    active.setFloatUniform("relief", preferences.relief.coerceIn(.15f, 1.6f))
                    active.setFloatUniform("paintStyle", preferences.style.ordinal.toFloat())
                    framePaint.shader = active
                    canvas.drawRect(dest, framePaint)
                    return
                } catch (error: RuntimeException) {
                    failedGpuShader = true
                    Log.e("HALO-PaintGPU", "AGSL shader failed; source kept visible", error)
                    onShaderFailure?.invoke(error.message ?: "GPU could not compile paint strokes")
                } finally {
                    framePaint.shader = null
                }
            }
            // Reliable fallback is an UNFILTERED picture, never a black frame.
            canvas.drawBitmap(image, null, dest, framePaint)
        } finally {
            canvas.restoreToCount(save)
        }
    }
}

/**
 * Latest-frame delivery with bounded memory. The capture worker never blocks
 * on GPU rendering and all bitmap/view ownership changes happen on the UI
 * thread. No preview listener means captured pixels are discarded at once.
 */
internal object PaintPreviewFrameHub {
    private val main = Handler(Looper.getMainLooper())
    private val latest = AtomicReference<Bitmap?>(null)
    private val scheduled = AtomicBoolean(false)
    @Volatile private var observer: PaintShaderView? = null

    fun attach(view: PaintShaderView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        observer = view
    }

    fun detach(view: PaintShaderView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (observer === view) {
            observer = null
            view.clearSource()
        }
        latest.getAndSet(null)?.recycle()
    }

    fun publish(bitmap: Bitmap) {
        if (observer == null) {
            bitmap.recycle()
            return
        }
        latest.getAndSet(bitmap)?.recycle()
        if (scheduled.compareAndSet(false, true)) {
            if (!main.post { drain() }) {
                scheduled.set(false)
                latest.getAndSet(null)?.recycle()
            }
        }
    }

    private fun drain() {
        val image = latest.getAndSet(null)
        val target = observer
        if (image != null) {
            if (target != null) target.show(image)
            else image.recycle()
        }
        scheduled.set(false)
        if (latest.get() != null && scheduled.compareAndSet(false, true)) {
            if (!main.post { drain() }) scheduled.set(false)
        }
    }

    fun clear() {
        latest.getAndSet(null)?.recycle()
        main.post { observer?.clearSource() }
    }
}
