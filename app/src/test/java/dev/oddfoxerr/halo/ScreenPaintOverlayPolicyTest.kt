package dev.oddfoxerr.halo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPaintOverlayPolicyTest {
    @Test fun touchesRemainAllowedByTheWindowOpacityPolicy() {
        assertTrue(ScreenPaintOverlayPolicy.opacityIsSafe(
            ScreenPaintOverlayPolicy.PREFERRED_ALPHA))
        assertFalse(ScreenPaintOverlayPolicy.opacityIsSafe(0.84f))
        assertFalse(ScreenPaintOverlayPolicy.opacityIsSafe(Float.NaN))
    }

    @Test fun hiddenOrProtectedCaptureNeverDisplaysAStaleOverlay() {
        assertTrue(ScreenPaintOverlayPolicy.shouldDisplayFrame(true, true))
        assertFalse(ScreenPaintOverlayPolicy.shouldDisplayFrame(false, true))
        assertFalse(ScreenPaintOverlayPolicy.shouldDisplayFrame(true, false))
    }
}
