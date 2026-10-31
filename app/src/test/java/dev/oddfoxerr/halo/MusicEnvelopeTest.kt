package dev.oddfoxerr.halo

import org.junit.Assert.*
import org.junit.Test

class MusicEnvelopeTest {
    @Test fun aStoppedCaptureDecaysWithoutAnotherCallback() {
        val motion = MusicEnvelope()
        motion.accept(FloatArray(24) { .6f }, .3f, 1000L, 1f)
        repeat(10) { motion.advance(1000L + it * 16, .016f, 1f) }
        assertTrue(motion.bands.any { it > .1f })
        var running = true
        repeat(300) { running = motion.advance(1200L + it * 16, .016f, 1f) }
        assertFalse(running)
        assertTrue(motion.bands.all { it == 0f })
        assertEquals(0f, motion.pulse, 0f)
    }
    @Test fun invalidSignalsCannotPoisonTheAnimation() {
        val motion = MusicEnvelope()
        motion.accept(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY), Float.NaN, 1000L, 1f)
        assertFalse(motion.advance(1000L, .016f, 1f))
        assertTrue(motion.bands.all { it == 0f })
    }
    @Test fun clearDoesNotLeaveATargetThatRestartsRendering() {
        val motion = MusicEnvelope()
        motion.accept(FloatArray(24) { 1f }, 1f, 1000L, 3f)
        motion.advance(1000L, .016f, 1f)
        motion.clear()
        assertFalse(motion.advance(1016L, .016f, 1f))
    }
    @Test fun notificationAndScreenGatesDoNotNeedAudioEvents() {
        assertNotNull(MusicVisibility.reason(false, true, false, false, true, true, true))
        assertNotNull(MusicVisibility.reason(true, false, false, false, true, true, true))
        assertNotNull(MusicVisibility.reason(true, true, false, true, true, true, true))
        assertNotNull(MusicVisibility.reason(true, true, false, false, true, false, true))
        assertNull(MusicVisibility.reason(true, true, false, false, true, true, true))
    }
    @Test fun quietHoursAndDisallowedPlayerSuppressOtherwiseLiveSignal() {
        assertNotNull(MusicVisibility.reason(true, true, true, false, true, true, true))
        assertNotNull(MusicVisibility.reason(true, true, false, false, false, true, true))
        assertNotNull(MusicVisibility.reason(true, true, false, false, true, true, false))
    }
}
