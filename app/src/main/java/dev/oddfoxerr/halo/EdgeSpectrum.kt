package dev.oddfoxerr.halo

import kotlin.math.max
import kotlin.math.sqrt

/** Each preset belongs to its actual visual family; saved shader IDs never change. */
enum class EdgeGroup(val title: String) {
    LINES("Lines"), PARTICLES("Particles"), LIGHTS("Lights"),
    WAVES("Waves"), RHYTHM("Rhythm"), DYNAMIC("Dynamic")
}

enum class EdgeMode(
    val title: String, val shaderId: Int, val description: String,
    val group: EdgeGroup
) {
    ELECTRIC_ARCS("Electric Arcs", 0, "Broken electric filaments with sharp, energetic movement.", EdgeGroup.DYNAMIC),
    TWIN_PULSE("Side Bass Lines", 1, "Two slim side lights stretch from the centre with the bass.", EdgeGroup.RHYTHM),
    LIGHT_BEADS("Even Dots", 2, "Evenly spaced points of light with a soft, musical shimmer.", EdgeGroup.PARTICLES),
    CORNER_TRACE("Corner Lines", 3, "Four rounded corner accents extend and settle with each beat.", EdgeGroup.LINES),
    BASS_RIBBON("Bass Side Lights", 4, "Two clean light blades surge on bass then snap back.", EdgeGroup.RHYTHM),
    KICK_RAIL("Bottom Kick Line", 5, "A minimal lower rail that punches outward on kick drums.", EdgeGroup.RHYTHM),
    CLASSIC_OUTLINE("Classic Outline", 6, "A continuous thin rounded border with a tiny bass response.", EdgeGroup.LINES),
    SOFT_BREATH("Soft Glow", 7, "The same clean border with a restrained, slowly breathing halo.", EdgeGroup.LIGHTS),
    ACCENT_SWEEP("Moving Highlight", 8, "A fine border with one subtle highlight travelling around it.", EdgeGroup.LIGHTS),
    HAIRLINE("Hairline", 9, "Barely-there continuous edge line.", EdgeGroup.LINES),
    DOUBLE_EDGE("Double Edge", 10, "Two delicate parallel outlines.", EdgeGroup.LINES),
    INNER_PINSTRIPE("Inner Pinstripe", 11, "Inset crisp line, softly responding to bass.", EdgeGroup.LINES),
    OUTER_PINSTRIPE("Outer Pinstripe", 12, "An outer fine line that leaves content untouched.", EdgeGroup.LINES),
    CORNER_GLEAM("Corner Gleam", 13, "Four rounded corners glow slightly brighter.", EdgeGroup.LIGHTS),
    FROST_LINE("Thin Shimmer", 14, "Cool satin outline with a restrained icy glint.", EdgeGroup.LIGHTS),
    VELVET("Soft Border", 15, "Smooth continuous border with a low soft halo.", EdgeGroup.LIGHTS),
    DIFFUSED("Wide Soft Glow", 16, "Fine rim with a slightly broader haze.", EdgeGroup.LIGHTS),
    SLOW_AURA("Slow Breathing Glow", 17, "A gentle, barely moving luminous border.", EdgeGroup.LIGHTS),
    TWIN_HALO("Two Glow Lines", 18, "Narrow two-layer light around the perimeter.", EdgeGroup.LIGHTS),
    AMBER_AURA("Moving Tint", 19, "Soft warm-looking tint moving through the edge.", EdgeGroup.LIGHTS),
    MOON_GLOW("Light Shimmer", 20, "Pale illuminated border with a quiet shimmer.", EdgeGroup.LIGHTS),
    BASS_LIFT("Bass Lift", 21, "Hairline rises slightly with the bass.", EdgeGroup.RHYTHM),
    BEAT_PINCH("Beat Pinch", 22, "Border tightens into a sharp beat response.", EdgeGroup.RHYTHM),
    KICK_FLASH("Kick Flash", 23, "Short brightness accents on real kick hits.", EdgeGroup.RHYTHM),
    SUSTAIN("Held-Note Glow", 24, "Long, calm glow for held musical notes.", EdgeGroup.RHYTHM),
    TREBLE_GLINT("High-Note Glint", 25, "Tiny top-end accents, never particle bursts.", EdgeGroup.RHYTHM),
    SPECTRUM_TRIM("Audio Gradient", 26, "Full outline with a subtle spectrum-dependent gradient.", EdgeGroup.RHYTHM),
    SLOW_ORBIT("Slow Moving Highlight", 27, "Single delicate shine slowly circling the border.", EdgeGroup.LIGHTS),
    COUNTERFLOW("Opposite Highlights", 28, "Two discreet highlights moving in opposite directions.", EdgeGroup.LIGHTS),
    CORNER_DRIFT("Corner Highlight", 29, "A subtle wandering shine around the corners.", EdgeGroup.LIGHTS),
    DAWN_PASS("Vertical Sweep", 30, "A soft highlight moves from top to bottom.", EdgeGroup.LIGHTS),
    SILK_WAVE("Fine Moving Ripple", 31, "Fine moving ripple within the border itself.", EdgeGroup.WAVES),
    PASSING_LIGHT("Passing Light", 32, "A slightly longer halo travels along the line.", EdgeGroup.LIGHTS),
    TINY_DOTS("Tiny Dots", 33, "Small uniform points lining the perimeter.", EdgeGroup.PARTICLES),
    MICRO_DASH("Micro Dash", 34, "Evenly spaced short illuminated strokes.", EdgeGroup.LINES),
    SATIN_STITCH("Fine Stitches", 35, "Ultra-fine stitched-light border.", EdgeGroup.LINES),
    MICRO_GRAIN("Fine Texture", 36, "Lightly textured edge with subtle movement.", EdgeGroup.PARTICLES),
    GLASS_ETCH("Double Fine Line", 37, "A delicate engraved-style outline.", EdgeGroup.LINES),
    PEARL_BEADS("Small Round Dots", 38, "A ring of neat rounded miniature lights.", EdgeGroup.PARTICLES),
    BASS_PRESSURE("Bass Pressure", 39, "The complete edge line expands and recoils on deep bass.", EdgeGroup.RHYTHM),
    IMPACT_WAVES("Impact Waves", 40, "Two side lights ripple on kicks and quickly recover.", EdgeGroup.WAVES),
    RUNNING_PULSES("Running Pulses", 41, "Three moving light accents race along the edge.", EdgeGroup.DYNAMIC),
    KICK_RIPPLE("Kick Ripple", 42, "Each kick sends a short flash along the lower corners.", EdgeGroup.RHYTHM),
    PULSE_CORNERS("Pulse Corners", 43, "Four corner brackets grow outward on the beat.", EdgeGroup.RHYTHM),
    AUDIO_WAVE("Audio Wave", 44, "The whole line bends with the live audio spectrum.", EdgeGroup.WAVES),
    LIQUID_EDGE("Liquid Edge", 45, "A thin moving liquid-like rim shaped by bass and mids.", EdgeGroup.WAVES),
    SIDE_ENERGY("Side Energy", 46, "Left and right edge strokes stretch on the bass.", EdgeGroup.DYNAMIC),
    SERIAL_LIGHTS("Serial Lights", 47, "Individual round lights chase around the border.", EdgeGroup.PARTICLES),
    EDGE_BARS("Edge Bars", 48, "A ring of small audio-reactive bars.", EdgeGroup.RHYTHM),
    SIDE_BARS("Side Bars", 49, "Vertical equalizer bars on the left and right sides.", EdgeGroup.RHYTHM),
    SIDE_WAVES("Side Waves", 50, "Smooth wave ribbons flow along both side edges.", EdgeGroup.WAVES),
    FULL_WAVES("Full Waves", 51, "A rounded border bends with the live spectrum.", EdgeGroup.WAVES),
    TOP_BOTTOM_WAVES("Top and Bottom Waves", 52, "Soft layered waves run along the top and bottom.", EdgeGroup.WAVES),
    CHASE_LIGHT("Chase Light", 53, "One smooth luminous trail travels the perimeter.", EdgeGroup.LIGHTS),
    CORNER_SPARKS("Corner Sparks", 54, "Small light particles drift through opposite corners.", EdgeGroup.PARTICLES),
    EDGE_DRIFT("Edge Drift", 55, "Sparse floating light specks move along the edge.", EdgeGroup.PARTICLES),
    SOFT_BEAMS("Soft Beams", 56, "Soft vertical light beams breathe at each side.", EdgeGroup.LIGHTS),
    FLUTTER("Flutter", 57, "Short gentle edge pulses move in rhythm.", EdgeGroup.RHYTHM),
    FIRE_LINES("Fire Lines", 58, "Narrow flames flicker along both screen sides.", EdgeGroup.DYNAMIC)
}

/** Shared between the music and notification selectors: exactly 59 choices. */
object EdgeCatalogue {
    private val sorted = EdgeMode.entries.sortedWith(
        compareBy<EdgeMode> { it.group.ordinal }.thenBy { it.title }
    )
    fun visible(group: EdgeGroup?): List<EdgeMode> =
        if (group == null) sorted else sorted.filter { it.group == group }
    fun count(group: EdgeGroup): Int = EdgeMode.entries.count { it.group == group }
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
