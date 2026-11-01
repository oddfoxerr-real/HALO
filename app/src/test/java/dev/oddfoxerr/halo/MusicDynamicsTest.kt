package dev.oddfoxerr.halo

import org.junit.Assert.*
import org.junit.Test

class MusicDynamicsTest {
    @Test fun lowMidHighBandsProduceDifferentEnvelopes() {
        val motion = MusicDynamics()
        motion.observe(FloatArray(24) { if (it < 6) .8f else 0f }, .6f, 1000L)
        motion.advance(.016f, true)
        assertTrue(motion.low > .2f)
        assertEquals(0f, motion.mid, .001f)
        assertEquals(0f, motion.high, .001f)
        motion.observe(FloatArray(24) { if (it in 6..15) .8f else 0f }, 0f, 1040L)
        motion.advance(.016f, true)
        assertTrue(motion.mid > 0f)
        motion.observe(FloatArray(24) { if (it > 15) .9f else 0f }, 0f, 1080L)
        motion.advance(.016f, true)
        assertTrue(motion.high > 0f)
    }

    @Test fun sustainedNoteDoesNotFabricateRepeatedBeats() {
        val motion = MusicDynamics()
        val bands = FloatArray(24) { .6f }
        motion.observe(bands, .6f, 1000L)
        motion.advance(.016f, true)
        val initial = motion.hit
        repeat(200) { frame ->
            motion.observe(bands, .6f, 1033L + frame * 20L)
            motion.advance(.02f, true)
        }
        assertTrue(initial > 0f)
        assertTrue(motion.hit < .003f)
    }

    @Test fun newPercussiveOnsetTriggersAfterRefractoryWindow() {
        val motion = MusicDynamics()
        motion.observe(FloatArray(24) { .8f }, .6f, 1000L)
        motion.advance(.016f, true)
        repeat(30) { motion.advance(.02f, false) }
        motion.observe(FloatArray(24), 0f, 1700L)
        motion.advance(.03f, true)
        motion.observe(FloatArray(24) { .9f }, .9f, 1800L)
        assertTrue(motion.hit > .6f)
    }

    @Test fun captureLossDecaysBandsAndOnsetWithoutRandomMotion() {
        val motion = MusicDynamics()
        motion.observe(FloatArray(24) { .85f }, .8f, 1000L)
        repeat(100) { motion.advance(.02f, false) }
        assertTrue(motion.low < .005f)
        assertTrue(motion.mid < .005f)
        assertTrue(motion.high < .005f)
        assertTrue(motion.hit < .005f)
        motion.reset()
        assertEquals(0f, motion.hit, 0f)
    }

    @Test fun invalidInputDoesNotPoisonMotion() {
        val motion = MusicDynamics()
        motion.observe(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY), Float.NaN, 1000L)
        motion.advance(.02f, true)
        assertEquals(0f, motion.low, .001f)
        assertTrue(motion.hit.isFinite())
    }
}
