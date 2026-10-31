package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import kotlin.math.sin

/**
 * Light full-perimeter notification effect. It follows the screen frame, not the internal
 * bounds of Samsung's protected notification UI. Never intercepts touch events.
 */
class NotificationBorderView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val border = Path()
    private val segment = Path()
    private val measure = PathMeasure()
    private val rect = RectF()
    var tint: Int = Color.rgb(170, 91, 255)
    var thicknessDp: Float = 3f
    var glow: Float = .65f
    var style: NotificationEdgeStyle = NotificationEdgeStyle.PULSE
    private var startedAt = 0L
    private var duration = 3500L
    private var animated = false

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun play(color: Int, thickness: Float, glowAmount: Float, newStyle: NotificationEdgeStyle, millis: Long) {
        tint = color
        thicknessDp = thickness.coerceIn(1f, 8f)
        glow = glowAmount.coerceIn(0f, 1f)
        style = newStyle
        duration = millis.coerceIn(800L, 10000L)
        startedAt = SystemClock.uptimeMillis()
        animated = true
        invalidate()
    }

    fun settle() {
        animated = false
        invalidate()
    }

    val isAnimating: Boolean get() = animated

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val pad = 13f * density
        rect.set(pad, pad, width - pad, height - pad)
        if (rect.width() <= 0 || rect.height() <= 0) return
        border.reset()
        border.addRoundRect(rect, 35f * density, 35f * density, Path.Direction.CW)
        val elapsed = (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
        val t = if (animated) (elapsed.toFloat() / duration).coerceIn(0f, 1f) else 1f
        val strength = if (!animated) .33f else when (style) {
            NotificationEdgeStyle.PULSE -> .60f + .40f * sin(t * 8f * Math.PI.toFloat()).let { kotlin.math.abs(it) }
            NotificationEdgeStyle.TRACE -> .8f
            NotificationEdgeStyle.GLOW -> (1f - t * .70f)
        }
        paint.color = tint
        paint.alpha = ((24f + 65f * glow) * strength).toInt().coerceIn(0, 255)
        paint.strokeWidth = (12f + glow * 8f) * density
        canvas.drawPath(border, paint)

        paint.alpha = (195f * strength + 35f).toInt().coerceIn(0, 255)
        paint.strokeWidth = thicknessDp * density
        canvas.drawPath(border, paint)

        if (animated && style == NotificationEdgeStyle.TRACE) {
            measure.setPath(border, true)
            val length = measure.length
            val start = (length * t * 1.4f) % length
            val end = start + length * .19f
            segment.reset()
            measure.getSegment(start, end.coerceAtMost(length), segment, true)
            if (end > length) measure.getSegment(0f, end - length, segment, true)
            paint.color = Color.WHITE
            paint.alpha = ((1f - t) * 210f).toInt().coerceIn(0, 255)
            paint.strokeWidth = (thicknessDp + 1.5f) * density
            canvas.drawPath(segment, paint)
        }
        if (animated && elapsed < duration && isAttachedToWindow) postInvalidateDelayed(33L)
        else if (animated) animated = false
    }
}

object NotificationEdgeOverlay {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var border: NotificationBorderView? = null
    private var generation = 0L
    val isAnimating: Boolean get() = border?.isAnimating == true
    val isVisible: Boolean get() = border != null

    fun show(context: Context, color: Long, duration: Long, persistent: Boolean, store: ProfileStore) {
        if (!Settings.canDrawOverlays(context)) return
        val view = ensure(context) ?: return
        generation++
        val id = generation
        view.play(color.toInt(), store.notificationBorderThickness,
            store.notificationBorderGlow, store.notificationEdgeStyle, duration)
        handler.postDelayed({
            if (generation == id) {
                if (persistent) view.settle() else dismiss()
            }
        }, duration.coerceIn(800L, 10000L))
    }

    fun showStatic(context: Context, color: Long, store: ProfileStore) {
        if (isAnimating) return
        val view = ensure(context) ?: return
        view.play(color.toInt(), store.notificationBorderThickness,
            store.notificationBorderGlow, store.notificationEdgeStyle, 1000L)
        view.settle()
    }

    private fun ensure(context: Context): NotificationBorderView? {
        border?.let { return it }
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Along with the narrow character overlay, stay under cumulative 0.8 touch opacity.
            alpha = .35f
        }
        return try {
            NotificationBorderView(context.applicationContext).also {
                wm.addView(it, lp)
                manager = wm
                border = it
            }
        } catch (_: Exception) { null }
    }

    fun dismiss() {
        generation++
        border?.let { try { manager?.removeViewImmediate(it) } catch (_: Exception) { } }
        border = null
        manager = null
    }
}
