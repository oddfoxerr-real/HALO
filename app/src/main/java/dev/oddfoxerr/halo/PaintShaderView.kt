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

float luminance(half3 c) {
    return dot(float3(c), float3(0.2126, 0.7152, 0.0722));
}

float hash21(float2 x) {
    return fract(sin(dot(x, float2(127.1, 311.7))) * 43758.5453);
}

half4 main(float2 coordinate) {
    float2 lo = float2(0.5, 0.5);
    float2 hi = max(lo, resolution - lo);
    float2 p = clamp(coordinate, lo, hi);
    half4 base = source.eval(p);
    float span = max(1.0, brushSize * 1.5);
    float gx = luminance(source.eval(clamp(p + float2(span, 0.0), lo, hi)).rgb)
             - luminance(source.eval(clamp(p - float2(span, 0.0), lo, hi)).rgb);
    float gy = luminance(source.eval(clamp(p + float2(0.0, span), lo, hi)).rgb)
             - luminance(source.eval(clamp(p - float2(0.0, span), lo, hi)).rgb);
    float2 edge = float2(-gy, gx);
    float weight = length(edge);
    float cellSize = max(4.0, brushSize * 11.0);
    float angle = hash21(floor(p / cellSize)) * 6.2831853;
    float2 tangent = weight > 0.018
        ? edge / weight
        : float2(cos(angle), sin(angle));
    float phase = hash21(floor((p + float2(7.0, 19.0)) / cellSize));
    float stroke = sin(dot(p, tangent) / max(2.0, brushSize * 3.8)
                       + phase * 6.2831853);
    float offset = stroke * brushSize * 2.3;
    float2 displacement = tangent * offset;
    float2 p1 = clamp(p + displacement, lo, hi);
    float2 p2 = clamp(p + displacement + tangent * span * 1.8, lo, hi);
    float2 p3 = clamp(p + displacement - tangent * span * 1.8, lo, hi);
    float2 p4 = clamp(p + displacement + tangent * span * 3.4, lo, hi);
    float2 p5 = clamp(p + displacement - tangent * span * 3.4, lo, hi);
    half3 pigment = source.eval(p1).rgb * 0.39
                  + source.eval(p2).rgb * 0.23
                  + source.eval(p3).rgb * 0.23
                  + source.eval(p4).rgb * 0.075
                  + source.eval(p5).rgb * 0.075;
    float smallGrain = hash21(floor(p / max(1.5, brushSize * 1.7)));
    float height = (stroke * 0.067 + (smallGrain - 0.5) * 0.040)
                   * relief;
    half3 lifted = clamp(pigment * half(1.0 + height),
                         half3(0.0), half3(1.0));
    return half4(mix(base.rgb, lifted, half(strength)), base.a);
}
"""
    }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dest = RectF()
    private var sourceBitmap: Bitmap? = null
    private var gpuShader: RuntimeShader? = null
    private var failedGpuShader = false
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
                    framePaint.shader = active
                    canvas.drawRect(dest, framePaint)
                    return
                } catch (error: RuntimeException) {
                    failedGpuShader = true
                    Log.e("HALO-PaintGPU", "AGSL shader failed; source kept visible", error)
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
