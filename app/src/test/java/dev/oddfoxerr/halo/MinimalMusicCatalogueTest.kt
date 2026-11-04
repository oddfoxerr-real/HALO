package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalMusicCatalogueTest {
    @Test fun newDesignsRemainSavedAndHaveUniqueGpuIds() {
        assertEquals(4, EdgeMode.BASS_RIBBON.shaderId)
        assertEquals(5, EdgeMode.KICK_RAIL.shaderId)
        assertEquals(EdgeMode.BASS_RIBBON, EdgeSpectrum.mode("BASS_RIBBON"))
        assertEquals(EdgeMode.KICK_RAIL, EdgeSpectrum.mode("KICK_RAIL"))
        assertEquals(EdgeMode.entries.size, EdgeMode.entries.map { it.shaderId }.distinct().size)
        assertTrue(EdgeMode.entries.contains(EdgeMode.ELECTRIC_ARCS))
    }
}
