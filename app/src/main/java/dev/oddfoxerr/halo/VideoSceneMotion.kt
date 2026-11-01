package dev.oddfoxerr.halo

import kotlin.math.abs

/**
 * Converts visible scene changes into a gentle animation envelope.
 *
 * This is NOT a microphone/music beat detector: video capture supplies colors,
 * not audio. Continuous movement comes from the selected GPU animation clock.
 * A scene cut produces a short visual accent, but the selected animation is kept.
 */
internal data class VideoSceneSignal(val bands: FloatArray, val impact: Float)

internal object VideoSceneMotion {
    private const val COUNT = 24

    fun from(current: VideoEdgeColors, previous: VideoEdgeColors?): VideoSceneSignal {
        val samples = current.zones.takeIf { it.size == 16 }
            ?: listOf(current.top, current.right, current.bottom, current.left)
        val earlier = previous?.zones?.takeIf { it.size == 16 }
            ?: previous?.let { listOf(it.top, it.right, it.bottom, it.left) }
        fun luminance(color: Int): Float {
            val red = (color ushr 16) and 255
            val green = (color ushr 8) and 255
            val blue = color and 255
            return (.2126f * red + .7152f * green + .0722f * blue) / 255f
        }
        val bands = FloatArray(COUNT) { index ->
            val color = samples[index * samples.size / COUNT]
            (.25f + luminance(color) * .60f).coerceIn(0f, 1f)
        }
        val difference = if (earlier != null && earlier.size == samples.size) {
            samples.indices.sumOf { index ->
                val a = samples[index]
                val b = earlier[index]
                val r = abs(((a ushr 16) and 255) - ((b ushr 16) and 255))
                val g = abs(((a ushr 8) and 255) - ((b ushr 8) and 255))
                val blue = abs((a and 255) - (b and 255))
                (r + g + blue).toDouble() / (3.0 * 255.0)
            }.toFloat() / samples.size
        } else 0f
        return VideoSceneSignal(bands, (difference * 2.2f).coerceIn(0f, 1f))
    }
}
