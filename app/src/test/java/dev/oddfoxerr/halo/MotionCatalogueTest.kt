package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionCatalogueTest {
    @Test fun classicPresetsAndExistingIdsHaveNotChanged() {
        assertEquals(59, EdgeMode.entries.size)
        for (id in 0..58) assertEquals(1, EdgeMode.entries.count { it.shaderId == id })
        assertEquals(0, EdgeMode.ELECTRIC_ARCS.shaderId)
        assertEquals(6, EdgeMode.CLASSIC_OUTLINE.shaderId)
        assertEquals(38, EdgeMode.PEARL_BEADS.shaderId)
        assertEquals(46, EdgeMode.SIDE_ENERGY.shaderId)
    }

    @Test fun videoReferencedMotionClassesHaveActualIndependentIdentifiers() {
        val newModes = EdgeMode.entries.filter { it.shaderId in 47..58 }
        assertEquals(12, newModes.size)
        for (style in newModes) {
            assertEquals(style, EdgeSpectrum.mode(style.name))
            assertTrue(style.title.length > 3)
            assertTrue(style.description.isNotBlank())
        }
        assertEquals(EdgeGroup.FLOW, EdgeMode.SIDE_WAVES.group)
        assertEquals(EdgeGroup.RHYTHM, EdgeMode.EDGE_BARS.group)
        assertEquals(EdgeGroup.TEXTURE, EdgeMode.CORNER_SPARKS.group)
        assertEquals(EdgeGroup.GLOW, EdgeMode.SOFT_BEAMS.group)
    }
}
