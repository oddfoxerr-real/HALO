package dev.oddfoxerr.halo

import kotlin.math.max
import kotlin.math.sqrt

/** Independent of Android; deterministic FFT spectrum extraction for the edge renderers. */
enum class EdgeMode(val title: String) {
    WAVE("Silk"), BARS("Spectrum"), TRAIL("Orbit"), PULSE("Pulse"), CHAIN("Chainsaw"),
    AURORA("Aurora"), COMET("Comet"), TWIN("Twin streams"), SPARKS("Sparks"),
    PIXELS("Pixel rush"), NEON("Neon circuit"), RAIN("Rainfall"), HELIX("Helix"),
    BLOOM("Bloom"), PRISM("Prism")
}

enum class NotificationEdgeMode(val title: String) {
    CHARACTER("Character"), BORDER("Full border"), BOTH("Both")
}

enum class NotificationEdgeStyle(val title: String) {
    PULSE("Pulse"), TRACE("Outline sweep"), GLOW("Soft glow"),
    COMET("Comet trail"), DASH("Chasing dashes"), SPARK("Electric sparks"),
    RIPPLE("Ripple"), AURORA("Aurora"), BEACON("Beacon")
}

object EdgeSpectrum {
    const val BAND_COUNT = 24

    /** Visualizer FFT: interleaved signed real/imaginary bytes, sampling rate in millihertz. */
    fun fromFft(fft: ByteArray, samplingRateMilliHz: Int): FloatArray {
        val result = FloatArray(BAND_COUNT)
        if (fft.size < 64 || samplingRateMilliHz < 1000) return result
        val hzPerBin = samplingRateMilliHz / 1000f / fft.size
        if (hzPerBin <= 0f) return result
        val limit = fft.size / 2 - 1
        for (band in 0 until BAND_COUNT) {
            val lowHz = 38f * Math.pow(160.0, band.toDouble() / BAND_COUNT).toFloat()
            val highHz = 38f * Math.pow(160.0, (band + 1).toDouble() / BAND_COUNT).toFloat()
            val start = max(1, (lowHz / hzPerBin).toInt()).coerceAtMost(limit)
            val end = max(start, (highHz / hzPerBin).toInt()).coerceAtMost(limit)
            var sum = 0f
            for (bin in start..end) {
                val re = fft[bin * 2].toInt() / 128f
                val im = fft[bin * 2 + 1].toInt() / 128f
                sum += re * re + im * im
            }
            result[band] = (sqrt(sum / (end - start + 1)) * 2.6f).coerceIn(0f, 1f)
        }
        return result
    }

    fun bass(bands: FloatArray): Float =
        if (bands.size < 4) 0f else (bands[0] + bands[1] + bands[2] + bands[3]) / 4f

    fun mode(value: String?): EdgeMode =
        EdgeMode.entries.firstOrNull { it.name == value } ?: EdgeMode.WAVE

    fun notificationMode(value: String?): NotificationEdgeMode =
        NotificationEdgeMode.entries.firstOrNull { it.name == value } ?: NotificationEdgeMode.CHARACTER

    fun notificationStyle(value: String?): NotificationEdgeStyle =
        NotificationEdgeStyle.entries.firstOrNull { it.name == value } ?: NotificationEdgeStyle.PULSE
}
