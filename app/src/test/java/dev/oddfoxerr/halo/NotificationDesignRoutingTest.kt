package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDesignRoutingTest {
    @Test fun dynamicGalleryAddsEightModesWithoutChangingPreviousIds() {
        val added = EdgeMode.entries.filter { it.shaderId in 39..46 }
        assertEquals(8, added.size)
        assertEquals((39..46).toList(), added.map { it.shaderId }.sorted())
        assertEquals(47, EdgeMode.entries.size)
        for (mode in added) {
            assertEquals(EdgeGroup.DYNAMIC, mode.group)
            assertEquals(mode, EdgeSpectrum.mode(mode.name))
        }
    }

    @Test fun notificationOverrideCanDifferFromMusicMode() {
        val rule = ApplicationRule(
            packageName = "com.example.app",
            notificationDesignOverride = EdgeMode.KICK_RIPPLE
        )
        assertEquals(EdgeMode.KICK_RIPPLE, rule.notificationDesignOverride)
        assertNull(ApplicationRule(packageName = "com.example.app").notificationDesignOverride)
        assertTrue(EdgeMode.ELECTRIC_ARCS != EdgeMode.KICK_RIPPLE)
    }

    @Test fun existingSavedDesignIdsSurviveTitleChanges() {
        assertEquals(0, EdgeMode.ELECTRIC_ARCS.shaderId)
        assertEquals(4, EdgeMode.BASS_RIBBON.shaderId)
        assertEquals(8, EdgeMode.ACCENT_SWEEP.shaderId)
        assertEquals(9, EdgeMode.HAIRLINE.shaderId)
        assertEquals(38, EdgeMode.PEARL_BEADS.shaderId)
        for (mode in EdgeMode.entries) {
            assertEquals(mode, EdgeSpectrum.mode(mode.name))
        }
    }
}
