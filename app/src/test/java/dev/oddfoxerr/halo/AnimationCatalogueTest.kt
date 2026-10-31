package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationCatalogueTest {
    @Test fun onlyNewGpuModesAreSelectable() {
        assertEquals(12, EdgeMode.entries.size)
        assertEquals(12, EdgeMode.entries.map { it.title }.distinct().size)
        assertEquals(EdgeMode.DOTS, EdgeSpectrum.mode(null))
        assertEquals(EdgeMode.DOTS, EdgeSpectrum.mode("unknown"))
        for (mode in EdgeMode.entries) assertEquals(mode, EdgeSpectrum.mode(mode.name))
        assertTrue(EdgeMode.entries.none { it.name in
            setOf("WAVE", "BARS", "TRAIL", "PULSE", "CHAIN", "SPARKS", "PIXELS") })
    }

    @Test fun oldSavedModesMigrateWithoutSelectingOldRenderers() {
        val expected = mapOf(
            "WAVE" to EdgeMode.RIBBON,
            "BARS" to EdgeMode.PIXEL,
            "TRAIL" to EdgeMode.METEOR,
            "PULSE" to EdgeMode.BLOOM,
            "CHAIN" to EdgeMode.FIREFLIES,
            "SPARKS" to EdgeMode.FIREFLIES,
            "PIXELS" to EdgeMode.PIXEL,
            "RAIN" to EdgeMode.CONSTELLATION,
            "AURORA" to EdgeMode.AURORA,
            "NEON" to EdgeMode.NEON,
            "PRISM" to EdgeMode.PRISM,
            "COMET" to EdgeMode.METEOR,
            "TWIN" to EdgeMode.METEOR,
            "HELIX" to EdgeMode.RIBBON
        )
        for ((old, replacement) in expected) {
            assertEquals("Migration for $old", replacement, EdgeSpectrum.mode(old))
        }
    }
}
