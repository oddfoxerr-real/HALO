package dev.oddfoxerr.halo

import kotlin.math.max
import kotlin.math.sqrt

/** Independent of Android; deterministic FFT spectrum extraction for the edge renderers. */
/** GPU-only designs. Display groups limit active preview tiles for mobile GPUs. */
enum class EdgeGroup(val title: String) {
    BORDERS("Borders"), GLOW("Glow"), RHYTHM("Rhythm"),
    FLOW("Flow"), TEXTURE("Texture"), DYNAMIC("Dynamic")
}

enum class EdgeMode(
    val title: String, val shaderId: Int, val description: String,
    val group: EdgeGroup = EdgeGroup.DYNAMIC
) {
    ELECTRIC_ARCS("Electric Arcs", 0, "Broken electric filaments with sharp, energetic movement."),
    TWIN_PULSE("Twin Pulse", 1, "Two slim side lights stretch from the centre with the bass."),
    LIGHT_BEADS("Light Beads", 2, "Evenly spaced points of light with a soft, musical shimmer."),
    CORNER_TRACE("Corner Trace", 3, "Four rounded corner accents extend and settle with each beat."),
    BASS_RIBBON("Bass Ribbon", 4, "Two clean light blades surge on bass then snap back."),
    KICK_RAIL("Kick Rail", 5, "A minimal lower rail that punches outward on kick drums."),
    CLASSIC_OUTLINE("Classic Outline", 6, "A continuous thin rounded border with a tiny bass response.", EdgeGroup.BORDERS),
    SOFT_BREATH("Soft Glow", 7, "The same clean border with a restrained, slowly breathing halo.", EdgeGroup.GLOW),
    ACCENT_SWEEP("Accent Sweep", 8, "A fine border with one subtle highlight travelling around it.", EdgeGroup.FLOW),    HAIRLINE("Hairline", 9, "Barely-there continuous edge line.", EdgeGroup.BORDERS),
    DOUBLE_EDGE("Double Edge", 10, "Two delicate parallel outlines.", EdgeGroup.BORDERS),
    INNER_PINSTRIPE("Inner Pinstripe", 11, "Inset crisp line, softly responding to bass.", EdgeGroup.BORDERS),
    OUTER_PINSTRIPE("Outer Pinstripe", 12, "An outer fine line that leaves content untouched.", EdgeGroup.BORDERS),
    CORNER_GLEAM("Corner Gleam", 13, "Four rounded corners glow slightly brighter.", EdgeGroup.BORDERS),
    FROST_LINE("Frost Line", 14, "Cool satin outline with a restrained icy glint.", EdgeGroup.BORDERS),
    VELVET("Velvet", 15, "Smooth continuous border with a low soft halo.", EdgeGroup.GLOW),
    DIFFUSED("Diffused", 16, "Fine rim with a slightly broader haze.", EdgeGroup.GLOW),
    SLOW_AURA("Slow Aura", 17, "A gentle, barely moving luminous border.", EdgeGroup.GLOW),
    TWIN_HALO("Twin Halo", 18, "Narrow two-layer light around the perimeter.", EdgeGroup.GLOW),
    AMBER_AURA("Amber Aura", 19, "Soft warm-looking tint moving through the edge.", EdgeGroup.GLOW),
    MOON_GLOW("Moon Glow", 20, "Pale illuminated border with a quiet shimmer.", EdgeGroup.GLOW),
    BASS_LIFT("Bass Lift", 21, "Hairline rises slightly with the bass.", EdgeGroup.RHYTHM),
    BEAT_PINCH("Beat Pinch", 22, "Border tightens into a sharp beat response.", EdgeGroup.RHYTHM),
    KICK_FLASH("Kick Flash", 23, "Short brightness accents on real kick hits.", EdgeGroup.RHYTHM),
    SUSTAIN("Sustain", 24, "Long, calm glow for held musical notes.", EdgeGroup.RHYTHM),
    TREBLE_GLINT("Treble Glint", 25, "Tiny top-end accents, never particle bursts.", EdgeGroup.RHYTHM),
    SPECTRUM_TRIM("Spectrum Trim", 26, "Full outline with a subtle spectrum-dependent gradient.", EdgeGroup.RHYTHM),
    SLOW_ORBIT("Slow Orbit", 27, "Single delicate shine slowly circling the border.", EdgeGroup.FLOW),
    COUNTERFLOW("Counterflow", 28, "Two discreet highlights moving in opposite directions.", EdgeGroup.FLOW),
    CORNER_DRIFT("Corner Drift", 29, "A subtle wandering shine around the corners.", EdgeGroup.FLOW),
    DAWN_PASS("Dawn Pass", 30, "A soft highlight moves from top to bottom.", EdgeGroup.FLOW),
    SILK_WAVE("Silk Wave", 31, "Fine moving ripple within the border itself.", EdgeGroup.FLOW),
    PASSING_LIGHT("Passing Light", 32, "A slightly longer halo travels along the line.", EdgeGroup.FLOW),
    TINY_DOTS("Tiny Dots", 33, "Small uniform points lining the perimeter.", EdgeGroup.TEXTURE),
    MICRO_DASH("Micro Dash", 34, "Evenly spaced short illuminated strokes.", EdgeGroup.TEXTURE),
    SATIN_STITCH("Satin Stitch", 35, "Ultra-fine stitched-light border.", EdgeGroup.TEXTURE),
    MICRO_GRAIN("Micro Grain", 36, "Lightly textured edge with subtle movement.", EdgeGroup.TEXTURE),
    GLASS_ETCH("Glass Etch", 37, "A delicate engraved-style outline.", EdgeGroup.TEXTURE),
    PEARL_BEADS("Pearl Beads", 38, "A ring of neat rounded miniature lights.", EdgeGroup.TEXTURE)
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

    /** New choices round-trip by name; retired presets retain the v0.18.1 migration. */
    fun mode(value: String?): EdgeMode =
        EdgeMode.entries.firstOrNull { it.name == value } ?: EdgeMode.ELECTRIC_ARCS

    fun notificationMode(value: String?): NotificationEdgeMode =
        NotificationEdgeMode.entries.firstOrNull { it.name == value } ?: NotificationEdgeMode.CHARACTER

}
