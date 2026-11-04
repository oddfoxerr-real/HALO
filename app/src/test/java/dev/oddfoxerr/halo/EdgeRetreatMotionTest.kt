package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeRetreatMotionTest {
    @Test fun canReverseBeforeFinishingAnExit() {
        val motion = EdgeRetreatMotion()
        assertTrue(motion.setTarget(false))
        repeat(8) { motion.advance(.016f) }
        val midpoint = motion.visibility
        assertTrue(midpoint > 0f && midpoint < 1f)
        assertTrue(motion.setTarget(true))
        repeat(8) { motion.advance(.016f) }
        assertTrue(motion.visibility > midpoint)
        repeat(60) { motion.advance(.016f) }
        assertEquals(1f, motion.visibility, .001f)
    }

    @Test fun fullExitDoesNotFabricateMotion() {
        val motion = EdgeRetreatMotion()
        motion.setTarget(false)
        repeat(90) { motion.advance(.016f) }
        assertEquals(0f, motion.visibility, .001f)
        assertFalse(motion.advance(.016f))
        motion.jumpToVisible()
        assertEquals(1f, motion.visibility, .001f)
    }

    @Test fun focusOnlyShowsOnMusicPlayerLauncherOrHalo() {
        val homes = setOf("com.sec.android.app.launcher")
        val own = "dev.oddfoxerr.halo"
        assertTrue(MusicFocusRouting.revealFor(null, "com.spotify.music", homes, own))
        assertTrue(MusicFocusRouting.revealFor("com.spotify.music", "com.spotify.music", homes, own))
        assertTrue(MusicFocusRouting.revealFor("com.sec.android.app.launcher", "com.spotify.music", homes, own))
        assertTrue(MusicFocusRouting.revealFor(own, "com.spotify.music", homes, own))
        assertFalse(MusicFocusRouting.revealFor("com.whatsapp", "com.spotify.music", homes, own))
    }
}
