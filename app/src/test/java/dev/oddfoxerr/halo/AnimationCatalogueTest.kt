package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationCatalogueTest {
    @Test fun onlyFourCuratedMaterialsRemain() {
        assertEquals(listOf(
            EdgeMode.ELECTRIC_ARCS,
            EdgeMode.MIRROR_RAIN,
            EdgeMode.FLAT_WAVES,
            EdgeMode.SIDE_SPECTRUM
        ), EdgeMode.entries)
        assertEquals(4, EdgeMode.entries.map { it.title }.distinct().size)
        for (mode in EdgeMode.entries) assertEquals(mode, EdgeSpectrum.mode(mode.name))
    }

    @Test fun previousSettingsMigrateToARealCurrentShader() {
        val old = listOf(
            "DOTS", "CONSTELLATION", "FIREFLIES", "METEOR", "LIQUID",
            "AURORA", "PRISM", "RIBBON", "BLOOM", "NEON", "PIXEL",
            "HALO", "SHOCKWAVE", "PLASMA", "SPARKLINE", "CRYSTAL",
            "TIDAL", "EMBER", "RADIANCE", "ORBITAL", "SIDE_FLAMES",
            "FLAT_BARS", "STANDING_WAVES", "CORNER_AURA"
        )
        for (name in old) {
            assertTrue("Old mode $name must map to one of four shaders",
                EdgeSpectrum.mode(name) in EdgeMode.entries)
        }
        assertEquals(EdgeMode.ELECTRIC_ARCS, EdgeSpectrum.mode("SPARKLINE"))
        assertEquals(EdgeMode.FLAT_WAVES, EdgeSpectrum.mode("AURORA"))
        assertEquals(EdgeMode.SIDE_SPECTRUM, EdgeSpectrum.mode("BARS"))
        assertEquals(EdgeMode.ELECTRIC_ARCS, EdgeSpectrum.mode(null))
        assertEquals(EdgeMode.ELECTRIC_ARCS, EdgeSpectrum.mode("unknown"))
    }
}
