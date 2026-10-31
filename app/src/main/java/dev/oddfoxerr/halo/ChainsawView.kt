package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.*

/** Shared HALO music renderer. Chainsaw is one design, not the music system itself. */
class ChainsawView(context: Context) : View(context) {
    private val motion = MusicEnvelope()
    var thickness = 1f
    var glow = .65f
    var mode = EdgeMode.WAVE
    var edgeColor = 0xFF9A7BFF.toInt()
    var accentColor = 0xFF52E0CB.toInt()
    var edgeInset = 8f
    var cornerRadius = 32f
    private var speed = 1f
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val perimeter = Path()
    private val wave = Path()
    private val segment = Path()
    private val tooth = Path()
    private val bounds = RectF()
    private val measure = PathMeasure()
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)
    private val coordinates = FloatArray(226)
    private var geometryKey = ""
    private var shaderKey = ""
    private var gradient: Shader? = null
    private var lastFrame = 0L
    private val renderLoop = MusicRenderLoop()
    private val choreographer by lazy { Choreographer.getInstance() }
    private val redraw = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!canRender()) { stopFrames(); return }
            if (renderLoop.onFrame(frameTimeNanos / 1_000_000L)) invalidate()
            else choreographer.postFrameCallback(this)
        }
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun configure(settings: MusicConfiguration) {
        mode = settings.mode; edgeColor = settings.color; accentColor = settings.accent
        thickness = settings.thickness; glow = settings.glow; speed = settings.speed
        edgeInset = settings.inset; cornerRadius = settings.corner
        invalidate()
    }
    fun clearSignal() { motion.clear(); stopFrames(); invalidate() }
    fun updateSpectrum(bands: FloatArray, bass: Float, sensitivity: Float, rate: Float) {
        speed = rate
        motion.accept(bands, bass, SystemClock.uptimeMillis(), sensitivity)
        requestFrame()
    }
    private fun requestFrame() {
        if (canRender() && renderLoop.request()) choreographer.postFrameCallback(redraw)
    }
    private fun canRender() = isShown && isAttachedToWindow && windowVisibility == VISIBLE
    private fun stopFrames() {
        choreographer.removeFrameCallback(redraw)
        renderLoop.reset()
        lastFrame = 0L
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        stopFrames()
        if (visibility == VISIBLE) requestFrame()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        stopFrames()
        if (visibility == VISIBLE) requestFrame()
    }
    override fun onDetachedFromWindow() { clearSignal(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 1f / 60 else (now - lastFrame) / 1000f
        lastFrame = now
        val moving = motion.advance(now, dt, speed)
        val unit = density * thickness
        val key = "$width:$height:$edgeInset:$cornerRadius:$thickness"
        if (key != geometryKey) {
            geometryKey = key
            val pad = max(edgeInset * density, 4f * unit)
            bounds.set(pad, pad, width - pad, height - pad)
            if (bounds.width() <= 0 || bounds.height() <= 0) return
            perimeter.reset()
            val radius = min(cornerRadius * density, min(bounds.width(), bounds.height()) / 2f)
            perimeter.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            measure.setPath(perimeter, true)
        }
        val colors = "$width:$height:$edgeColor:$accentColor"
        if (colors != shaderKey) {
            shaderKey = colors
            gradient = SweepGradient(width / 2f, height / 2f, intArrayOf(edgeColor, accentColor, edgeColor, accentColor, edgeColor), null)
        }
        if (measure.length <= 0f) return
        when (mode) {
            EdgeMode.WAVE -> silk(canvas, unit)
            EdgeMode.BARS -> spectrum(canvas, unit)
            EdgeMode.TRAIL -> orbit(canvas, unit)
            EdgeMode.PULSE -> {
                stroke(canvas, perimeter, (1.5f + motion.pulse * 5f) * unit, (38 + motion.pulse * 215).toInt(), true)
            }
            EdgeMode.CHAIN -> chainsaw(canvas, unit)
        }
        paint.shader = null; paint.alpha = 255
        if (moving) requestFrame()
    }
    private fun amplitude(fraction: Float): Float {
        // Mirror the spectrum around the frame; interpolate between bands instead of stair-stepping.
        val value = abs(sin(fraction * PI * 2)).toFloat() * (motion.bands.size - 1)
        val low = value.toInt()
        return motion.bands[low] * (1 - (value - low)) + motion.bands[min(low + 1, motion.bands.lastIndex)] * (value - low)
    }
    private fun silk(canvas: Canvas, unit: Float) {
        val count = 112
        for (i in 0 until count) {
            measure.getPosTan(measure.length * i / count, position, tangent)
            val offset = (1f + amplitude(i.toFloat() / count) * 19f) * unit
            coordinates[i * 2] = position[0] - tangent[1] * offset
            coordinates[i * 2 + 1] = position[1] + tangent[0] * offset
        }
        wave.reset()
        wave.moveTo((coordinates[0] + coordinates[(count - 1) * 2]) / 2,
            (coordinates[1] + coordinates[(count - 1) * 2 + 1]) / 2)
        for (i in 0 until count) {
            val next = (i + 1) % count
            wave.quadTo(coordinates[i * 2], coordinates[i * 2 + 1],
                (coordinates[i * 2] + coordinates[next * 2]) / 2,
                (coordinates[i * 2 + 1] + coordinates[next * 2 + 1]) / 2)
        }
        wave.close()
        stroke(canvas, wave, 2.2f * unit, (55 + motion.bands.maxOrNull()!! * 200).toInt(), true)
    }
    private fun spectrum(canvas: Canvas, unit: Float) {
        stroke(canvas, perimeter, unit, 26)
        paint.style = Paint.Style.STROKE; paint.shader = gradient; paint.strokeWidth = 2.6f * unit
        for (i in 0 until 120) {
            val fraction = i / 120f
            measure.getPosTan(measure.length * fraction, position, tangent)
            val strength = amplitude(fraction)
            val length = (1.5f + strength * 25f) * unit
            paint.alpha = (35 + strength * 220).toInt()
            canvas.drawLine(position[0], position[1], position[0] - tangent[1] * length, position[1] + tangent[0] * length, paint)
        }
    }
    private fun orbit(canvas: Canvas, unit: Float) {
        stroke(canvas, perimeter, unit, 25)
        val length = measure.length
        for (head in 0..1) for (part in 0 until 20) {
            val start = ((motion.phase + head * .5f - part * .008f + 1f) % 1f) * length
            val end = start + length * .009f
            segment.reset()
            measure.getSegment(start, min(end, length), segment, true)
            if (end > length) measure.getSegment(0f, end - length, segment, true)
            stroke(canvas, segment, (2.2f + motion.pulse * 3) * unit,
                ((1f - part / 20f) * (55 + motion.pulse * 200)).toInt())
        }
    }
    private fun chainsaw(canvas: Canvas, unit: Float) {
        val motor = motion.motor
        stroke(canvas, perimeter, 5f * unit, 70, true)
        val count = (measure.length / (19f * unit)).toInt().coerceIn(6, 260)
        for (i in 0 until count) {
            measure.getPosTan((i * measure.length / count + motor.travel * density) % measure.length, position, tangent)
            canvas.save(); canvas.translate(position[0], position[1])
            canvas.rotate(Math.toDegrees(atan2(tangent[1], tangent[0]).toDouble()).toFloat())
            canvas.scale(unit, unit)
            paint.shader = null; paint.alpha = 255; paint.style = Paint.Style.FILL
            paint.color = Color.rgb(130, 143, 150)
            canvas.drawRoundRect(-6f, -2f, 6f, 2f, 2f, 2f, paint)
            tooth.reset(); tooth.moveTo(-4f, 2f); tooth.lineTo(-2f, 9f); tooth.lineTo(5f, 6f); tooth.lineTo(2f, 2f); tooth.close()
            paint.color = Color.rgb(210, 219, 221); canvas.drawPath(tooth, paint)
            if (i % 5 == 0) {
                paint.color = Color.argb((motor.jam * 205).toInt(), 117, 32, 28)
                canvas.drawOval(-6f, -3f, 5f, 6f, paint)
            }
            if (motor.kick > .1f && i % 8 == 0) {
                paint.color = Color.argb((motor.kick * 240).toInt(), 255, 181, 73); paint.strokeWidth = 1.2f
                canvas.drawLine(0f, 9f, 5f, 12f + 18f * (1 - motor.kick), paint)
            }
            canvas.restore()
        }
    }
    private fun stroke(canvas: Canvas, path: Path, width: Float, alpha: Int, halo: Boolean = false) {
        paint.style = Paint.Style.STROKE; paint.shader = gradient; paint.color = Color.WHITE
        if (halo && glow > 0f) {
            for (layer in 3 downTo 1) {
                paint.strokeWidth = width + layer * density * 3f
                paint.alpha = (alpha * glow * .18f / layer).toInt().coerceIn(0, 255)
                canvas.drawPath(path, paint)
            }
        }
        paint.strokeWidth = width; paint.alpha = alpha.coerceIn(0, 255); canvas.drawPath(path, paint)
    }
}
