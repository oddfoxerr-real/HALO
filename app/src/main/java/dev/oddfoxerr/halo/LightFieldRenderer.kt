package dev.oddfoxerr.halo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Glass/neon volumetric-looking edge lighting composed from translucent filled ribbons.
 * It is original procedural geometry, not line-sprite artwork from another application.
 * Cached arrays, path and shaders keep allocations out of the 60fps frame path.
 */
internal class LightFieldRenderer {
    private val count = 144
    private val xs = FloatArray(count + 1)
    private val ys = FloatArray(count + 1)
    private val nx = FloatArray(count + 1)
    private val ny = FloatArray(count + 1)
    private val intensity = FloatArray(count + 1)
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
    private val ribbon = Path()
    private val transform = Matrix()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; isDither = true }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; isDither = true }
    private var field: SweepGradient? = null
    private var shaderKey = ""
    private val corona = RadialGradient(0f, 0f, 1f,
        intArrayOf(Color.WHITE, 0x88FFFFFF.toInt(), Color.TRANSPARENT),
        floatArrayOf(0f, .28f, 1f), Shader.TileMode.CLAMP)

    fun draw(
        canvas: Canvas,
        perimeter: PathMeasure,
        envelope: MusicEnvelope,
        mode: EdgeMode,
        width: Int,
        height: Int,
        density: Float,
        thickness: Float,
        spread: Float,
        glow: Float,
        brightness: Float,
        primary: Int,
        secondary: Int
    ) {
        if (perimeter.length <= 0f || width <= 0 || height <= 0) return
        val bands = envelope.bands
        val bass = envelope.pulse.coerceIn(0f, 1f)
        val phase = envelope.phase
        val d = density * thickness.coerceIn(.6f, 3f)
        val expansion = spread.coerceIn(.3f, 2.8f)
        val key = width.toString() + ":" + height + ":" + primary + ":" + secondary
        if (key != shaderKey) {
            shaderKey = key
            field = SweepGradient(width / 2f, height / 2f,
                intArrayOf(primary, secondary, primary, Color.WHITE, secondary, primary), null)
        }
        transform.setRotate(phase * 360f, width / 2f, height / 2f)
        field?.setLocalMatrix(transform)

        // Smooth neighboring FFT bands. The silhouette therefore flows without a jagged,
        // stair-stepped outline while remaining responsive to actual captured audio.
        for (i in 0..count) {
            val f = i / count.toFloat()
            perimeter.getPosTan(perimeter.length * (if (i == count) .999999f else f), pos, tan)
            xs[i] = pos[0]
            ys[i] = pos[1]
            nx[i] = -tan[1]
            ny[i] = tan[0]
            val u = ((f + phase * .18f) % 1f) * (bands.size - 1)
            val idx = u.toInt().coerceIn(0, bands.lastIndex)
            val next = min(idx + 1, bands.lastIndex)
            val prev = max(idx - 1, 0)
            val blend = u - idx
            val current = bands[idx] * (1f - blend) + bands[next] * blend
            val smooth = current * .72f + bands[prev] * .13f + bands[next] * .15f
            intensity[i] = effectEnergy(mode, f, phase, smooth, bass).coerceIn(0f, 1f)
        }

        // Each halo is a filled translucent volume, not a stroked path. An inner
        // white-light nucleus sits over progressively wider, dimmer color volumes.
        val widths = floatArrayOf(19f, 11f, 6f, 2.4f)
        val alphas = floatArrayOf(.075f, .13f, .27f, .68f)
        val global = brightness.coerceIn(.2f, 1f)
        val soft = glow.coerceIn(0f, 1f)
        for (layer in widths.indices) {
            ribbon.reset()
            val fuzz = widths[layer] * density * expansion *
                (if (layer == 0) (.3f + soft * .7f) else 1f)
            for (i in 0..count) {
                val energy = intensity[i]
                val widen = fuzz + (1f + 16f * energy) * d * expansion
                val x = xs[i] + nx[i] * widen
                val y = ys[i] + ny[i] * widen
                if (i == 0) ribbon.moveTo(x, y) else ribbon.lineTo(x, y)
            }
            for (i in count downTo 0) {
                val energy = intensity[i]
                val inward = (fuzz * .70f + (1f + 5f * energy) * d * expansion)
                ribbon.lineTo(xs[i] - nx[i] * inward, ys[i] - ny[i] * inward)
            }
            ribbon.close()
            fill.shader = field
            val energyBoost = .66f + .34f * bass
            fill.alpha = (255f * alphas[layer] * global * energyBoost).toInt().coerceIn(0, 255)
            canvas.drawPath(ribbon, fill)
        }

        // Tiny spectral bloom sources make lights feel layered rather than hand-drawn.
        // A single cached radial shader is scaled for each glint; no new bitmap or shader.
        highlight.shader = corona
        for (i in 0 until 18) {
            val index = (i * 8 + 3) % count
            val strength = intensity[index]
            if (strength < .055f) continue
            val f = index / count.toFloat()
            val flicker = (.65f + .35f * sin((f * 11f + phase * 2.7f) * 6.283185f))
            val size = (3f + strength * 9f) * density * expansion
            val cx = xs[index] + nx[index] * (2f + strength * 7f) * d
            val cy = ys[index] + ny[index] * (2f + strength * 7f) * d
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(size, size)
            highlight.alpha = (170f * global * strength * flicker).toInt().coerceIn(0, 255)
            canvas.drawCircle(0f, 0f, 1f, highlight)
            canvas.restore()
        }
        fill.shader = null
        fill.alpha = 255
        highlight.shader = null
        highlight.alpha = 255
    }

    private fun effectEnergy(mode: EdgeMode, f: Float, phase: Float, amplitude: Float, bass: Float): Float {
        val waves = sin((f * 4f - phase * 2f) * 6.283185f) * .5f + .5f
        val shorter = sin((f * 12f + phase * 1.5f) * 6.283185f) * .5f + .5f
        val distance = abs(((f - phase + 1.5f) % 1f) - .5f)
        val trail = exp(-distance * distance * 95f)
        return when (mode) {
            EdgeMode.WAVE -> amplitude * .75f + bass * .2f + waves * .12f
            EdgeMode.BARS -> amplitude * .9f + bass * .14f
            EdgeMode.TRAIL -> trail * (.4f + bass * .8f) + amplitude * .12f
            EdgeMode.PULSE -> bass * .85f + amplitude * .2f
            EdgeMode.CHAIN -> bass * .42f + amplitude * .25f + shorter * .09f
            EdgeMode.AURORA -> amplitude * .53f + waves * .4f + bass * .14f
            EdgeMode.COMET -> trail * (.55f + bass * .7f) + amplitude * .14f
            EdgeMode.TWIN -> max(trail, exp(-abs(distance - .5f) * 15f)) * (.4f + bass * .7f)
            EdgeMode.SPARKS -> amplitude * .42f + shorter * bass * .55f
            EdgeMode.PIXELS -> amplitude * .8f + shorter * .17f
            EdgeMode.NEON -> amplitude * .65f + bass * .3f
            EdgeMode.RAIN -> amplitude * .8f + shorter * bass * .18f
            EdgeMode.HELIX -> amplitude * .50f + shorter * .37f + bass * .16f
            EdgeMode.BLOOM -> bass * .54f + waves * amplitude * .5f
            EdgeMode.PRISM -> amplitude * .62f + shorter * .29f + bass * .12f
        }
    }
}
