package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintCaptureSizingTest {
    @Test fun portraitGalaxyUsesEnoughDetail() {
        val divisor = PaintCaptureSizing.divisorFor(1080, 2340, true, true)
        assertEquals(2, divisor)
        assertTrue(1080 / divisor <= 1024)
        assertTrue(2340 / divisor <= 2048)
    }

    @Test fun landscapeGalaxyNeverExceedsDecoderLimits() {
        val divisor = PaintCaptureSizing.divisorFor(2340, 1080, true, true)
        assertEquals(3, divisor)
        assertTrue(2340 / divisor <= 1024)
        assertTrue(1080 / divisor <= 2048)
    }

    @Test fun largeTabletLandscapeAlsoFitsLimits() {
        val divisor = PaintCaptureSizing.divisorFor(3200, 2000, true, true)
        assertTrue(3200 / divisor <= 1024)
        assertTrue(2000 / divisor <= 2048)
    }

    @Test fun oldVideoAndPreviewModesArePreserved() {
        assertEquals(3, PaintCaptureSizing.divisorFor(2340, 1080, false, true))
        assertEquals(6, PaintCaptureSizing.divisorFor(2340, 1080, false, false))
    }
}
