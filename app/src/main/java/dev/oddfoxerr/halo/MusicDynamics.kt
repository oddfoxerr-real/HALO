package dev.oddfoxerr.halo

import kotlin.math.exp
import kotlin.math.max

/**
 * A music-specific motion engine. The three spectral regions respond independently
 * and positive spectral flux detects actual changes, not arbitrary animation beats.
 *
 * All inputs come from Android Visualizer FFT when that API permits capture.
 * A missing/blocked signal decays to zero. No simulated music energy is generated.
 */
class MusicDynamics {
    var low = 0f; private set
    var mid = 0f; private set
    var high = 0f; private set
    var hit = 0f; private set

    private var targetLow = 0f
    private var targetMid = 0f
    private var targetHigh = 0f
    private val previous = FloatArray(EdgeSpectrum.BAND_COUNT)
    private var fluxFloor = .012f
    private var lastObserved = Long.MIN_VALUE
    private var lastOnset = Long.MIN_VALUE

    fun observe(bands: FloatArray, bass: Float, timeMs: Long) {
        if (timeMs < lastObserved) return
        var bassSum = 0f
        var midSum = 0f
        var highSum = 0f
        var positive = 0f
        for (i in previous.indices) {
            val sample = bands.getOrElse(i) { 0f }.let {
                if (it.isFinite()) it.coerceIn(0f, 1f) else 0f
            }
            when (i) {
                in 0..5 -> bassSum += sample
                in 6..15 -> midSum += sample
                else -> highSum += sample
            }
            val delta = max(0f, sample - previous[i])
            positive += delta * when (i) {
                in 0..5 -> 1.55f
                in 6..15 -> 1.0f
                else -> .85f
            }
            previous[i] = sample
        }
        val finiteBass = if (bass.isFinite()) bass.coerceIn(0f, 1f) else 0f
        targetLow = max((bassSum / 6f), finiteBass * .85f).coerceIn(0f, 1f)
        targetMid = (midSum / 10f).coerceIn(0f, 1f)
        targetHigh = (highSum / 8f).coerceIn(0f, 1f)
        val flux = positive / previous.size
        val hasEnergy = targetLow > .025f || targetMid > .035f || targetHigh > .045f
        val refractoryDone = lastOnset == Long.MIN_VALUE || timeMs - lastOnset >= 145L
        val onset = hasEnergy && refractoryDone &&
            flux > max(.028f, fluxFloor * 1.6f)
        if (onset) {
            hit = (hit + .55f + (flux * 4.0f).coerceAtMost(.5f)).coerceAtMost(1.5f)
            lastOnset = timeMs
        }
        // Slowly following the actual signal prevents repeated false beats
        // from constant loudness or a single isolated high-frequency note.
        fluxFloor += (flux - fluxFloor) * .045f
        lastObserved = timeMs
    }

    fun advance(dt: Float, fresh: Boolean) {
        val time = if (dt.isFinite()) dt.coerceIn(0f, .07f) else 0f
        fun glide(current: Float, target: Float): Float {
            val coefficient = if (target > current) 38f else 9f
            return current + (target - current) * (1f - exp(-time * coefficient))
        }
        low = glide(low, if (fresh) targetLow else 0f)
        mid = glide(mid, if (fresh) targetMid else 0f)
        high = glide(high, if (fresh) targetHigh else 0f)
        hit *= exp(-time * 10.5f)
        if (hit < .0025f) hit = 0f
    }

    fun reset() {
        low = 0f; mid = 0f; high = 0f; hit = 0f
        targetLow = 0f; targetMid = 0f; targetHigh = 0f
        previous.fill(0f)
        fluxFloor = .012f
        lastObserved = Long.MIN_VALUE
        lastOnset = Long.MIN_VALUE
    }
}
