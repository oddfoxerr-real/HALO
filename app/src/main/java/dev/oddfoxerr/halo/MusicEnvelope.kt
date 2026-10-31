package dev.oddfoxerr.halo

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Real audio follows fast attack / slower release, with a separately detected transient.
 * Beat detection happens on new FFT frames, NEVER on UI frames: UI refresh frequency cannot
 * invent beats or erase transients between capture callbacks.
 */
class MusicEnvelope {
    val bands = FloatArray(EdgeSpectrum.BAND_COUNT)
    private val targets = FloatArray(EdgeSpectrum.BAND_COUNT)
    private val previous = FloatArray(EdgeSpectrum.BAND_COUNT)
    private var sampleAt = Long.MIN_VALUE
    private var targetBass = 0f
    private var averageBass = .025f
    private var averageFlux = .012f
    private var beatTime = Long.MIN_VALUE
    private var pendingKick = 0f
    private var attackImpulse = 0f
    var pulse = 0f; private set
    var kick = 0f; private set
    var phase = 0f; private set
    val motor = ChainDynamics()

    fun accept(input: FloatArray, bass: Float, timeMs: Long, sensitivity: Float, onset: Float = 0f) {
        val gain = if (sensitivity.isFinite()) sensitivity.coerceIn(.5f, 5f) else 1f
        var flux = 0f
        var rawPeak = 0f
        for (i in targets.indices) {
            val raw = input.getOrElse(i) { 0f }.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            val positive = max(0f, raw - previous[i])
            if (i < 12) flux += positive * (if (i < 5) 1.35f else .65f)
            rawPeak = max(rawPeak, raw)
            previous[i] = raw
            // A softer response curve makes low amplitudes visible before hard clipping.
            targets[i] = (raw * gain).coerceIn(0f, 1f)
        }
        flux /= 12f
        val rawBass = if (bass.isFinite()) bass.coerceIn(0f, 1f) else 0f
        val surprise = max(0f, rawBass - averageBass)
        val onset = (flux > max(.018f, averageFlux * 1.65f) && rawPeak > .025f) ||
            (surprise > max(.037f, averageBass * .48f) && rawBass > .055f)
        val enoughTime = beatTime == Long.MIN_VALUE || timeMs - beatTime >= 155L
        if (onset && enoughTime) {
            // Strongest impacts remain dramatic at high sensitivity while avoiding a
            // permanently saturated beat during loud sustained sounds.
            val strength = (flux * 4.8f + surprise * 2.6f) * (gain * .55f + .45f)
            pendingKick = max(pendingKick, strength.coerceIn(.30f, 1f))
            beatTime = timeMs
        }
        averageBass += (rawBass - averageBass) * .075f
        averageFlux += (flux - averageFlux) * .09f
        targetBass = (rawBass * gain).coerceIn(0f, 1f)
        // Onsets bypass the slow band envelope for a visible attack on the exact beat.
        if (onset.isFinite()) attackImpulse = max(attackImpulse,
            (onset * (.55f + gain*.22f)).coerceIn(0f,1f))
        sampleAt = timeMs
    }

    fun clear() {
        sampleAt = Long.MIN_VALUE; targets.fill(0f); previous.fill(0f); bands.fill(0f)
        targetBass = 0f; averageBass = .025f; averageFlux = .012f
        beatTime = Long.MIN_VALUE; pendingKick = 0f
        pulse = 0f; kick = 0f; phase = 0f; motor.reset()
    }

    fun advance(timeMs: Long, dt: Float, speed: Float): Boolean {
        val time = if (dt.isFinite()) dt.coerceIn(0f, .05f) else 0f
        val fresh = sampleAt != Long.MIN_VALUE && timeMs - sampleAt in 0L..330L
        val rise = 1f - exp(-time * 34f)
        val fall = 1f - exp(-time * 13f)
        var peak = 0f
        for (i in bands.indices) {
            val target = if (fresh) targets[i] else 0f
            bands[i] += (target - bands[i]) * if (target > bands[i]) rise else fall
            if (bands[i] < .0025f) bands[i] = 0f
            peak = max(peak, bands[i])
        }
        val bass = if (fresh) targetBass else 0f
        if (pendingKick > .01f && fresh) {
            kick = max(kick, pendingKick)
            pendingKick = 0f
        }
        kick *= exp(-time * 9f)
        if (kick < .0025f) kick = 0f
        val energy = (bass * .48f + kick * .94f).coerceIn(0f, 1f)
        pulse += (energy - pulse) * if (energy > pulse) rise else fall
        if (pulse < .0025f) pulse = 0f
        motor.step(bass, time, 1f, speed)
        val movement = (peak * .8f + pulse).coerceAtMost(1f)
        phase = (phase + time * (.065f + .24f * movement) * speed) % 1f
        return peak > .003f || pulse > .003f || kick > .003f || motor.speed > .01f
    }
}
