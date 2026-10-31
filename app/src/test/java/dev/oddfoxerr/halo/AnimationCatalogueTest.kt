package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationCatalogueTest {
    @Test fun gpuEffectsAreUniqueAndIncludeParticles() {
        assertEquals(12, EdgeMode.entries.size)
        assertEquals(12, EdgeMode.entries.map { it.title }.distinct().size)
        assertEquals(12, EdgeMode.entries.map { it.description }.distinct().size)
        assertTrue(EdgeMode.entries.contains(EdgeMode.DOTS))
        assertTrue(EdgeMode.entries.contains(EdgeMode.FIREFLIES))
        assertEquals(EdgeMode.LIQUID, EdgeSpectrum.mode(null))
        for (mode in EdgeMode.entries) assertEquals(mode, EdgeSpectrum.mode(mode.name))
    }

    @Test fun removedLegacyAnimationsAreNotSelectable() {
        for (legacy in listOf("WAVE", "BARS", "TRAIL", "PULSE", "CHAIN",
            "TWIN", "SPARKS", "PIXELS", "NEON", "RAIN", "HELIX", "BLOOM")) {
            assertFalse(EdgeMode.entries.any { it.name == legacy })
        }
    }

    @Test fun savedLegacySelectionsMigrateToNewGpuModes() {
        assertEquals(EdgeMode.LIQUID, EdgeSpectrum.mode("WAVE"))
        assertEquals(EdgeMode.SPECTRUM, EdgeSpectrum.mode("BARS"))
        assertEquals(EdgeMode.ORBITALS, EdgeSpectrum.mode("TRAIL"))
        assertEquals(EdgeMode.DOTS, EdgeSpectrum.mode("CHAIN"))
        assertEquals(EdgeMode.METEORS, EdgeSpectrum.mode("RAIN"))
        assertEquals(EdgeMode.COMETS, EdgeSpectrum.mode("COMET"))
    }

    @Test fun legacyNotificationPreferenceParsingDoesNotControlNewEffects() {
        assertEquals(NotificationEdgeStyle.PULSE, EdgeSpectrum.notificationStyle(null))
    }
}
