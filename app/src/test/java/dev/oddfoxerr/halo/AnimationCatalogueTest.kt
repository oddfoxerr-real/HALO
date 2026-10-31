package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationCatalogueTest {
    @Test fun gpuCatalogueHasDistinctEffects() {
        assertEquals(12, EdgeMode.entries.size)
        assertEquals(12, EdgeMode.entries.map { it.title }.distinct().size)
        assertTrue(EdgeMode.entries.any { it.name == "DOTS" })
        assertTrue(EdgeMode.entries.any { it.name == "FIREFLIES" })
        assertEquals(EdgeMode.LIQUID, EdgeSpectrum.mode(null))
        assertEquals(EdgeMode.LIQUID, EdgeSpectrum.mode("unknown"))
        for (mode in EdgeMode.entries) assertEquals(mode, EdgeSpectrum.mode(mode.name))
    }

    @Test fun legacySelectionsMigrateWithoutLegacyRenderers() {
        val legacy = mapOf(
            "WAVE" to EdgeMode.LIQUID,
            "BARS" to EdgeMode.SPECTRUM,
            "TRAIL" to EdgeMode.ORBIT,
            "PULSE" to EdgeMode.RIPPLES,
            "CHAIN" to EdgeMode.SHARDS,
            "SPARKS" to EdgeMode.FIREFLIES,
            "RAIN" to EdgeMode.FIREFLIES,
            "PIXELS" to EdgeMode.SPECTRUM,
            "TWIN" to EdgeMode.ORBIT,
            "COMET" to EdgeMode.COMETS
        )
        for ((previous, expected) in legacy) assertEquals(expected, EdgeSpectrum.mode(previous))
    }

    @Test fun gradientCatalogueUsesFourStableChoices() {
        assertEquals(4, EdgeGradient.entries.size)
        assertEquals(4, EdgeGradient.entries.map { it.title }.distinct().size)
        assertEquals(EdgeGradient.SMOOTH, EdgeGradient.entries.first())
    }
}
