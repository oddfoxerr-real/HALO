package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloVersionTest {
    @Test fun versionsSortNumericallyInsteadOfAsStrings() {
        val old = requireNotNull(HaloVersion.parse("v0.20.1"))
        val next = requireNotNull(HaloVersion.parse("0.21.0"))
        val future = requireNotNull(HaloVersion.parse("v1.0.0"))
        assertTrue(next > old)
        assertTrue(future > next)
        assertEquals(HaloVersion(0, 21, 0), next)
    }
    @Test fun invalidOrUnstableTagsAreNotAutoInstalled() {
        assertNull(HaloVersion.parse("v0.21.0-beta"))
        assertNull(HaloVersion.parse("v0.21"))
        assertNull(HaloVersion.parse("v0.21.0+unsafe"))
        assertNull(HaloVersion.parse("v00.21.0"))
        assertNull(HaloVersion.parse("../../invalid"))
    }
}
