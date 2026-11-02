package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.ArrayDeque

/**
 * Paint into a dedicated SECURE SurfaceView, rather than marking the entire
 * overlay window FLAG_SECURE. The containing window stays transparent.
 *
 * MediaProjection exclusion of secure surfaces is compositor-specific:
 * physical Samsung validation is required. If frames become redacted, the
 * service removes this surface and reports the restriction, never recycles
 * old captured content as a fake freeze-frame.
 *
 * Rendered off the main thread directly to the SurfaceHolder. SurfaceView
 * decouples painting time from Compose/View invalidation.
 */
internal class LivePaintSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val tag = "HALO-LivePaint"
    @Volatile private var ready = false
    @Volatile private var attached = false
    private val painter = BrushCanvasPainter()
    private val pendingRecycle = ArrayDeque<PaintedStrokeFrame>()

    init {
        // SECURE applies only to the brush layer, not to the full WindowManager
        // application overlay as in versions <= 0.20.5.
        setSecure(true)
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        setWillNotDraw(true)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ready = true
        attached = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        ready = width > 0 && height > 0
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ready = false
        attached = false
    }

    /** Called only by HALO's single dedicated capture/painting HandlerThread. */
    fun present(frame: PaintedStrokeFrame, preferences: PaintedScreenPreferences): Boolean {
        if (!ready || !attached || !holder.surface.isValid || width <= 0 || height <= 0) {
            frame.dispose()
            return false
        }

        var canvas: Canvas? = null
        return try {
            canvas = if (Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas()
                else holder.lockCanvas()
            val locked = canvas ?: run {
                frame.dispose()
                return false
            }
            locked.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            painter.draw(locked, frame, preferences, width, height)
            holder.unlockCanvasAndPost(locked)
            canvas = null

            // GPU can consume bitmap-backed display lists after unlockCanvasAndPost.
            // Retain a small bounded FIFO until several *new frames* have posted.
            pendingRecycle.addLast(frame)
            while (pendingRecycle.size > 4) pendingRecycle.removeFirst().dispose()
            true
        } catch (error: RuntimeException) {
            Log.e(tag, "Secure live paint surface rejected a frame", error)
            frame.dispose()
            false
        } finally {
            canvas?.let { locked ->
                try { holder.unlockCanvasAndPost(locked) }
                catch (error: RuntimeException) {
                    Log.w(tag, "Unable to return surface canvas", error)
                }
            }
        }
    }

    /** Called on the painter worker when content is invalid or capture is paused. */
    fun clearSurface() {
        if (!ready || !holder.surface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas = if (Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas()
                else holder.lockCanvas()
            canvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } catch (error: RuntimeException) {
            Log.w(tag, "Could not clear secure paint surface", error)
        } finally {
            canvas?.let {
                try { holder.unlockCanvasAndPost(it) }
                catch (_: RuntimeException) {}
            }
        }
        while (pendingRecycle.isNotEmpty()) pendingRecycle.removeFirst().dispose()
    }

    /** Must be called once after stopping the worker, never during drawing. */
    fun release() {
        ready = false
        attached = false
        while (pendingRecycle.isNotEmpty()) pendingRecycle.removeFirst().dispose()
        painter.release()
        holder.removeCallback(this)
    }
}

/** Immutable brush bitmaps with hardware Canvas rendering on a worker thread. */
internal class BrushCanvasPainter {
    private val brushes = Array(4) { BrushTextureFactory.create(it) }
    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val filters = object : LinkedHashMap<Int, LightingColorFilter>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int,LightingColorFilter>) =
            size > 256
    }

    fun draw(
        canvas: Canvas,
        frame: PaintedStrokeFrame,
        settings: PaintedScreenPreferences,
        displayWidth: Int,
        displayHeight: Int
    ) {
        if (frame.underpainting.isRecycled || displayWidth <= 0 || displayHeight <= 0) return
        val save = canvas.save()
        try {
            canvas.scale(displayWidth.toFloat() / frame.width, displayHeight.toFloat() / frame.height)
            rect.set(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
            backdrop.alpha = 255
            canvas.drawBitmap(frame.underpainting, null, rect, backdrop)
            val intensity = (settings.relief * 12f).toInt().coerceIn(0, 22)
            val light = Color.rgb(intensity, intensity, intensity)
            for (dab in frame.strokes) {
                val key = dab.rgb xor (intensity shl 24)
                paint.colorFilter = filters[key] ?: LightingColorFilter(dab.rgb, light).also {
                    filters[key] = it
                }
                paint.alpha = dab.opacity
                val token = canvas.save()
                canvas.translate(dab.x, dab.y)
                canvas.rotate(dab.rotation)
                rect.set(
                    -dab.length * .5f, -dab.thickness * .5f,
                    dab.length * .5f, dab.thickness * .5f
                )
                canvas.drawBitmap(brushes[dab.brush], null, rect, paint)
                canvas.restoreToCount(token)
            }
        } finally {
            paint.colorFilter = null
            canvas.restoreToCount(save)
        }
    }

    fun release() {
        filters.clear()
        brushes.forEach { if (!it.isRecycled) it.recycle() }
    }
}
