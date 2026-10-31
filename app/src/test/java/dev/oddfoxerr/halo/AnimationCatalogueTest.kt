package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationCatalogueTest {
    @Test fun musicCatalogueHasDistinctStableModes() {
        assertEquals(15, EdgeMode.entries.size)
        assertEquals(15, EdgeMode.entries.map { it.title }.distinct().size)
        assertEquals(EdgeMode.WAVE, EdgeSpectrum.mode(null))
        assertEquals(EdgeMode.WAVE, EdgeSpectrum.mode("unknown"))
        for (mode in EdgeMode.entries) assertEquals(mode, EdgeSpectrum.mode(mode.name))
    }

    @Test fun notificationCatalogueHasDistinctStableStyles() {
        assertEquals(9, NotificationEdgeStyle.entries.size)
        assertEquals(9, NotificationEdgeStyle.entries.map { it.title }.distinct().size)
        assertEquals(NotificationEdgeStyle.PULSE, EdgeSpectrum.notificationStyle(null))
        for (style in NotificationEdgeStyle.entries)
            assertEquals(style, EdgeSpectrum.notificationStyle(style.name))
    }

    @Test fun oldStyleIdentifiersRemainBackwardCompatible() {
        for (name in listOf("WAVE", "BARS", "TRAIL", "PULSE", "CHAIN"))
            assertTrue(EdgeMode.entries.any { it.name == name })
        for (name in listOf("PULSE", "TRACE", "GLOW"))
            assertTrue(NotificationEdgeStyle.entries.any { it.name == name })
    }
}
