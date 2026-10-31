package dev.oddfoxerr.halo

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Samples only RGB values; never retains, encodes, writes, or transmits screen frames.
 * Zone positions follow a clockwise screen perimeter starting on the upper edge.
 */
internal object VideoColorSampler {
    const val ZONES = 16

    fun sample(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): IntArray {
        if (width < 8 || height < 8 || pixelStride < 3 || rowStride < width * pixelStride) {
            return IntArray(ZONES)
        }
        val output = IntArray(ZONES)
        val inset = (min(width, height) * .085f).coerceAtLeast(2f)
        val left = inset
        val top = inset
        val right = width - 1f - inset
        val bottom = height - 1f - inset
        val r = (min(right - left, bottom - top) * .065f).coerceAtLeast(2f)
        val horizontal = max(0f, right - left - 2f * r)
        val vertical = max(0f, bottom - top - 2f * r)
        val arc = (PI.toFloat() / 2f) * r
        val perimeter = 2f * (horizontal + vertical + 2f * arc)
        val maxByte = buffer.limit()
        for (zone in 0 until ZONES) {
            var distance = zone * perimeter / ZONES
            val x: Float
            val y: Float
            if (distance < horizontal) {
                x = left + r + distance
                y = top
            } else {
                distance -= horizontal
                if (distance < arc) {
                    val angle = -PI.toFloat() / 2f + distance / r
                    x = right - r + cos(angle) * r
                    y = top + r + sin(angle) * r
                } else {
                    distance -= arc
                    if (distance < vertical) {
                        x = right
                        y = top + r + distance
                    } else {
                        distance -= vertical
                        if (distance < arc) {
                            val angle = distance / r
                            x = right - r + cos(angle) * r
                            y = bottom - r + sin(angle) * r
                        } else {
                            distance -= arc
                            if (distance < horizontal) {
                                x = right - r - distance
                                y = bottom
                            } else {
                                distance -= horizontal
                                if (distance < arc) {
                                    val angle = PI.toFloat() / 2f + distance / r
                                    x = left + r + cos(angle) * r
                                    y = bottom - r + sin(angle) * r
                                } else {
                                    distance -= arc
                                    if (distance < vertical) {
                                        x = left
                                        y = bottom - r - distance
                                    } else {
                                        distance -= vertical
                                        val angle = PI.toFloat() + distance / r
                                        x = left + r + cos(angle) * r
                                        y = top + r + sin(angle) * r
                                    }
                                }
                            }
                        }
                    }
                }
            }
            var red = 0
            var green = 0
            var blue = 0
            var count = 0
            // Multi-point averaging suppresses subtitles, compression artifacts and flashes.
            for (dy in -2..2) for (dx in -2..2) {
                val px = (x.toInt() + dx * 2).coerceIn(0, width - 1)
                val py = (y.toInt() + dy * 2).coerceIn(0, height - 1)
                val offset = py.toLong() * rowStride + px.toLong() * pixelStride
                if (offset + 2L >= maxByte || offset < 0) continue
                val index = offset.toInt()
                red += buffer.get(index).toInt() and 0xff
                green += buffer.get(index + 1).toInt() and 0xff
                blue += buffer.get(index + 2).toInt() and 0xff
                count++
            }
            if (count > 0) {
                output[zone] = -0x1000000 or
                    ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
            }
        }
        return output
    }
}
