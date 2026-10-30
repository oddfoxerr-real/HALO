package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Low-overdraw Muviz-style edge visualizer.
 * Preserves the existing public class name so saved projects and activities can upgrade safely.
 * Audio callbacks set targets; rendering interpolates on screen refresh until the signal decays.
 */
class ChainsawView(context: Context) : View(context) {
    val motor = ChainDynamics()
    var thickness = 1f
    var glow = .6f
    var mode: EdgeMode = EdgeMode.WAVE
        set(value) { field = value; invalidate() }
    var edgeColor: Int = Color.rgb(170, 91, 255)
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bounds = RectF()
    private val perimeter = Path()
    private val wave = Path()
    private val segment = Path()
    private val measure = PathMeasure()
    private val point = FloatArray(2)
    private val tangent = FloatArray(2)
    private val bands = FloatArray(EdgeSpectrum.BAND_COUNT)
    private val targets = FloatArray(EdgeSpectrum.BAND_COUNT)
    private var targetPulse = 0f
    private var pulse = 0f
    private var phase = 0f
    private var lastFrameMs = 0L
    private var lastAudioMs = 0L
    private var sensitivityValue = 1f
    private var speedValue = 1f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Keep FFT capture independent from display refresh and avoid unbounded queued frames. */
    fun updateSpectrum(fftBands: FloatArray, bass: Float, dtSeconds: Float, sensitivity: Float, speed: Float) {
        sensitivityValue = sensitivity.coerceIn(.5f, 5f)
        speedValue = speed.coerceIn(.25f, 3f)
        for (i in targets.indices) {
            targets[i] = ((fftBands.getOrElse(i) { 0f }) * sensitivityValue * .75f).coerceIn(0f, 1f)
        }
        targetPulse = (bass * sensitivityValue).coerceIn(0f, 1f)
        lastAudioMs = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        lastFrameMs = 0L
        super.onDetachedFromWindow()
    }

    private fun advanceFrame(): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 1f / 60f
            else ((now - lastFrameMs) / 1000f).coerceIn(0f, .05f)
        lastFrameMs = now
        val fresh = now - lastAudioMs < 300L
        val rise = 1f - exp(-dt * 22f)
        val fall = 1f - exp(-dt * 8f)
        var strongest = 0f
        for (i in bands.indices) {
            val target = if (fresh) targets[i] else 0f
            val response = if (target > bands[i]) rise else fall
            bands[i] += (target - bands[i]) * response
            if (bands[i] < .0025f) bands[i] = 0f
            strongest = max(strongest, bands[i])
        }
        val targetBass = if (fresh) targetPulse else 0f
        pulse += (targetBass - pulse) * if (targetBass > pulse) rise else fall
        if (pulse < .0025f) pulse = 0f
        motor.step(targetBass, dt, sensitivityValue, speedValue)
        if (fresh && targetBass > .008f) {
            phase = (phase + dt * 1.15f * speedValue) % 1f
        }
        return (fresh && targets.any { it > .006f }) || strongest > .008f ||
            pulse > .008f || motor.speed > .02f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val keepAnimating = advanceFrame()
        val unit = density * thickness.coerceIn(.6f, 1.6f)
        val pad = 13f * unit
        bounds.set(pad, pad, width - pad, height - pad)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        perimeter.reset()
        perimeter.addRoundRect(bounds, 32f * density, 32f * density, Path.Direction.CW)
        measure.setPath(perimeter, true)
        val length = measure.length
        if (length <= 0f) return

        when (mode) {
            EdgeMode.WAVE -> drawWave(canvas, length, unit)
            EdgeMode.BARS -> drawBars(canvas, length, unit)
            EdgeMode.TRAIL -> drawTrail(canvas, length, unit)
            EdgeMode.CHAIN -> drawMechanical(canvas, length, unit)
        }
        paint.alpha = 255
        if (keepAnimating && isAttachedToWindow && visibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun drawWave(canvas: Canvas, length: Float, unit: Float) {
        wave.reset()
        val points = 112
        for (i in 0..points) {
            val distance = length * i / points
            measure.getPosTan(distance.coerceAtMost(length - .01f), point, tangent)
            val spectrum = bands[(i * (bands.size - 1) / points)]
            val displacement = (2f + spectrum * 24f + pulse * 4f) * unit
            val nx = -tangent[1]
            val ny = tangent[0]
            val x = point[0] + nx * displacement
            val y = point[1] + ny * displacement
            if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        wave.close()
        stroke(canvas, wave, edgeColor, 11f * unit, (glow * 42).toInt())
        stroke(canvas, wave, edgeColor, 5f * unit, 105)
        stroke(canvas, wave, Color.WHITE, 1.4f * unit, 215)
    }

    private fun drawBars(canvas: Canvas, length: Float, unit: Float) {
        stroke(canvas, perimeter, edgeColor, unit, 74)
        val count = 88
        paint.style = Paint.Style.STROKE
        paint.color = edgeColor
        for (i in 0 until count) {
            measure.getPosTan(length * i / count, point, tangent)
            val value = bands[(i * bands.size / count).coerceAtMost(bands.lastIndex)]
            val height = (2f + value * 25f) * unit
            paint.strokeWidth = max(1.5f, 2.6f * unit)
            paint.alpha = (64f + 175f * value).toInt().coerceIn(0, 255)
            canvas.drawLine(
                point[0] - tangent[1] * 3f * unit,
                point[1] + tangent[0] * 3f * unit,
                point[0] - tangent[1] * (height + 3f * unit),
                point[1] + tangent[0] * (height + 3f * unit),
                paint
            )
        }
    }

    private fun drawTrail(canvas: Canvas, length: Float, unit: Float) {
        stroke(canvas, perimeter, edgeColor, 1.6f * unit, 100)
        val start = phase * length
        val trailLength = length * (.14f + pulse * .12f)
        for (part in 0 until 12) {
            val from = (start - (part + 1) * trailLength / 12f + length * 2f) % length
            val to = (from + trailLength / 12f)
            segment.reset()
            measure.getSegment(from, to.coerceAtMost(length), segment, true)
            if (to > length) measure.getSegment(0f, to - length, segment, true)
            stroke(canvas, segment, edgeColor, (3f + pulse * 2f) * unit, ((12 - part) * 17).coerceIn(0, 255))
        }
    }

    private fun drawMechanical(canvas: Canvas, length: Float, unit: Float) {
        stroke(canvas, perimeter, edgeColor, 5f * unit, 72)
        val count = (length / (22f * unit)).toInt().coerceIn(12, 120)
        paint.style = Paint.Style.STROKE
        val progress = motor.travel * density
        for (i in 0 until count) {
            val d = (length * i / count + progress) % length
            measure.getPosTan(d, point, tangent)
            val angle = atan2(tangent[1], tangent[0])
            val dx = sin(angle) * 6f * unit
            val dy = -kotlin.math.cos(angle) * 6f * unit
            paint.color = if (i % 4 == 0) edgeColor else Color.rgb(176, 183, 197)
            paint.strokeWidth = 3f * unit
            paint.alpha = (95f + pulse * 130f).toInt().coerceIn(0, 255)
            canvas.drawLine(point[0] - dx, point[1] - dy, point[0] + dx, point[1] + dy, paint)
        }
    }

    private fun stroke(canvas: Canvas, path: Path, color: Int, widthPx: Float, opacity: Int) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.alpha = opacity.coerceIn(0, 255)
        paint.strokeWidth = widthPx
        canvas.drawPath(path, paint)
    }
}
