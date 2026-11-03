package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Touch-through painter drawn in an ordinary Android View.
 *
 * The former TextureView/Secure SurfaceView paths introduced a separate
 * compositor buffer that could appear black, obscure touches, or fail to
 * publish subsequent frames on Samsung devices. This view has NO underlying
 * texture or screenshot backplate: Android composites only its translucent
 * brush pixels over the real applications.
 *
 * Brush planning is performed off the UI thread. The renderer receives a
 * bounded immutable list of stamps, never a captured screen Bitmap.
 */
internal class LivePaintSurface(context: Context) : View(context) {
    private data class BrushSnapshot(
        val strokes: List<PaintDab>,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val preferences: PaintedScreenPreferences
    )

    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicReference<BrushSnapshot?>(null)
    private val scheduled = AtomicBoolean(false)
    private val painter = BrushCanvasPainter()
    private var displayed: BrushSnapshot? = null

    @Volatile
    private var released = false

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isLongClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Called by the single background paint planner. Transfers ONLY stamp
     * parameters to the UI, disposing of the capture Bitmap immediately.
     * Newer pending frames replace old ones instead of growing a backlog.
     */
    fun present(frame: PaintedStrokeFrame, preferences: PaintedScreenPreferences): Boolean {
        if (released) {
            frame.dispose()
            return false
        }
        val snapshot = BrushSnapshot(
            frame.strokes, frame.width, frame.height, preferences
        )
        frame.dispose()
        if (released) return false
        pending.set(snapshot)
        return schedulePresentation()
    }

    private fun schedulePresentation(): Boolean {
        if (released) return false
        if (!scheduled.compareAndSet(false, true)) return true
        val accepted = main.post { drainPending() }
        if (!accepted) scheduled.set(false)
        return accepted
    }

    private fun drainPending() {
        if (released) {
            pending.set(null)
            scheduled.set(false)
            return
        }
        pending.getAndSet(null)?.let {
            displayed = it
            invalidate()
        }
        scheduled.set(false)
        if (pending.get() != null) schedulePresentation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = displayed ?: return
        if (released || width <= 0 || height <= 0) return
        try {
            painter.drawLive(
                canvas = canvas,
                strokes = current.strokes,
                sourceWidth = current.sourceWidth,
                sourceHeight = current.sourceHeight,
                settings = current.preferences,
                displayWidth = width,
                displayHeight = height
            )
        } catch (error: RuntimeException) {
            // Failure must NEVER replace the phone screen with a black bitmap.
            Log.e("HALO-LiveBrush", "Failed to render a live brush frame", error)
            displayed = null
        }
    }

    /** Removing stamps reveals the untouched real screen underneath. */
    fun clearSurface() {
        pending.set(null)
        main.post {
            displayed = null
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        pending.set(null)
        displayed = null
        super.onDetachedFromWindow()
    }

    fun release() {
        if (released) return
        released = true
        pending.set(null)
        main.post {
            displayed = null
            painter.release()
            invalidate()
        }
    }
}

/** Shared CPU-planned / GPU-canvas brush renderer for photos and live marks. */
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
        val save = canvas.save()
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
            drawDabs(canvas, frame.strokes, settings, includeBackground)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    /** No source screenshot is ever drawn in this path. */
    fun drawLive(
        canvas: Canvas,
        strokes: List<PaintDab>,
        sourceWidth: Int,
        sourceHeight: Int,
        settings: PaintedScreenPreferences,
        displayWidth: Int,
        displayHeight: Int
    ) {
        if (sourceWidth <= 0 || sourceHeight <= 0 ||
            displayWidth <= 0 || displayHeight <= 0) return
        val save = canvas.save()
        try {
            canvas.scale(
                displayWidth.toFloat() / sourceWidth,
                displayHeight.toFloat() / sourceHeight
            )
            drawDabs(canvas, strokes, settings, includeBackground = false)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun drawDabs(
        canvas: Canvas,
        strokes: List<PaintDab>,
        settings: PaintedScreenPreferences,
        includeBackground: Boolean
    ) {
        val relief = (settings.relief * 12f).toInt().coerceIn(0, 22)
        val light = if (includeBackground) relief else 46 + relief
        val glow = Color.rgb(light, light, light)
        try {
            for (dab in strokes) {
                val pigment = if (includeBackground) dab.rgb else Color.rgb(
                    (Color.red(dab.rgb) * 1.14f).toInt().coerceIn(0, 255),
                    (Color.green(dab.rgb) * 1.14f).toInt().coerceIn(0, 255),
                    (Color.blue(dab.rgb) * 1.14f).toInt().coerceIn(0, 255)
                )
                val key = pigment xor (light shl 24)
                paint.colorFilter = filters[key]
                    ?: LightingColorFilter(pigment, glow).also { filters[key] = it }
                // The parent overlay is independently capped at 48% opacity.
                // Reduce stamp alpha too: intersecting brush marks should
                // never look like an opaque dark filter over the phone.
                paint.alpha = if (includeBackground) dab.opacity
                    else (dab.opacity * .54f).toInt().coerceIn(0, 138)
                val save = canvas.save()
                try {
                    canvas.translate(dab.x, dab.y)
                    canvas.rotate(dab.rotation)
                    rect.set(
                        -dab.length * .5f, -dab.thickness * .5f,
                        dab.length * .5f, dab.thickness * .5f
                    )
                    canvas.drawBitmap(brushes[dab.brush], null, rect, paint)
                } finally {
                    canvas.restoreToCount(save)
                }
            }
        } finally {
            paint.colorFilter = null
        }
    }

    fun release() {
        filters.clear()
        brushes.forEach { if (!it.isRecycled) it.recycle() }
    }
}
