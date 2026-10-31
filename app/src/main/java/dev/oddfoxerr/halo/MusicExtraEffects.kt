package dev.oddfoxerr.halo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Ten original screen-perimeter music designs.
 * All drawing is deterministic from the smoothed spectrum and motion phase.
 * Reuses Paths, arrays and Paint to avoid per-frame allocations in the render loop.
 */
internal class MusicExtraEffects {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val segment = Path()
    private val ribbon = Path()
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)
    private var measure: PathMeasure = PathMeasure()
    private var motion: MusicEnvelope = MusicEnvelope()
    private var shader: Shader? = null
    private var gradientKey = ""
    private var density = 1f
    private var unit = 1f
    private var brightness = 1f
    private var glow = 0f
    private var mainColor = Color.WHITE
    private var accentColor = Color.WHITE

    fun draw(
        mode: EdgeMode,
        canvas: Canvas,
        path: Path,
        pathMeasure: PathMeasure,
        envelope: MusicEnvelope,
        width: Int,
        height: Int,
        pixelDensity: Float,
        scale: Float,
        light: Float,
        glowAmount: Float,
        primary: Int,
        secondary: Int
    ) {
        measure = pathMeasure
        motion = envelope
        density = pixelDensity
        unit = density * scale
        brightness = light.coerceIn(.2f, 1f)
        glow = glowAmount.coerceIn(0f, 1f)
        mainColor = primary
        accentColor = secondary
        val key = "$width:$height:$primary:$secondary"
        if (key != gradientKey) {
            gradientKey = key
            shader = SweepGradient(width * .5f, height * .5f,
                intArrayOf(primary, secondary, primary, secondary, primary), null)
        }
        when (mode) {
            EdgeMode.AURORA -> aurora(canvas, path)
            EdgeMode.COMET -> comet(canvas, path)
            EdgeMode.TWIN -> twins(canvas, path)
            EdgeMode.SPARKS -> sparks(canvas)
            EdgeMode.PIXELS -> pixels(canvas)
            EdgeMode.NEON -> neon(canvas, path)
            EdgeMode.RAIN -> rain(canvas)
            EdgeMode.HELIX -> helix(canvas)
            EdgeMode.BLOOM -> bloom(canvas, path)
            EdgeMode.PRISM -> prism(canvas, path)
            else -> Unit
        }
    }

    private fun signal(fraction: Float): Float {
        val bands = motion.bands
        val normalized = wrap(fraction) * (bands.size - 1)
        val lo = normalized.toInt().coerceIn(0, bands.lastIndex)
        val hi = min(lo + 1, bands.lastIndex)
        val mix = normalized - lo
        return bands[lo] * (1f - mix) + bands[hi] * mix
    }

    private fun wrap(value: Float) = ((value % 1f) + 1f) % 1f

    private fun at(fraction: Float) {
        measure.getPosTan(measure.length * wrap(fraction), position, tangent)
    }

    private fun trace(start: Float, span: Float) {
        segment.reset()
        val total = measure.length
        if (total <= 0f) return
        val s = wrap(start) * total
        val end = s + span.coerceIn(0f, 1f) * total
        measure.getSegment(s, min(end, total), segment, true)
        if (end > total) measure.getSegment(0f, end - total, segment, true)
    }

    private fun stroke(canvas: Canvas, path: Path, width: Float, opacity: Int, halo: Boolean = false) {
        val alpha = (opacity * brightness).toInt().coerceIn(0, 255)
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.shader = shader
        if (halo && glow > 0f) {
            paint.strokeWidth = width + 8f * density * glow
            paint.alpha = (alpha * .32f * glow).toInt().coerceIn(0, 255)
            canvas.drawPath(path, paint)
        }
        paint.strokeWidth = width
        paint.alpha = alpha
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    private fun aurora(canvas: Canvas, border: Path) {
        stroke(canvas, border, 1.1f * unit, 95, true)
        for (layer in 0..2) {
            ribbon.reset()
            for (i in 0..112) {
                val u = i / 112f
                at(u)
                val loud = signal(u + motion.phase * .3f + layer * .16f)
                val wave = sin((u * 6f + motion.phase * 2.3f + layer * .31f) * (2f * PI.toFloat())).toFloat()
                val reach = (4f + loud * 17f + wave * (3f + motion.pulse * 9f) + layer * 3f) * unit
                val x = position[0] - tangent[1] * reach
                val y = position[1] + tangent[0] * reach
                if (i == 0) ribbon.moveTo(x, y) else ribbon.lineTo(x, y)
            }
            stroke(canvas, ribbon, (1.4f + layer * .45f) * unit, 220 - layer * 42, true)
        }
    }

    private fun comet(canvas: Canvas, border: Path) {
        stroke(canvas, border, 1.2f * unit, 75)
        for (head in 0..2) {
            val center = wrap(motion.phase * (1f + head * .17f) + head / 3f)
            for (tail in 16 downTo 0) {
                val u = center - tail * .008f
                trace(u, .011f)
                val energy = .48f + signal(center + head * .13f) * .52f
                val fade = 1f - tail / 17f
                stroke(canvas, segment, (1.8f + 3f * fade * (motion.pulse + .3f)) * unit,
                    (245f * fade * energy).toInt(), tail < 4)
            }
        }
    }

    private fun twins(canvas: Canvas, border: Path) {
        stroke(canvas, border, unit, 75)
        for (direction in 0..1) {
            val center = wrap(if (direction == 0) motion.phase else 1f - motion.phase + .5f)
            for (part in 0..24) {
                val f = center - (part * .006f * if (direction == 0) 1f else -1f)
                trace(f, .007f)
                val strength = signal(f + direction * .23f)
                val trail = 1f - part / 25f
                stroke(canvas, segment, (1.2f + 4f * strength) * unit,
                    (215f * trail + 15f).toInt(), part < 5)
            }
        }
    }

    private fun sparks(canvas: Canvas) {
        for (i in 0 until 68) {
            val anchor = wrap(i * .6180339f + motion.phase * (.1f + i % 3 * .15f))
            at(anchor)
            val strength = signal(i / 68f + motion.phase * .2f)
            val flash = max(0f, sin((i * 1.61f + motion.phase * 12f) * (2f * PI.toFloat())).toFloat())
            val magnitude = (strength * .80f + motion.pulse * .20f) * (.35f + flash * .65f)
            if (magnitude < .025f) continue
            val offset = (2f + 24f * magnitude) * unit
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = if (i % 3 == 0) accentColor else mainColor
            paint.alpha = (brightness * (85f + 170f * magnitude)).toInt().coerceIn(0, 255)
            canvas.drawCircle(position[0] - tangent[1] * offset,
                position[1] + tangent[0] * offset, (1f + magnitude * 2.8f) * unit, paint)
        }
    }

    private fun pixels(canvas: Canvas) {
        for (i in 0 until 112) {
            val f = wrap(i / 112f + motion.phase * .12f)
            at(f)
            val level = (signal(f) * 5f + motion.pulse * 1.5f).toInt().coerceIn(0, 6)
            val cells = max(1, level)
            canvas.save()
            canvas.translate(position[0], position[1])
            canvas.rotate(Math.toDegrees(kotlin.math.atan2(tangent[1].toDouble(), tangent[0].toDouble())).toFloat())
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = if (i % 5 == 0) accentColor else mainColor
            paint.alpha = (brightness * (120 + level * 21)).toInt().coerceIn(0, 255)
            for (j in 0 until cells) {
                val y = (2f + j * 4f) * unit
                canvas.drawRoundRect(-2.1f * unit, y, 2.1f * unit, y + 2.4f * unit,
                    .8f * unit, .8f * unit, paint)
            }
            canvas.restore()
        }
    }

    private fun neon(canvas: Canvas, border: Path) {
        stroke(canvas, border, (1.6f + 1.6f * motion.pulse) * unit, 230, true)
        for (i in 0..7) {
            val f = wrap(i / 8f + motion.phase)
            val pulse = signal(f + i * .09f)
            trace(f, .028f + .028f * pulse)
            stroke(canvas, segment, (2.5f + 3.2f * pulse) * unit,
                (120f + 135f * pulse).toInt(), true)
        }
    }

    private fun rain(canvas: Canvas) {
        for (i in 0 until 84) {
            val fraction = wrap(i / 84f + motion.phase * (0.35f + i % 4 * .11f))
            at(fraction)
            val frequency = signal(fraction + i * .05f)
            val length = (3.5f + frequency * 20f + motion.pulse * 9f) * unit
            paint.style = Paint.Style.STROKE
            paint.shader = null
            paint.strokeWidth = (1.4f + frequency) * unit
            paint.color = if (i % 4 == 0) accentColor else mainColor
            paint.alpha = (brightness * (75f + frequency * 180f)).toInt().coerceIn(0, 255)
            canvas.drawLine(position[0] - tangent[1] * 2f * unit,
                position[1] + tangent[0] * 2f * unit,
                position[0] - tangent[1] * length,
                position[1] + tangent[0] * length, paint)
        }
    }

    private fun helix(canvas: Canvas) {
        for (strand in 0..1) {
            ribbon.reset()
            for (i in 0..128) {
                val fraction = i / 128f
                at(fraction)
                val loud = signal(fraction + motion.phase * .2f)
                val wave = sin((fraction * 16f + motion.phase * 3.5f) * (2f * PI.toFloat()) +
                    strand * PI.toFloat()).toFloat()
                val reach = (8f + wave * (5f + loud * 13f)) * unit
                val x = position[0] - tangent[1] * reach
                val y = position[1] + tangent[0] * reach
                if (i == 0) ribbon.moveTo(x, y) else ribbon.lineTo(x, y)
            }
            stroke(canvas, ribbon, (1.6f + motion.pulse * 1.8f) * unit,
                if (strand == 0) 235 else 170, true)
        }
    }

    private fun bloom(canvas: Canvas, border: Path) {
        stroke(canvas, border, 1.4f * unit, 110)
        for (i in 0 until 36) {
            val f = wrap(i / 36f + motion.phase * .09f)
            at(f)
            val intensity = signal(f + i * .02f) * .65f + motion.pulse * .35f
            if (intensity < .02f) continue
            paint.shader = null
            paint.color = if (i % 2 == 0) mainColor else accentColor
            paint.style = Paint.Style.STROKE
            for (ring in 0..1) {
                val radius = (2.2f + intensity * (7f + ring * 9f)) * unit
                paint.strokeWidth = (if (ring == 0) 1.7f else 1f) * unit
                paint.alpha = (brightness * intensity * (if (ring == 0) 225f else 115f)).toInt().coerceIn(0, 255)
                canvas.drawCircle(position[0], position[1], radius, paint)
            }
        }
    }

    private fun prism(canvas: Canvas, border: Path) {
        stroke(canvas, border, 1.3f * unit, 105)
        for (i in 0 until 48) {
            val frac = i / 48f
            val strength = signal(frac + motion.phase * .24f)
            trace(frac + motion.phase * .1f, .011f + strength * .006f)
            stroke(canvas, segment, (1.5f + strength * 5.5f) * unit,
                (130 + 125 * strength).toInt(), strength > .45f)
        }
        for (i in 0 until 12) {
            val f = wrap(i / 12f + motion.phase)
            at(f)
            val magnitude = (motion.pulse * .65f + signal(f) * .35f).coerceIn(0f, 1f)
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = if (i % 2 == 0) accentColor else mainColor
            paint.alpha = (brightness * (90f + 165f * magnitude)).toInt().coerceIn(0, 255)
            val radius = (1.5f + magnitude * 2.5f) * unit
            canvas.drawCircle(position[0], position[1], radius, paint)
        }
    }
}
