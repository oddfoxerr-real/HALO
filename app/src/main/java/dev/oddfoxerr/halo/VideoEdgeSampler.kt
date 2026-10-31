package dev.oddfoxerr.halo

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Samples sixteen positions around the actual display perimeter, clockwise from the
 * start of the top edge. Sampling locations correspond to the AGSL shader's arc-length
 * gradient coordinates. Only the calculated colors leave the capture worker; no frames
 * are saved, encoded, uploaded, or sent to another service.
 */
internal object VideoEdgeSampler {
    const val COUNT = 16
    private const val TAU = (PI * 2.0).toFloat()

    data class EdgePoint(val x: Float, val y: Float, val innerX: Float, val innerY: Float)

    fun point(index: Int, width: Int, height: Int, edgeDepth: Float): EdgePoint {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val radius = min(w, h) * .045f
        val horizontal = max(0f, w - 2f * radius)
        val vertical = max(0f, h - 2f * radius)
        val arc = PI.toFloat() * radius / 2f
        val perimeter = 2f * (horizontal + vertical) + 4f * arc
        var s = (index + .5f) * perimeter / COUNT
        val inset = min(w, h) * edgeDepth.coerceIn(.025f, .19f)
        fun edge(x: Float, y: Float, inwardX: Float, inwardY: Float) =
            EdgePoint(x, y, (x + inwardX * inset).coerceIn(0f, w - 1f),
                (y + inwardY * inset).coerceIn(0f, h - 1f))
        if (s < horizontal) return edge(radius + s, 0f, 0f, 1f)
        s -= horizontal
        if (s < arc) {
            val theta = -PI.toFloat() / 2f + s / radius
            return edge(w-radius + cos(theta)*radius, radius + sin(theta)*radius,
                -cos(theta), -sin(theta))
        }
        s -= arc
        if (s < vertical) return edge(w, radius + s, -1f, 0f)
        s -= vertical
        if (s < arc) {
            val theta = s / radius
            return edge(w-radius + cos(theta)*radius, h-radius + sin(theta)*radius,
                -cos(theta), -sin(theta))
        }
        s -= arc
        if (s < horizontal) return edge(w-radius-s, h, 0f, -1f)
        s -= horizontal
        if (s < arc) {
            val theta = PI.toFloat()/2f + s/radius
            return edge(radius + cos(theta)*radius, h-radius + sin(theta)*radius,
                -cos(theta), -sin(theta))
        }
        s -= arc
        if (s < vertical) return edge(0f, h-radius-s, 1f, 0f)
        s -= vertical
        val theta = PI.toFloat() + s.coerceAtMost(arc)/radius
        return edge(radius+cos(theta)*radius, radius+sin(theta)*radius,
            -cos(theta), -sin(theta))
    }

    fun sample(
        buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
        edgeDepth: Float = .075f, saturation: Float = 1.15f
    ): IntArray {
        val result = IntArray(COUNT)
        if (width < 16 || height < 16 || pixelStride < 3 || rowStride < width * pixelStride) return result
        val readLimit = buffer.limit()
        // Nine pixels per position, distributed over a small local neighborhood to avoid
        // reacting to subtitles, individual compression blocks and UI icon pixels.
        val step = max(1, (min(width, height) * .016f).toInt())
        for (zone in 0 until COUNT) {
            val point = point(zone, width, height, edgeDepth)
            var rr = 0f; var gg = 0f; var bb = 0f
            var samples = 0
            for (dy in -1..1) for (dx in -1..1) {
                val x = (point.innerX.toInt() + dx * step).coerceIn(0, width - 1)
                val y = (point.innerY.toInt() + dy * step).coerceIn(0, height - 1)
                val offset = y.toLong() * rowStride + x.toLong() * pixelStride
                if (offset < 0L || offset + 2 >= readLimit) continue
                val i = offset.toInt()
                val red = (buffer.get(i).toInt() and 0xff) / 255f
                val green = (buffer.get(i + 1).toInt() and 0xff) / 255f
                val blue = (buffer.get(i + 2).toInt() and 0xff) / 255f
                // RMS averaging keeps saturated film colors visible despite dark pixels.
                rr += red * red; gg += green * green; bb += blue * blue
                samples++
            }
            if (samples == 0) continue
            val r = sqrt(rr / samples); val g = sqrt(gg / samples); val b = sqrt(bb / samples)
            val luma = .2126f * r + .7152f * g + .0722f * b
            val saturationFactor = saturation.coerceIn(0f, 1.8f)
            fun byte(value: Float) = ((luma + (value-luma)*saturationFactor)
                .coerceIn(0f, 1f) * 255f + .5f).toInt()
            result[zone] = (0xff shl 24) or (byte(r) shl 16) or
                (byte(g) shl 8) or byte(b)
        }
        return result
    }
}

/** Color transitions remain fluid without lagging an entire scene behind the video. */
internal class VideoColorSmoother {
    private val current = FloatArray(VideoEdgeSampler.COUNT * 3)
    private var initialized = false
    fun update(colors: IntArray, smoothing: Float): IntArray {
        val factor = if (!initialized) 1f else (1f - smoothing.coerceIn(0f, .94f))
        initialized = true
        val output = IntArray(VideoEdgeSampler.COUNT)
        for (zone in output.indices) {
            val argb = colors.getOrElse(zone) { 0xff000000.toInt() }
            for (channel in 0..2) {
                val shift = 16 - channel * 8
                val value = ((argb ushr shift) and 0xff).toFloat()
                val i = zone*3 + channel
                current[i] += (value-current[i])*factor
            }
            output[zone] = 0xff000000.toInt() or
                (current[zone*3].toInt().coerceIn(0,255) shl 16) or
                (current[zone*3+1].toInt().coerceIn(0,255) shl 8) or
                current[zone*3+2].toInt().coerceIn(0,255)
        }
        return output
    }
}
