package dev.oddfoxerr.halo

import kotlin.math.exp
import kotlin.math.max

/** Display interpolation never depends on FFT delivery continuing. No Android clock dependencies. */
class MusicEnvelope {
    val bands = FloatArray(EdgeSpectrum.BAND_COUNT)
    private val targets = FloatArray(EdgeSpectrum.BAND_COUNT)
    private var sampleAt = Long.MIN_VALUE
    private var targetBass = 0f
    var pulse = 0f; private set
    var beat = 0f; private set
    var phase = 0f; private set
    val motor = ChainDynamics()

    fun accept(input: FloatArray, bass: Float, timeMs: Long, sensitivity: Float, onset: Float = 0f) {
        val gain = if (sensitivity.isFinite()) sensitivity.coerceIn(.5f, 5f) else 1f
        for (i in targets.indices) targets[i] = input.getOrElse(i) { 0f }.let {
            if (it.isFinite()) (it * gain).coerceIn(0f, 1f) else 0f
        }
        targetBass = if (bass.isFinite()) (bass * gain).coerceIn(0f, 1f) else 0f
        if (onset.isFinite() && onset > .2f) beat = max(beat, onset.coerceIn(0f, 1f))
        sampleAt = timeMs
    }
    fun clear() {
        sampleAt = Long.MIN_VALUE; targets.fill(0f); bands.fill(0f)
        targetBass = 0f; pulse = 0f; beat = 0f; phase = 0f; motor.reset()
    }
    fun advance(timeMs: Long, dt: Float, speed: Float, response: Float = 1f): Boolean {
        val time = if (dt.isFinite()) dt.coerceIn(0f, .05f) else 0f
        val fresh = sampleAt != Long.MIN_VALUE && timeMs - sampleAt in 0L..300L
        val responsiveness = if (response.isFinite()) response.coerceIn(.5f, 2.5f) else 1f
        val rise = 1f - exp(-time * 64f * responsiveness)
        val fall = 1f - exp(-time * 13f * responsiveness)
        var peak = 0f
        for (i in bands.indices) {
            val target = if (fresh) targets[i] else 0f
            bands[i] += (target - bands[i]) * if (target > bands[i]) rise else fall
            if (bands[i] < .0025f) bands[i] = 0f
            peak = max(peak, bands[i])
        }
        val bass = if (fresh) targetBass else 0f
        pulse += (bass - pulse) * if (bass > pulse) rise else fall
        if (pulse < .0025f) pulse = 0f
        beat *= exp(-time * 9f * responsiveness)
        if (beat < .004f) beat = 0f
        motor.step(bass, time, 1f, speed) // gain applied once, at ingestion
        val movement = (peak * .6f + pulse).coerceAtMost(1f)
        phase = (phase + time * .20f * speed * movement) % 1f
        return peak > .003f || pulse > .003f || beat > .004f || motor.speed > .01f
    }
}
