package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.view.View
import kotlin.math.atan2
import kotlin.math.sin

/** Original mechanical chain artwork. No manga panels, logos, or downloaded assets. */
class ChainsawView(context: Context) : View(context) {
    val motor = ChainDynamics()
    var thickness = 1f
    var glow = .6f
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Path()
    private val tooth = Path()
    private val measure = PathMeasure()
    private val bounds = RectF()
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = density * thickness.coerceIn(.6f, 1.6f)
        val inset = 13f * unit
        bounds.set(inset, inset, width - inset, height - inset)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        border.reset()
        border.addRoundRect(bounds, 34f * density, 34f * density, Path.Direction.CW)
        measure.setPath(border, true)
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(255, 65, 24)
        for (layer in 3 downTo 1) {
            paint.strokeWidth = (6 + layer * 5) * unit
            paint.alpha = (glow * (12 + 22 * motor.kick) / layer).toInt().coerceIn(0, 255)
            canvas.drawPath(border, paint)
        }
        paint.alpha = 255
        paint.strokeWidth = 7 * unit
        paint.color = Color.rgb(56, 34, 31)
        canvas.drawPath(border, paint)
        paint.strokeWidth = 2 * unit
        paint.color = Color.rgb(186, 65, 36)
        canvas.drawPath(border, paint)
        val count = (measure.length / (18 * unit)).toInt().coerceIn(4, 400)
        val spacing = measure.length / count
        val stutter = sin(motor.travel * .7f) * motor.jam * motor.speed * 3f
        for (index in 0 until count) {
            val distance = (index * spacing + motor.travel * density + stutter + measure.length) % measure.length
            measure.getPosTan(distance, position, tangent)
            canvas.save()
            canvas.translate(position[0], position[1])
            canvas.rotate(Math.toDegrees(atan2(tangent[1], tangent[0]).toDouble()).toFloat())
            canvas.scale(unit, unit)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(118, 126, 131)
            canvas.drawRoundRect(-7f, -3.5f, 7f, 3.5f, 2f, 2f, paint)
            paint.color = Color.rgb(29, 31, 33)
            canvas.drawCircle(-4f, 0f, 1.4f, paint)
            canvas.drawCircle(4f, 0f, 1.4f, paint)
            tooth.reset()
            tooth.moveTo(-6f, 3f); tooth.lineTo(-3f, 11f)
            tooth.lineTo(6f, 7f); tooth.lineTo(2f, 3f); tooth.close()
            paint.color = Color.rgb(218, 224, 222)
            canvas.drawPath(tooth, paint)
            // Dark red resin visibly builds up as the chain clogs.
            if (index % 5 == 0) {
                paint.color = Color.argb((motor.jam * 215).toInt(), 112, 24, 19)
                canvas.drawOval(-7f, -4f, 5f, 7f + motor.jam * 3f, paint)
            }
            if (motor.kick > .08f && index % 7 == 0) {
                paint.color = Color.argb((motor.kick * 230).toInt(), 255, 169, 57)
                paint.strokeWidth = 1.2f
                canvas.drawLine(1f, 12f, 5f + index % 4, 13f + 18f * (1f - motor.kick), paint)
            }
            canvas.restore()
        }
        paint.alpha = 255
    }
}
