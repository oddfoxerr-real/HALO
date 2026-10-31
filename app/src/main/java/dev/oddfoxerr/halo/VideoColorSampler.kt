package dev.oddfoxerr.halo

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 32 clockwise zones: top L→R, right T→B, bottom R→L, left B→T.
 * Pure JVM code. No frame is saved: only thirty-two average RGB colors escape.
 */
object VideoColorSampler {
    const val ZONES_PER_SIDE = 8
    const val ZONE_COUNT = 32

    fun sample(
        bytes: ByteBuffer, width: Int, height: Int,
        rowStride: Int, pixelStride: Int
    ): IntArray {
        require(width > 0 && height > 0 && rowStride >= width * pixelStride && pixelStride >= 3)
        val result = IntArray(ZONE_COUNT)
        for (side in 0 until 4) {
            for (zone in 0 until ZONES_PER_SIDE) {
                val position = zone / ZONES_PER_SIDE.toFloat()
                val zoneSize = 1f / ZONES_PER_SIDE
                var bestScore = -1f
                var bestColor = 0xFF000000.toInt()
                // Search inward from the closest visible edge to avoid black letterboxing.
                for (depth in 0..4) {
                    val inset = .035f + depth * .050f
                    var red = 0f
                    var green = 0f
                    var blue = 0f
                    var pixels = 0
                    for (sample in 0 until 5) {
                        val u = (position + (sample + .5f) / 5f * zoneSize).coerceIn(0f, 1f)
                        val xNorm: Float
                        val yNorm: Float
                        when (side) {
                            0 -> { xNorm = u; yNorm = inset }
                            1 -> { xNorm = 1f - inset; yNorm = u }
                            2 -> { xNorm = 1f - u; yNorm = 1f - inset }
                            else -> { xNorm = inset; yNorm = 1f - u }
                        }
                        val x = (xNorm * (width - 1)).roundToInt().coerceIn(0, width - 1)
                        val y = (yNorm * (height - 1)).roundToInt().coerceIn(0, height - 1)
                        val offset = y * rowStride + x * pixelStride
                        if (offset + 2 >= bytes.limit()) continue
                        red += (bytes.get(offset).toInt() and 255)
                        green += (bytes.get(offset + 1).toInt() and 255)
                        blue += (bytes.get(offset + 2).toInt() and 255)
                        pixels++
                    }
                    if (pixels == 0) continue
                    red /= pixels
                    green /= pixels
                    blue /= pixels
                    val luminance = (red * .2126f + green * .7152f + blue * .0722f) / 255f
                    // Prefer the nearest non-black edge; stop chasing bright pixels deep
                    // inside the picture once visible video content is found.
                    val score = luminance - depth * .035f
                    if (score > bestScore) {
                        bestScore = score
                        bestColor = (255 shl 24) or (red.roundToInt().coerceIn(0, 255) shl 16) or
                            (green.roundToInt().coerceIn(0, 255) shl 8) or blue.roundToInt().coerceIn(0, 255)
                    }
                    if (luminance > .12f) break
                }
                result[side * ZONES_PER_SIDE + zone] = bestColor
            }
        }
        return result
    }

    fun smooth(previous: IntArray, current: IntArray, blend: Float): IntArray {
        require(previous.size == ZONE_COUNT && current.size == ZONE_COUNT)
        val t = blend.coerceIn(0f, 1f)
        return IntArray(ZONE_COUNT) { i ->
            val a = previous[i]
            val b = current[i]
            fun channel(shift: Int): Int {
                val prior = (a ushr shift) and 255
                val next = (b ushr shift) and 255
                return (prior + (next - prior) * t).roundToInt().coerceIn(0, 255)
            }
            (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }

    fun isBlack(colors: IntArray): Boolean = colors.all {
        val r = (it ushr 16) and 255
        val g = (it ushr 8) and 255
        val b = it and 255
        max(r, max(g, b)) < 8
    }
}
