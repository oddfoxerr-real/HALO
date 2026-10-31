package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Test

class NotchSettingsTest {
    @Test fun defaultDoesNotDrawUnexpectedCutout() {
        assertEquals(NotchMode.OFF, NotchSettings().mode)
    }

    @Test fun manualModesRemainStable() {
        assertEquals(NotchMode.CIRCLE, NotchSettings(mode = NotchMode.CIRCLE).normalized().mode)
        assertEquals(NotchMode.PILL, NotchSettings(mode = NotchMode.PILL).normalized().mode)
        assertEquals(NotchMode.AUTO, NotchSettings(mode = NotchMode.AUTO).normalized().mode)
    }

    @Test fun offsetsAndSizesAreClamped() {
        val value = NotchSettings(mode = NotchMode.PILL,
            xOffsetDp = -900f, topDp = 900f, widthDp = 1f,
            heightDp = 1000f, gapDp = -12f).normalized()
        assertEquals(-120f, value.xOffsetDp, .001f)
        assertEquals(125f, value.topDp, .001f)
        assertEquals(12f, value.widthDp, .001f)
        assertEquals(90f, value.heightDp, .001f)
        assertEquals(0f, value.gapDp, .001f)
    }
}
