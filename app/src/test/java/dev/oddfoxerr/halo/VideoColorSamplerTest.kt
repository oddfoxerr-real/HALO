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
        assertEquals(16, result.zones.size)
        assertTrue(result.zones.distinct().size > 3)
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

    @Test fun narrowSamplingFollowsPhysicalEdgeInsteadOfCenterScene() {
        val width = 128
        val height = 128
        val bytes = ByteBuffer.allocate(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val color = when {
                y < 18 -> intArrayOf(220, 50, 50)
                y >= 110 -> intArrayOf(35, 85, 220)
                x < 18 -> intArrayOf(35, 210, 60)
                x >= 110 -> intArrayOf(235, 210, 30)
                else -> intArrayOf(190, 35, 175)
            }
            bytes.put(color[0].toByte())
            bytes.put(color[1].toByte())
            bytes.put(color[2].toByte())
            bytes.put(255.toByte())
        }
        bytes.flip()
        val result = VideoColorSampler.sample(bytes, width, height, width * 4, 4)
        fun red(value: Int) = value ushr 16 and 255
        fun green(value: Int) = value ushr 8 and 255
        fun blue(value: Int) = value and 255
        assertTrue("Top should match red scene edge", red(result.top) > green(result.top) * 2)
        assertTrue("Bottom should match blue scene edge", blue(result.bottom) > red(result.bottom) * 2)
        assertTrue("Left should match green scene edge", green(result.left) > red(result.left) * 2)
        assertTrue("Right should match yellow scene edge", red(result.right) > blue(result.right) * 2)
        assertEquals(16, result.zones.size)
    }

    @Test fun smoothingReactsToCutsAndKeepsBlackDark() {
        val result = VideoColorSampler.smooth(VideoEdgeColors.BLACK,
            VideoEdgeColors(-0x10000, -0x10000, -0x10000, -0x10000), .5f)
        assertEquals(127.0, (result.top ushr 16 and 255).toDouble(), 1.0)
        assertTrue(VideoColorSampler.isDark(VideoEdgeColors.BLACK))
        assertTrue(!VideoColorSampler.isDark(result))
    }

    @Test fun rejectsInvalidPlaneLayoutWithoutCrashing() {
        assertEquals(VideoEdgeColors.BLACK,
            VideoColorSampler.sample(ByteBuffer.allocate(8), 500, 500, 4, 1))
    }
}
