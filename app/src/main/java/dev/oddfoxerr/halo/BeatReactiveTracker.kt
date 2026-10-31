package dev.oddfoxerr.halo

import kotlin.math.exp
import kotlin.math.max

/** Adaptive spectral-onset detector. Pure Kotlin for deterministic unit tests. */
internal class BeatReactiveTracker {
    data class Result(val bass: Float, val onset: Float, val energy: Float)
    private val previous = FloatArray(EdgeSpectrum.BAND_COUNT)
    private var averageFlux = .025f
    private var averageLevel = .045f
    private var lastBeatAt = Long.MIN_VALUE
    private var initial = true

    fun reset() {
        previous.fill(0f)
        averageFlux = .025f
        averageLevel = .045f
        lastBeatAt = Long.MIN_VALUE
        initial = true
    }

    fun process(bands: FloatArray, rawBass: Float, timeMs: Long): Result {
        if (bands.isEmpty()) return Result(0f,0f,0f)
        val dt = 1f / 45f
        var flux = 0f
        var energy = 0f
        var lowEnergy = 0f
        for (i in previous.indices) {
            val value = bands.getOrElse(i) { 0f }.let { if (it.isFinite()) it.coerceIn(0f,1f) else 0f }
            val increase = max(0f,value - previous[i])
            flux += increase * if (i < 8) 1.65f else .65f
            energy += value
            if (i < 5) lowEnergy += value
            previous[i] = value
        }
        flux /= previous.size
        energy /= previous.size
        lowEnergy /= 5f
        val bass = max(rawBass.coerceIn(0f,1f),lowEnergy*.84f)
        if (initial) {
            averageLevel = max(energy,.025f)
            averageFlux = max(flux,.018f)
            initial = false
            return Result(bass,0f,energy)
        }
        // A low dynamic background is tracked separately from short transients.
        averageFlux += (flux-averageFlux)*(1f-exp(-dt*2.2f))
        averageLevel += (energy-averageLevel)*(1f-exp(-dt*1.5f))
        val threshold = max(.008f,averageFlux*1.38f)
        val ratio = ((flux-threshold)/max(.025f,threshold*.9f)).coerceIn(0f,1f)
        val eligible = lastBeatAt == Long.MIN_VALUE || timeMs-lastBeatAt >= 125L
        val onBeat = eligible && ratio > .14f && bass > .018f &&
            energy > averageLevel*.68f
        if (onBeat) lastBeatAt = timeMs
        return Result(bass,if(onBeat) (.40f + ratio*.60f).coerceIn(0f,1f) else 0f,energy)
    }
}
