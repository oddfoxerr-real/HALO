package dev.oddfoxerr.halo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Six native notification edge effects. Frames are driven by the owning border view and
 * never require an Activity or a separate animation timer to remain visible.
 */
internal class NotificationExtraEffects {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val segment = Path()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)

    fun draw(
        style: NotificationEdgeStyle,
        canvas: Canvas,
        measure: PathMeasure,
        elapsedMs: Long,
        density: Float,
        color: Int,
        thickness: Float,
        glow: Float
    ) {
        val length = measure.length
        if (length <= 0f) return
        // Use continuous rather than reset-on-cycle time; this keeps persistent indicators seamless.
        val seconds = (elapsedMs % 120000L) / 1000f
        when (style) {
            NotificationEdgeStyle.COMET -> {
                for (head in 0..1) {
                    val current = wrap(seconds * .13f + head * .5f)
                    for (tail in 16 downTo 0) {
                        val f = current - tail * .0075f
                        trace(measure, f, .009f)
                        val strength = 1f - tail / 17f
                        line(canvas, segment, thickness * density * (1.1f + strength * 1.3f),
                            (240f * strength).toInt(), color, glow, density)
                    }
                }
            }
            NotificationEdgeStyle.DASH -> {
                for (i in 0 until 40) {
                    trace(measure, i / 40f + seconds * .08f, .012f)
                    val beat = .5f + .5f * sin((i / 40f * 6f - seconds * 2.2f) * (2f * PI)).toFloat()
                    line(canvas, segment, thickness * density * (1f + beat * .8f),
                        (110f + beat * 145f).toInt(), color, glow, density)
                }
            }
            NotificationEdgeStyle.SPARK -> {
                // Deterministic sparks preserve their positions across frames and avoid strobing.
                for (i in 0 until 55) {
                    val fraction = wrap(i * .6180339f + seconds * .022f * (1 + i % 3))
                    measure.getPosTan(length * fraction, pos, tan)
                    val spark = max(0f, sin((seconds * (2f + i % 5) + i * .71f) * (2f * PI)).toFloat())
                    if (spark < .55f) continue
                    paint.shader = null
                    paint.style = Paint.Style.STROKE
                    paint.color = if (i % 5 == 0) Color.WHITE else color
                    paint.alpha = (255f * (spark - .45f)).toInt().coerceIn(0, 255)
                    paint.strokeWidth = (1f + spark) * density
                    val reach = (5f + spark * 13f) * density
                    canvas.drawLine(pos[0], pos[1],
                        pos[0] - tan[1] * reach, pos[1] + tan[0] * reach, paint)
                }
            }
            NotificationEdgeStyle.RIPPLE -> {
                for (layer in 0..2) {
                    val wave = sin((seconds * 1.25f + layer / 3f) * (2f * PI)).toFloat()
                    val opacity = (140 + (wave + 1f) * 50f).toInt().coerceIn(0, 255)
                    // Ripple offsets the contour uniformly instead of scaling the whole screen.
                    for (i in 0 until 16) {
                        val f = i / 16f
                        trace(measure, f, .052f)
                        canvas.save()
                        measure.getPosTan(length * wrap(f), pos, tan)
                        val shift = (layer * 2.5f + wave * 2f) * density
                        canvas.translate(-tan[1] * shift, tan[0] * shift)
                        line(canvas, segment, thickness * density * .7f,
                            opacity, color, glow * .65f, density)
                        canvas.restore()
                    }
                }
            }
            NotificationEdgeStyle.AURORA -> {
                for (layer in 0..2) {
                    for (i in 0 until 28) {
                        val u = i / 28f
                        val s = sin(((u * 4f) - seconds * .4f + layer * .33f) * (2f * PI)).toFloat()
                        val strength = (s + 1f) * .5f
                        trace(measure, u, .033f)
                        line(canvas, segment, (thickness * (.7f + layer * .15f) + strength * 2f) * density,
                            (70f + 155f * strength).toInt(), color, glow * .9f, density)
                    }
                }
            }
            NotificationEdgeStyle.BEACON -> {
                for (head in 0..3) {
                    val center = wrap(seconds * (if (head % 2 == 0) .11f else -.11f) + head / 4f)
                    for (trail in 0 until 15) {
                        trace(measure, center - trail * .007f, .008f)
                        val level = (1f - trail / 15f)
                        line(canvas, segment, thickness * density * (1f + level * 1.4f),
                            (245f * level).toInt(), if (head % 2 == 0) color else Color.WHITE,
                            glow * .8f, density)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun wrap(value: Float): Float = ((value % 1f) + 1f) % 1f

    private fun trace(measure: PathMeasure, start: Float, fractionLength: Float) {
        segment.reset()
        val length = measure.length
        val from = wrap(start) * length
        val end = from + fractionLength * length
        measure.getSegment(from, min(end, length), segment, true)
        if (end > length) measure.getSegment(0f, end - length, segment, true)
    }

    private fun line(
        canvas: Canvas,
        path: Path,
        width: Float,
        opacity: Int,
        color: Int,
        glow: Float,
        density: Float
    ) {
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.color = color
        val alpha = opacity.coerceIn(0, 255)
        if (glow > 0f) {
            paint.strokeWidth = width + glow * 6f * density
            paint.alpha = (alpha * glow * .28f).toInt().coerceIn(0, 255)
            canvas.drawPath(path, paint)
        }
        paint.strokeWidth = width
        paint.alpha = alpha
        canvas.drawPath(path, paint)
    }
}
