package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * A decorative pill outline on unlocked screens only. This is NOT an overlay inside Samsung's
 * protected Now Bar window. Rendering is confined to a transparent, non-touch window.
 */
class PillHaloView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val outline = Path()
    private val highlight = Path()
    private val measure = PathMeasure()
    private var glowColor = Color.rgb(85, 232, 153)
    private var glowAmount = .65f
    private var thicknessDp = 2.5f
    private var elapsed = 0f
    private var running = true
    private var previousFrameNanos = 0L

    fun setStyle(color: Int, glow: Float, thickness: Float) {
        glowColor = color
        glowAmount = glow.coerceIn(0f, 1f)
        thicknessDp = thickness.coerceIn(1f, 6f)
        invalidate()
    }

    fun stop() {
        running = false
        previousFrameNanos = 0L
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        previousFrameNanos = 0L
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val now = System.nanoTime()
        if (previousFrameNanos != 0L)
            elapsed += ((now - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, .1f)
        previousFrameNanos = now
        val pad = 12f * density
        val box = RectF(pad, pad, width - pad, height - pad)
        val radius = box.height() / 2f
        outline.reset()
        outline.addRoundRect(box, radius, radius, Path.Direction.CW)
        paint.color = glowColor
        paint.strokeWidth = (9f + glowAmount * 6f) * density
        paint.alpha = (24f + glowAmount * 38f).roundToInt()
        canvas.drawPath(outline, paint)
        paint.strokeWidth = (4f + glowAmount * 2f) * density
        paint.alpha = (48f + glowAmount * 60f).roundToInt()
        canvas.drawPath(outline, paint)
        paint.strokeWidth = thicknessDp * density
        paint.alpha = 215
        canvas.drawPath(outline, paint)
        measure.setPath(outline, true)
        val length = measure.length
        if (length > 0f) {
            val start = (elapsed * 80f * density) % length
            val size = (58f * density).coerceAtMost(length / 3f)
            highlight.reset()
            val end = start + size
            measure.getSegment(start, end.coerceAtMost(length), highlight, true)
            if (end > length) measure.getSegment(0f, end - length, highlight, true)
            paint.color = Color.WHITE
            paint.alpha = 208
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = (thicknessDp + 1f) * density
            canvas.drawPath(highlight, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
        if (running && isAttachedToWindow) postInvalidateDelayed(33L)
    }
}

object PillHaloOverlay {
    private var windowManager: WindowManager? = null
    private var activeView: PillHaloView? = null
    private var activeParams: WindowManager.LayoutParams? = null

    fun show(context: Context, color: Long, store: ProfileStore) {
        if (!Settings.canDrawOverlays(context)) {
            dismiss()
            return
        }
        val density = context.resources.displayMetrics.density
        val width = ((store.pillWidthDp + 24f) * density).roundToInt()
        val height = ((store.pillHeightDp + 24f) * density).roundToInt()
        val lp = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            y = (store.pillBottomDp * density).roundToInt()
            alpha = .60f
        }
        val manager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = activeView
        if (view == null) {
            val next = PillHaloView(context.applicationContext)
            next.setStyle(color.toInt(), store.pillGlow, store.pillThicknessDp)
            try {
                manager.addView(next, lp)
                windowManager = manager
                activeView = next
                activeParams = lp
            } catch (_: RuntimeException) {
                next.stop()
                dismiss()
            }
        } else {
            view.setStyle(color.toInt(), store.pillGlow, store.pillThicknessDp)
            if (activeParams?.width != width || activeParams?.height != height || activeParams?.y != lp.y) {
                try {
                    windowManager?.updateViewLayout(view, lp)
                    activeParams = lp
                } catch (_: RuntimeException) { dismiss() }
            }
        }
    }

    fun dismiss() {
        activeView?.stop()
        activeView?.let { try { windowManager?.removeViewImmediate(it) } catch (_: RuntimeException) {} }
        activeView = null
        activeParams = null
        windowManager = null
    }
}
