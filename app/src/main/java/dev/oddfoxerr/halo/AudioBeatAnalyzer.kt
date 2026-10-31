package dev.oddfoxerr.halo

import kotlin.math.max
import kotlin.math.min

/**
 * Detects actual onsets from captured FFT frames, never a timed/fake BPM pulse.
 * Processing is worker-thread owned; its output is passed to the main-thread envelope.
 */
class AudioBeatAnalyzer {
    data class Result(val impulse: Float, val lowEnergy: Float, val signalPresent: Boolean)
    private val previous = FloatArray(EdgeSpectrum.BAND_COUNT)
    private var averageRise = .012f
    private var previousBass = 0f
    private var lastOnsetAt = -10000L

    fun reset() {
        previous.fill(0f)
        averageRise = .012f
        previousBass = 0f
        lastOnsetAt = -10000L
    }

    fun process(bands: FloatArray, bassEnergy: Float, timeMs: Long): Result {
        val bass = if (bassEnergy.isFinite()) bassEnergy.coerceIn(0f, 1f) else 0f
        var low = bass
        var maxBand = 0f
        var rise = 0f
        for (i in previous.indices) {
            val value = (bands.getOrNull(i) ?: 0f).let {
                if (it.isFinite()) it.coerceIn(0f, 1f) else 0f
            }
            val previousValue = previous[i]
            val weighting = if (i < 6) 1.2f else if (i < 13) .5f else .20f
            rise += max(0f, value - previousValue) * weighting
            if (i < 5) low = max(low, value * .8f)
            maxBand = max(maxBand, value)
            previous[i] = value
        }
        rise /= 11f
        val bassRise = max(0f, bass - previousBass)
        previousBass = bass
        val onset = bassRise * .62f + rise * .83f
        // Update the slow baseline *after* comparing the onset with the old value.
        val threshold = max(.008f, averageRise * 1.65f)
        val loudEnough = low >= .034f && maxBand >= .018f
        val beat = loudEnough && onset >= threshold &&
            timeMs - lastOnsetAt >= 165L
        averageRise += (onset - averageRise) * .065f
        if (beat) lastOnsetAt = timeMs
        val strength = if (beat) (onset / max(threshold, .001f)).coerceIn(.65f, 1.0f) else 0f
        return Result(strength, low.coerceIn(0f, 1f), maxBand > .012f || bass > .02f)
    }
}
