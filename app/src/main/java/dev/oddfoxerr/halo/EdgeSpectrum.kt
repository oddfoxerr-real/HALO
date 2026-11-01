package dev.oddfoxerr.halo

import kotlin.math.max
import kotlin.math.sqrt

/** Independent of Android; deterministic FFT spectrum extraction for the edge renderers. */
/** GPU-only designs. No legacy line/Canvas animation modes remain selectable. */
enum class EdgeMode(val title: String) {
    ELECTRIC_ARCS("Electric Arcs"),
    MIRROR_RAIN("Mirror Rain"),
    FLAT_WAVES("Flat Waves"),
    SIDE_SPECTRUM("Side Spectrum")
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
    /**
     * Saved v0.17 presets migrate to the closest supported visual family.
     * Unsupported animations are removed from the catalogue and shader itself.
     */
    fun mode(value: String?): EdgeMode = when (value) {
        "ELECTRIC_ARCS", "SPARKLINE", "SHOCKWAVE", "NEON", "PRISM",
        "FIRELIES", "FIREFLIES", "SPARKS", "CHAIN" -> EdgeMode.ELECTRIC_ARCS
        "MIRROR_RAIN", "CONSTELLATION", "METEOR", "PIXEL",
        "DOTS", "RAIN", "COMET", "TRAIL", "TWIN", "ORBITAL",
        "EMBER" -> EdgeMode.MIRROR_RAIN
        "FLAT_WAVES", "LIQUID", "AURORA", "RIBBON", "TIDAL",
        "PLASMA", "STANDING_WAVES", "WAVE", "HELIX" -> EdgeMode.FLAT_WAVES
        "SIDE_SPECTRUM", "FLAT_BARS", "BARS", "PIXELS",
        "BLOOM", "HALO", "SIDE_FLAMES", "RADIANCE",
        "CRYSTAL", "CORNER_AURA", "PULSE" -> EdgeMode.SIDE_SPECTRUM
        else -> EdgeMode.ELECTRIC_ARCS
    }

    fun notificationMode(value: String?): NotificationEdgeMode =
        NotificationEdgeMode.entries.firstOrNull { it.name == value } ?: NotificationEdgeMode.CHARACTER

}
