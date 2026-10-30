package dev.oddfoxerr.halo

import org.junit.Assert.*
import org.junit.Test

class MediaEdgePolicyTest {
    @Test fun lockScreenAndSleepAreAlwaysHidden() {
        assertFalse(MediaEdgePolicy.shouldShow(false, true, "com.spotify.music", false, emptySet()))
        assertFalse(MediaEdgePolicy.shouldShow(true, false, "com.spotify.music", false, emptySet()))
    }

    @Test fun onlyWhenPlayingIsExplicit() {
        assertTrue(MediaEdgePolicy.shouldShow(true, true, null, false, emptySet()))
        assertFalse(MediaEdgePolicy.shouldShow(true, true, null, true, emptySet()))
        assertTrue(MediaEdgePolicy.shouldShow(true, true, "com.spotify.music", true, emptySet()))
    }

    @Test fun sourceAllowlistDoesNotLeakIntoOtherPlayers() {
        val allowed = setOf("com.spotify.music")
        assertFalse(MediaEdgePolicy.shouldShow(true, true, null, false, allowed))
        assertFalse(MediaEdgePolicy.shouldShow(true, true, "com.google.android.apps.youtube.music", false, allowed))
        assertTrue(MediaEdgePolicy.shouldShow(true, true, "com.spotify.music", true, allowed))
    }

    @Test fun unknownColorSourceFallsBackToManual() {
        assertEquals(EdgeColorSource.MANUAL, MediaEdgePolicy.colorSource(null))
        assertEquals(EdgeColorSource.MANUAL, MediaEdgePolicy.colorSource("UNKNOWN"))
        assertEquals(EdgeColorSource.ALBUM_ART, MediaEdgePolicy.colorSource("ALBUM_ART"))
    }
}
