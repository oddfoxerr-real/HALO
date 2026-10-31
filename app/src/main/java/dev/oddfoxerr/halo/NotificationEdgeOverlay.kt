package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.SweepGradient
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
import android.view.Gravity
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
    private val extraEffects = NotificationExtraEffects()
    private val notchRenderer = NotchLightRenderer()
    private var notchSettings = NotchSettings()
    private val gradientMatrix = Matrix()
    private var edgeGradient: SweepGradient? = null
    private var edgeGradientKey = ""
    private val rect = RectF()
    var tint: Int = Color.rgb(170, 91, 255)
    var thicknessDp: Float = 3f
    var glow: Float = .75f
    var insetLeft = 6f
    var insetRight = 6f
    var insetTop = 6f
    var insetBottom = 6f
    var cornerRadius = 35f
    var style: NotificationEdgeStyle = NotificationEdgeStyle.PULSE
    private var startedAt = 0L
    private var duration = 3500L
    private var animated = false
    private var repeating = false

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun configureFit(store: ProfileStore) {
        insetLeft = store.notificationBorderLeft
        insetRight = store.notificationBorderRight
        insetTop = store.notificationBorderTop
        insetBottom = store.notificationBorderBottom
        cornerRadius = store.notificationBorderCorner
        notchSettings = store.notchSettings()
        invalidate()
    }

    fun play(color: Int, thickness: Float, glowAmount: Float, newStyle: NotificationEdgeStyle, millis: Long) {
        repeating = false
        tint = color
        thicknessDp = thickness.coerceIn(1f, 8f)
        glow = glowAmount.coerceIn(0f, 1f)
        style = newStyle
        duration = millis.coerceIn(800L, 10000L)
        startedAt = SystemClock.uptimeMillis()
        animated = true
        invalidate()
    }

    fun keepAnimating() {
        // A retained notification must not leave a permanently frozen border.
        repeating = true
        animated = true
        startedAt = SystemClock.uptimeMillis()
        duration = 3500L
        invalidate()
    }

    // A persistent indicator loops visually but does not keep stealing music priority.
    val isAnimating: Boolean get() = animated && !repeating

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        rect.set(insetLeft * density, insetTop * density,
            width - insetRight * density, height - insetBottom * density)
        if (rect.width() <= 0 || rect.height() <= 0) return
        border.reset()
        val radius = (cornerRadius * density).coerceAtMost(minOf(rect.width(), rect.height()) / 2f)
        border.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val elapsed = (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
        val t = if (repeating) (elapsed % duration).toFloat() / duration
            else if (animated) (elapsed.toFloat() / duration).coerceIn(0f, 1f) else 1f
        val strength = if (!animated) 0f else when (style) {
            NotificationEdgeStyle.PULSE -> .70f + .30f * sin(t * 8f * Math.PI.toFloat()).let { kotlin.math.abs(it) }
            NotificationEdgeStyle.TRACE -> .85f
            NotificationEdgeStyle.GLOW -> if (repeating) .65f + .35f * kotlin.math.abs(sin(t * Math.PI.toFloat() * 2f)) else 1f - t * .55f
            NotificationEdgeStyle.COMET, NotificationEdgeStyle.DASH, NotificationEdgeStyle.SPARK,
            NotificationEdgeStyle.RIPPLE, NotificationEdgeStyle.AURORA, NotificationEdgeStyle.BEACON -> .68f
        }
        val gradientKey = width.toString() + ":" + height + ":" + tint
        if (edgeGradientKey != gradientKey) {
            edgeGradientKey = gradientKey
            edgeGradient = SweepGradient(width / 2f, height / 2f,
                intArrayOf(tint, Color.WHITE, tint, tint, Color.WHITE, tint), null)
        }
        gradientMatrix.setRotate(t * 300f, width / 2f, height / 2f)
        edgeGradient?.setLocalMatrix(gradientMatrix)
        paint.shader = edgeGradient
        val layers = floatArrayOf(20f, 11f, 5f, 0f)
        val opacities = floatArrayOf(.065f, .14f, .32f, .92f)
        for (index in layers.indices) {
            paint.alpha = (255f * opacities[index] * strength).toInt().coerceIn(0, 255)
            paint.strokeWidth = (layers[index] * glow + thicknessDp) * density
            canvas.drawPath(border, paint)
        }
        paint.shader = null

        measure.setPath(border, true)
        if (animated && style == NotificationEdgeStyle.TRACE) {
            val length = measure.length
            val start = (length * t * 1.4f) % length
            val end = start + length * .19f
            segment.reset()
            measure.getSegment(start, end.coerceAtMost(length), segment, true)
            if (end > length) measure.getSegment(0f, end - length, segment, true)
            paint.color = Color.WHITE
            paint.alpha = (if (repeating) 210f else (1f - t) * 210f).toInt().coerceIn(0, 255)
            paint.strokeWidth = (thicknessDp + 1.5f) * density
            canvas.drawPath(segment, paint)
        }
        if (animated) {
            notchRenderer.draw(canvas, this, notchSettings, tint, Color.WHITE,
                .94f, glow, strength, t)
        }
        if (animated && style != NotificationEdgeStyle.PULSE && style != NotificationEdgeStyle.TRACE && style != NotificationEdgeStyle.GLOW) {
            extraEffects.draw(style, canvas, measure, elapsed, density, tint, thicknessDp, glow)
        }
        if (animated && (repeating || elapsed < duration) && isAttachedToWindow) postInvalidateDelayed(33L)
        else if (!repeating && animated) animated = false
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
        view.configureFit(store)
        view.play(color.toInt(), store.notificationBorderThickness,
            store.notificationBorderGlow, store.notificationEdgeStyle, duration)
        handler.postDelayed({
            if (generation == id) {
                if (persistent) view.keepAnimating() else dismiss()
            }
        }, duration.coerceIn(800L, 10000L))
    }

    fun showStatic(context: Context, color: Long, store: ProfileStore) {
        if (isAnimating) return
        val view = ensure(context) ?: return
        view.configureFit(store)
        view.play(color.toInt(), store.notificationBorderThickness,
            store.notificationBorderGlow, store.notificationEdgeStyle, 3500L)
        view.keepAnimating()
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
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Combined with the narrow character window (0.40), remains below Android's
            // 0.80 obscuring-opacity threshold while making the perimeter visible.
            alpha = .55f
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
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
