package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSceneMotionTest {
    private fun solid(color: Int) = VideoEdgeColors(color, color, color, color, List(16) { color })

    @Test fun sceneCutsProduceTransientsWithoutChangingAnimationMode() {
        val red = solid(0xFFFF0000.toInt())
        val blue = solid(0xFF0000FF.toInt())
        val first = VideoSceneMotion.from(red, null)
        val still = VideoSceneMotion.from(red, red)
        val changed = VideoSceneMotion.from(blue, red)
        assertEquals(24, first.bands.size)
        assertEquals(0f, first.impact, .001f)
        assertEquals(0f, still.impact, .001f)
        assertTrue(changed.impact > .3f)
        assertTrue(changed.bands.all { it in 0f..1f })
    }

    @Test fun eachVideoRegionContributesEnergyToItsCorrespondingBands() {
        val zones = List(16) { if (it < 8) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val colors = VideoEdgeColors(zones[0], zones[4], zones[8], zones[12], zones)
        val result = VideoSceneMotion.from(colors, null)
        assertTrue(result.bands.first() > result.bands.last())
        assertTrue(result.bands.all { it.isFinite() })
    }

    @Test fun missingZonesUseFourSideFallback() {
        val fallback = VideoEdgeColors(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
            0xFFFFFFFF.toInt(), 0xFF000000.toInt()
        )
        val result = VideoSceneMotion.from(fallback, null)
        assertEquals(24, result.bands.size)
        assertEquals(0f, result.impact, .001f)
        assertTrue(result.bands.first() > result.bands[7])
    }
}
