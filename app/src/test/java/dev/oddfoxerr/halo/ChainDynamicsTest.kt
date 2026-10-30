package dev.oddfoxerr.halo

import org.junit.Assert.*
import org.junit.Test

class ChainDynamicsTest {
    @Test fun silenceStaysJammedAndDoesNotInventBeats() {
        val motor = ChainDynamics()
        repeat(300) { motor.step(0f, 1f / 30) }
        assertEquals(0f, motor.travel, 0f)
        assertEquals(1f, motor.jam, .001f)
        assertEquals(0f, motor.kick, 0f)
    }
    @Test fun hitRestartsThenSilenceClogsAndNextHitRestarts() {
        val motor = ChainDynamics()
        motor.step(.7f, 1f / 30)
        assertTrue(motor.speed > 0f)
        assertTrue(motor.kick > .7f)
        repeat(120) { motor.step(0f, 1f / 30) }
        assertTrue(motor.jam > .99f)
        assertEquals(0f, motor.speed, .002f)
        val stopped = motor.travel
        motor.step(.8f, 1f / 30)
        assertTrue(motor.travel > stopped)
        assertTrue(motor.kick > .7f)
    }
    @Test fun sustainedToneDoesNotGenerateRepeatedOnsets() {
        val motor = ChainDynamics()
        repeat(90) { motor.step(.5f, 1f / 30) }
        assertTrue(motor.kick < .001f)
    }
    @Test fun fftMeasuresBassAndRejectsTrebleAndInvalidInput() {
        val fft = ByteArray(1024)
        fft[4] = 90 // 93.75 Hz at 48 kHz
        assertTrue(ChainDynamics.bassEnergy(fft, 48000000) > .1f)
        fft[4] = 0
        fft[200] = 100
        assertEquals(0f, ChainDynamics.bassEnergy(fft, 48000000), 0f)
        assertEquals(0f, ChainDynamics.bassEnergy(fft, 0), 0f)
    }
    @Test fun frameRateDoesNotChangeTravelMaterially() {
        fun run(fps: Int): Float {
            val motor = ChainDynamics()
            repeat(fps * 3) { motor.step(.4f, 1f / fps) }
            return motor.travel
        }
        assertEquals(run(30), run(60), 10f)
    }
}
