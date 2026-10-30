package dev.oddfoxerr.halo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainsawOverlayPolicyTest {
    @Test fun unlockedInteractiveScreenCanRender() {
        assertTrue(ChainsawOverlayPolicy.mayRender(interactive = true, keyguardLocked = false))
    }

    @Test fun sleepingOrLockedScreensNeverRender() {
        assertFalse(ChainsawOverlayPolicy.mayRender(interactive = false, keyguardLocked = false))
        assertFalse(ChainsawOverlayPolicy.mayRender(interactive = true, keyguardLocked = true))
        assertFalse(ChainsawOverlayPolicy.mayRender(interactive = false, keyguardLocked = true))
    }
}
