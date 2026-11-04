package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicBorderCatalogueTest {
    @Test fun threeClassicBordersArePersistentIndependentShaders() {
        val styles = listOf(
            EdgeMode.CLASSIC_OUTLINE to 6,
            EdgeMode.SOFT_BREATH to 7,
            EdgeMode.ACCENT_SWEEP to 8
        )
        for ((style, id) in styles) {
            assertEquals(id, style.shaderId)
            assertEquals(style, EdgeSpectrum.mode(style.name))
            assertTrue(style.title.isNotBlank())
        }
        assertEquals(EdgeMode.entries.size,
            EdgeMode.entries.map { it.shaderId }.distinct().size)
    }

    @Test fun existingDesignsKeepTheirSavedIds() {
        assertEquals(0, EdgeMode.ELECTRIC_ARCS.shaderId)
        assertEquals(1, EdgeMode.TWIN_PULSE.shaderId)
        assertEquals(2, EdgeMode.LIGHT_BEADS.shaderId)
        assertEquals(3, EdgeMode.CORNER_TRACE.shaderId)
        assertEquals(4, EdgeMode.BASS_RIBBON.shaderId)
        assertEquals(5, EdgeMode.KICK_RAIL.shaderId)
    }
}
