package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationCatalogueTest {
    @Test fun savedDesignsRoundTripWithoutFallingBackToElectric() {
        for (mode in EdgeMode.entries) assertEquals(mode, EdgeSpectrum.mode(mode.name))
        assertEquals(EdgeMode.entries.size, EdgeMode.entries.map { it.shaderId }.toSet().size)
        assertEquals(0, EdgeMode.ELECTRIC_ARCS.shaderId)
    }

    @Test fun previousMusicPresetNamesAllMigrateToApprovedShader() {
        val previousNames = listOf(
            "DOTS", "CONSTELLATION", "FIREFLIES", "METEOR", "LIQUID",
            "AURORA", "PRISM", "RIBBON", "BLOOM", "NEON", "PIXEL",
            "HALO", "SHOCKWAVE", "PLASMA", "SPARKLINE", "CRYSTAL",
            "TIDAL", "EMBER", "RADIANCE", "ORBITAL", "SIDE_FLAMES",
            "FLAT_BARS", "STANDING_WAVES", "CORNER_AURA",
            "MIRROR_RAIN", "FLAT_WAVES", "SIDE_SPECTRUM",
            "ELECTRIC_ARCS", "WAVE", "BARS", "RAIN"
        )
        for (name in previousNames) {
            assertEquals("Migration for $name", EdgeMode.ELECTRIC_ARCS, EdgeSpectrum.mode(name))
        }
        assertEquals(EdgeMode.ELECTRIC_ARCS, EdgeSpectrum.mode(null))
        assertEquals(EdgeMode.ELECTRIC_ARCS, EdgeSpectrum.mode("unknown"))
    }
}
