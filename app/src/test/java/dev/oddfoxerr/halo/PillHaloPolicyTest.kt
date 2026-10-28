package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Test

class PillHaloPolicyTest {
    @Test fun onlyOptedInPlayingUnlockedNonQuietDisplay() {
        for (enabled in listOf(false, true))
            for (unlocked in listOf(false, true))
                for (playing in listOf(false, true))
                    for (quiet in listOf(false, true)) {
                        assertEquals(enabled && unlocked && playing && !quiet,
                            PillHaloPolicy.shouldShow(enabled, unlocked, playing, quiet))
                    }
    }

    @Test fun colorsMatchMediaServicesAndFallback() {
        assertEquals(0xFF1ED760L, PillHaloPolicy.mediaColor("com.spotify.music", 0L))
        assertEquals(0xFFFF5264L, PillHaloPolicy.mediaColor("com.google.android.apps.youtube.music", 0L))
        assertEquals(0xFFFF8A36L, PillHaloPolicy.mediaColor("com.soundcloud.android", 0L))
        assertEquals(0xFF123456L, PillHaloPolicy.mediaColor("com.other", 0xFF123456L))
    }
}
