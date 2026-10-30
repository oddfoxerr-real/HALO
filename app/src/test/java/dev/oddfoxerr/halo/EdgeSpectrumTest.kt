package dev.oddfoxerr.halo

import org.junit.Assert.*
import org.junit.Test

class EdgeSpectrumTest {
    @Test fun silenceDoesNotInventBeats() {
        val bands = EdgeSpectrum.fromFft(ByteArray(1024), 48000000)
        assertEquals(EdgeSpectrum.BAND_COUNT, bands.size)
        assertTrue(bands.all { it == 0f })
        assertEquals(0f, EdgeSpectrum.bass(bands), 0f)
    }

    @Test fun spectralBassAndTrebleAreDifferent() {
        val low = ByteArray(1024)
        low[2] = 120
        val high = ByteArray(1024)
        high[200] = 120
        assertTrue(EdgeSpectrum.bass(EdgeSpectrum.fromFft(low, 48000000)) > 0f)
        assertEquals(0f, EdgeSpectrum.bass(EdgeSpectrum.fromFft(high, 48000000)), 0f)
    }

    @Test fun invalidInputIsSafe() {
        assertTrue(EdgeSpectrum.fromFft(ByteArray(0), 48000000).all { it == 0f })
        assertTrue(EdgeSpectrum.fromFft(ByteArray(1024), 0).all { it == 0f })
        assertEquals(EdgeMode.WAVE, EdgeSpectrum.mode("unrecognized"))
        assertEquals(NotificationEdgeMode.CHARACTER, EdgeSpectrum.notificationMode(null))
    }
}
