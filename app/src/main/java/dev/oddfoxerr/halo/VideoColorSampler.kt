package dev.oddfoxerr.halo

import android.graphics.Color
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Four color regions matching a TV bias-light strip: top, right, bottom, left. */
data class VideoEdgeColors(val top: Int, val right: Int, val bottom: Int, val left: Int) {
    companion object {
        val BLACK = VideoEdgeColors(Color.BLACK, Color.BLACK, Color.BLACK, Color.BLACK)
    }
}

/**
 * Downsampled MediaProjection frame analysis. Operates only in RAM, never stores images.
 * Samples *inside* the video to avoid sampling HALO's own illuminated edge or letterbox borders.
 * Input must be RGBA_8888 with the Android Image.Plane rowStride and pixelStride.
 */
object VideoColorSampler {
    fun sample(
        data: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
        inset: Float = .12f
    ): VideoEdgeColors {
        if (width < 16 || height < 16 || pixelStride < 4 || rowStride < width * pixelStride)
            return VideoEdgeColors.BLACK
        val offset = inset.coerceIn(.06f, .25f)
        fun region(vertical: Boolean, nearStart: Boolean): Int {
            var red = 0L; var green = 0L; var blue = 0L; var count = 0L
            // Small inner strips avoid the overlay itself, UI controls and black letterbox bars.
            for (i in 0 until 13) for (j in 0 until 5) {
                val across = .17f + .66f * (i + .5f) / 13f
                val depth = offset + .085f * (j + .5f) / 5f
                val x = if (vertical) across else if (nearStart) depth else 1f - depth
                val y = if (vertical) if (nearStart) depth else 1f - depth else across
                val px = (x * (width - 1)).toInt().coerceIn(0, width - 1)
                val py = (y * (height - 1)).toInt().coerceIn(0, height - 1)
                val at = py.toLong() * rowStride + px.toLong() * pixelStride
                if (at < 0 || at + 3 >= data.limit()) continue
                val ix = at.toInt()
                val alpha = data.get(ix + 3).toInt() and 255
                if (alpha < 128) continue
                red += (data.get(ix).toInt() and 255)
                green += (data.get(ix + 1).toInt() and 255)
                blue += (data.get(ix + 2).toInt() and 255)
                count++
            }
            if (count == 0L) return Color.BLACK
            return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
        }
        return VideoEdgeColors(
            top = region(vertical = true, nearStart = true),
            right = region(vertical = false, nearStart = false),
            bottom = region(vertical = true, nearStart = false),
            left = region(vertical = false, nearStart = true)
        )
    }

    /** Fast asymmetric smoothing: scene changes arrive immediately, subtle fluctuations remain stable. */
    fun smooth(old: VideoEdgeColors, next: VideoEdgeColors, response: Float): VideoEdgeColors {
        val factor = response.coerceIn(.08f, 1f)
        fun one(a: Int, b: Int): Int {
            fun c(value: Int, nextValue: Int) =
                (value + (nextValue - value) * factor).toInt().coerceIn(0, 255)
            return Color.rgb(c(Color.red(a), Color.red(b)),
                c(Color.green(a), Color.green(b)), c(Color.blue(a), Color.blue(b)))
        }
        return VideoEdgeColors(
            one(old.top, next.top), one(old.right, next.right),
            one(old.bottom, next.bottom), one(old.left, next.left))
    }

    fun isDark(frame: VideoEdgeColors): Boolean {
        val colors = intArrayOf(frame.top, frame.bottom, frame.left, frame.right)
        return colors.all { max(Color.red(it), max(Color.green(it), Color.blue(it))) < 9 }
    }
}
