package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeGalleryCatalogueTest {
    @Test fun exactlyThirtyNewUniqueGalleryPresets() {
        val added = EdgeMode.entries.filter { it.shaderId in 9..38 }
        assertEquals(30, added.size)
        assertEquals((9..38).toList(), added.map { it.shaderId }.sorted())
        assertEquals(39, EdgeMode.entries.size)
        assertEquals(39, EdgeMode.entries.map { it.shaderId }.distinct().size)
        for (preset in added) {
            assertEquals(preset, EdgeSpectrum.mode(preset.name))
            assertTrue(preset.title.isNotBlank())
            assertTrue(preset.description.isNotBlank())
            assertTrue(preset.group != EdgeGroup.DYNAMIC)
        }
    }

    @Test fun galleryFamilyCountAndPreviousPresetsArePreserved() {
        for (family in EdgeGroup.entries.filter { it != EdgeGroup.DYNAMIC }) {
            assertTrue(family.title.isNotBlank())
            assertTrue(EdgeMode.entries.count { it.group == family } >= 6)
        }
        assertEquals(EdgeMode.ELECTRIC_ARCS, EdgeSpectrum.mode("ELECTRIC_ARCS"))
        assertEquals(EdgeMode.CLASSIC_OUTLINE, EdgeSpectrum.mode("CLASSIC_OUTLINE"))
        assertEquals(EdgeMode.SOFT_BREATH, EdgeSpectrum.mode("SOFT_BREATH"))
        assertEquals(EdgeMode.ACCENT_SWEEP, EdgeSpectrum.mode("ACCENT_SWEEP"))
    }
}
