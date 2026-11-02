package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView

/**
 * Safe live brush layer.
 *
 * Previous builds put a SECURE SurfaceView on top of the phone. It could
 * redact MediaProjection and create a separate opaque input-obscuring layer.
 * A TextureView belongs to the host window, and its alpha is composed with
 * that window. No secure child layer or opaque screenshot is created.
 *
 * Only translucent pigmented brush strokes are drawn. The actual phone UI
 * remains physically underneath this layer and handles every touch.
 * This class cannot lock or draw the phone's applications themselves.
 */
internal class LivePaintSurface(context: Context) :
    TextureView(context), TextureView.SurfaceTextureListener {
    private val tag = "HALO-LiveBrush"
    @Volatile private var surfaceReady = false
    @Volatile private var released = false
    private val painter = BrushCanvasPainter()
    private var consecutiveRenderErrors = 0

    init {
        isOpaque = false
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(
        texture: SurfaceTexture, width: Int, height: Int
    ) {
        surfaceReady = width > 0 && height > 0 && !released
    }

    override fun onSurfaceTextureSizeChanged(
        texture: SurfaceTexture, width: Int, height: Int
    ) {
        surfaceReady = width > 0 && height > 0 && !released
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        surfaceReady = false
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    /** Called only from LatestPaintDispatcher's dedicated render thread. */
    fun present(frame: PaintedStrokeFrame, preferences: PaintedScreenPreferences): Boolean {
        if (released || !surfaceReady || !isAvailable || width <= 0 || height <= 0) {
            frame.dispose()
            return false
        }
        var canvas: Canvas? = null
        var posted = false
        return try {
            canvas = lockCanvas()
            val target = canvas ?: return false
            target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            painter.draw(
                target, frame, preferences, width, height,
                includeBackground = false
            )
            unlockCanvasAndPost(target)
            canvas = null
            posted = true
            consecutiveRenderErrors = 0
            true
        } catch (error: RuntimeException) {
            consecutiveRenderErrors++
            Log.e(tag, "Translucent paint update failed ($consecutiveRenderErrors)", error)
            false
        } finally {
            canvas?.let {
                try { unlockCanvasAndPost(it) }
                catch (error: RuntimeException) {
                    Log.w(tag, "Could not post final live brush canvas", error)
                }
            }
            frame.dispose()
        }
    }

    /** Clear only the transparent brush canvas; never show a black fallback. */
    fun clearSurface() {
        if (released || !surfaceReady || !isAvailable) return
        var canvas: Canvas? = null
        try {
            canvas = lockCanvas()
            canvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } catch (error: RuntimeException) {
            Log.w(tag, "Brush clearing unavailable", error)
        } finally {
            canvas?.let {
                try { unlockCanvasAndPost(it) }
                catch (error: RuntimeException) {
                    Log.w(tag, "Brush clearing commit failed", error)
                }
            }
        }
    }

    fun release() {
        released = true
        surfaceReady = false
        painter.release()
        surfaceTextureListener = null
    }
}

/**
 * Multi-pass original brush stamps.
 * Full-colour backgrounds are allowed inside HALO's photo editor ONLY.
 * Live overlays must draw marks with alpha, with no source screenshot.
 */
internal class BrushCanvasPainter {
    private val brushes = Array(4) { BrushTextureFactory.create(it) }
    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val filters = object : LinkedHashMap<Int, LightingColorFilter>(256, .75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, LightingColorFilter>
        ) = size > 256
    }

    fun draw(
        canvas: Canvas,
        frame: PaintedStrokeFrame,
        settings: PaintedScreenPreferences,
        displayWidth: Int,
        displayHeight: Int,
        includeBackground: Boolean = true
    ) {
        if (frame.original.isRecycled || displayWidth <= 0 || displayHeight <= 0) return
        val saved = canvas.save()
        try {
            canvas.scale(
                displayWidth.toFloat() / frame.width,
                displayHeight.toFloat() / frame.height
            )
            if (includeBackground) {
                rect.set(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
                backdrop.alpha = 255
                canvas.drawBitmap(frame.underpainting, null, rect, backdrop)
            }
            val relief = (settings.relief * 12f).toInt().coerceIn(0, 22)
            val glow = Color.rgb(relief, relief, relief)
            for (dab in frame.strokes) {
                val key = dab.rgb xor (relief shl 24)
                paint.colorFilter = filters[key]
                    ?: LightingColorFilter(dab.rgb, glow).also { filters[key] = it }
                // One intentionally translucent brush pass is composited
                // over the existing phone screen. Do not redraw opaque
                // screenshots over the entire screen.
                paint.alpha = if (includeBackground) dab.opacity
                    else (dab.opacity * 0.72f).toInt().coerceIn(0, 185)
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
            canvas.restoreToCount(saved)
        }
    }

    fun release() {
        filters.clear()
        brushes.forEach { if (!it.isRecycled) it.recycle() }
    }
}
