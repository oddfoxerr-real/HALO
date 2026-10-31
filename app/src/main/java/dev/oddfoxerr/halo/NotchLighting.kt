package dev.oddfoxerr.halo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.abs
import kotlin.math.min

enum class NotchMode(val title: String) {
    OFF("Off"), AUTO("Auto"), CIRCLE("Camera ring"), PILL("Camera pill")
}

data class NotchSettings(
    val mode: NotchMode = NotchMode.OFF,
    val xOffsetDp: Float = 0f,
    val topDp: Float = 24f,
    val widthDp: Float = 30f,
    val heightDp: Float = 30f,
    val gapDp: Float = 4f
) {
    fun normalized() = copy(
        xOffsetDp = xOffsetDp.coerceIn(-120f, 120f),
        topDp = topDp.coerceIn(0f, 125f),
        widthDp = widthDp.coerceIn(12f, 160f),
        heightDp = heightDp.coerceIn(12f, 90f),
        gapDp = gapDp.coerceIn(0f, 24f)
    )
}

internal object NotchGeometry {
    fun manual(width: Int, density: Float, input: NotchSettings): RectF {
        val s = input.normalized()
        val w = (s.widthDp + 2f * s.gapDp) * density
        val h = if (s.mode == NotchMode.CIRCLE) w else (s.heightDp + 2f * s.gapDp) * density
        val cx = width / 2f + s.xOffsetDp * density
        val cy = s.topDp * density
        return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    fun find(view: View, input: NotchSettings): Pair<RectF, Boolean>? {
        val settings = input.normalized()
        if (settings.mode == NotchMode.OFF) return null
        if (settings.mode != NotchMode.AUTO) {
            return manual(view.width, view.resources.displayMetrics.density, settings) to
                (settings.mode == NotchMode.CIRCLE)
        }
        val cutout = view.rootWindowInsets?.displayCutout ?: return null
        val screenWidth = view.resources.displayMetrics.widthPixels
        val screenHeight = view.resources.displayMetrics.heightPixels
        val rect = cutout.boundingRects.filter { it.top < screenHeight * .3f }
            .minByOrNull { abs(it.centerX() - screenWidth / 2) } ?: return null
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val density = view.resources.displayMetrics.density
        val gap = settings.gapDp * density
        val expanded = RectF(rect.left - location[0] - gap, rect.top - location[1] - gap,
            rect.right - location[0] + gap, rect.bottom - location[1] + gap)
        return expanded to (abs(expanded.width() - expanded.height()) < 10f * density)
    }
}

/** Independent light halo; the physical camera interior is never filled or obscured. */
internal class NotchLightRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val segment = Path()
    private val measure = PathMeasure()
    private val matrix = Matrix()
    private var gradient: SweepGradient? = null
    private var geometryKey = ""
    private var gradientKey = ""
    private val bounds = RectF()
    private val location = IntArray(2)

    fun draw(
        canvas: Canvas, host: View, settings: NotchSettings,
        primary: Int, accent: Int, brightness: Float, glow: Float,
        energy: Float, phase: Float
    ) {
        val placement = NotchGeometry.find(host, settings) ?: return
        val rect = placement.first
        if (rect.width() <= 2f || rect.height() <= 2f) return
        val density = host.resources.displayMetrics.density
        val key = rect.toString() + placement.second.toString()
        if (key != geometryKey) {
            geometryKey = key
            bounds.set(rect)
            path.reset()
            if (placement.second) {
                path.addOval(bounds, Path.Direction.CW)
            } else {
                val radius = min(bounds.width(), bounds.height()) / 2f
                path.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            }
            measure.setPath(path, true)
            gradientKey = ""
        }
        val colorKey = key + primary + ":" + accent
        if (colorKey != gradientKey) {
            gradientKey = colorKey
            gradient = SweepGradient(bounds.centerX(), bounds.centerY(),
                intArrayOf(primary, accent, primary, Color.WHITE, accent, primary), null)
        }
        val active = energy.coerceIn(0f, 1f)
        val opacity = brightness.coerceIn(.2f, 1f)
        val soft = glow.coerceIn(0f, 1f)
        val centerWidth = (1.6f + active * 3.5f) * density
        matrix.setRotate(phase * 360f, bounds.centerX(), bounds.centerY())
        gradient?.setLocalMatrix(matrix)
        paint.shader = gradient
        val widths = floatArrayOf(14f, 8f, 3f, .7f)
        val alphas = floatArrayOf(.06f, .16f, .42f, .92f)
        for (index in widths.indices) {
            paint.strokeWidth = centerWidth + widths[index] * density * (if (index == 0) soft else 1f)
            paint.alpha = (255f * alphas[index] * opacity * (.7f + active * .3f)).toInt().coerceIn(0, 255)
            canvas.drawPath(path, paint)
        }
        if (active > .02f && measure.length > 0f) {
            val start = ((phase * .9f + .16f) % 1f) * measure.length
            val end = start + measure.length * (.1f + active * .12f)
            segment.reset()
            measure.getSegment(start, min(end, measure.length), segment, true)
            if (end > measure.length) measure.getSegment(0f, end - measure.length, segment, true)
            paint.shader = null
            paint.color = Color.WHITE
            paint.strokeWidth = (1f + active) * density
            paint.alpha = (160f * opacity * active).toInt().coerceIn(0, 255)
            canvas.drawPath(segment, paint)
        }
        paint.shader = null
        paint.color = Color.WHITE
        paint.alpha = 255
    }
}
