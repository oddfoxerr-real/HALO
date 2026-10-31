package dev.oddfoxerr.halo

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoColorSamplerTest {
    @Test fun samplesFourIndependentSidesOfRgbaFrame() {
        val width = 96
        val height = 160
        val bytes = ByteBuffer.allocate(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val channels = when {
                y < height * .25f -> intArrayOf(255, 0, 0)
                y > height * .75f -> intArrayOf(0, 0, 255)
                x < width * .25f -> intArrayOf(0, 255, 0)
                x > width * .75f -> intArrayOf(255, 255, 0)
                else -> intArrayOf(0, 0, 0)
            }
            bytes.put(channels[0].toByte())
            bytes.put(channels[1].toByte())
            bytes.put(channels[2].toByte())
            bytes.put(255.toByte())
        }
        bytes.flip()
        val result = VideoColorSampler.sample(bytes, width, height, width * 4, 4)
        fun red(c: Int) = c ushr 16 and 255
        fun green(c: Int) = c ushr 8 and 255
        fun blue(c: Int) = c and 255
        assertTrue(red(result.top) > green(result.top))
        assertTrue(red(result.top) > blue(result.top))
        assertTrue(blue(result.bottom) > red(result.bottom))
        assertTrue(green(result.left) > red(result.left))
        assertTrue(red(result.right) > blue(result.right))
        assertTrue(green(result.right) > blue(result.right))
    }

    @Test fun smoothingReactsToCutsAndKeepsBlackDark() {
        val result = VideoColorSampler.smooth(VideoEdgeColors.BLACK,
            VideoEdgeColors(-0x10000, -0x10000, -0x10000, -0x10000), .5f)
        assertEquals(127, result.top ushr 16 and 255, 1)
        assertTrue(VideoColorSampler.isDark(VideoEdgeColors.BLACK))
        assertTrue(!VideoColorSampler.isDark(result))
    }

    @Test fun rejectsInvalidPlaneLayoutWithoutCrashing() {
        assertEquals(VideoEdgeColors.BLACK,
            VideoColorSampler.sample(ByteBuffer.allocate(8), 500, 500, 4, 1))
    }
}
