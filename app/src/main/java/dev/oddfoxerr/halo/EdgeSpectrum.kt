package dev.oddfoxerr.halo

import kotlin.math.max
import kotlin.math.sqrt

/** Independent of Android; deterministic FFT spectrum extraction for the edge renderers. */
/** GPU-only designs. No legacy line/Canvas animation modes remain selectable. */
enum class EdgeMode(val title: String) {
    DOTS("Luminous Dots"),
    CONSTELLATION("Constellation"),
    FIREFLIES("Fireflies"),
    METEOR("Meteor Shower"),
    LIQUID("Liquid Glass"),
    AURORA("Aurora Flow"),
    PRISM("Chromatic Prism"),
    RIBBON("Silk Ribbons"),
    BLOOM("Pulse Bloom"),
    NEON("Neon Current"),
    PIXEL("Pixel Matrix"),
    HALO("Halo Rings")
}

enum class NotificationEdgeMode(val title: String) {
    CHARACTER("Character"), BORDER("Full border"), BOTH("Both")
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

    /**
     * Existing v0.13 installs keep an appropriate GPU design after the old
     * animation enum disappears. Never route users into removed Canvas code.
     */
    fun mode(value: String?): EdgeMode =
        EdgeMode.entries.firstOrNull { it.name == value } ?: when (value) {
            "WAVE", "HELIX" -> EdgeMode.RIBBON
            "BARS", "PIXELS" -> EdgeMode.PIXEL
            "TRAIL", "COMET", "TWIN" -> EdgeMode.METEOR
            "PULSE" -> EdgeMode.BLOOM
            "CHAIN", "SPARKS" -> EdgeMode.FIREFLIES
            "RAIN" -> EdgeMode.CONSTELLATION
            "AURORA" -> EdgeMode.AURORA
            "NEON" -> EdgeMode.NEON
            "PRISM" -> EdgeMode.PRISM
            else -> EdgeMode.DOTS
        }

    fun notificationMode(value: String?): NotificationEdgeMode =
        NotificationEdgeMode.entries.firstOrNull { it.name == value } ?: NotificationEdgeMode.CHARACTER

}
