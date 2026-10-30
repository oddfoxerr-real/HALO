package dev.oddfoxerr.halo

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Time-based motor and adaptive bass-onset detector; contains no Android dependencies. */
class ChainDynamics {
    var speed = 0f; private set
    var travel = 0f; private set
    var kick = 0f; private set
    var jam = 1f; private set
    var energy = 0f; private set
    private var average = .03f
    private var previous = 0f
    private var sinceBeat = 10f
    private var quiet = 1f

    fun reset() {
        speed = 0f; travel = 0f; kick = 0f; jam = 1f; energy = 0f
        average = .03f; previous = 0f; sinceBeat = 10f; quiet = 1f
    }

    fun step(bass: Float, dt: Float, sensitivity: Float = 1f, rate: Float = 1f) {
        val time = dt.coerceIn(0f, .1f)
        val input = if (bass.isFinite()) (bass * sensitivity).coerceIn(0f, 1f) else 0f
        sinceBeat += time
        val beat = input > .025f && input > average * 1.45f &&
            input - previous > .009f && sinceBeat > .16f
        average += (input - average) * (1f - exp(-time * 2f))
        previous = input
        if (beat) { kick = 1f; sinceBeat = 0f; quiet = 0f }
        // Integrate the motor at <=240 Hz independently of the UI/audio polling rate.
        val steps = kotlin.math.ceil(time * 240f).toInt().coerceAtLeast(1)
        val tick = time / steps
        repeat(steps) {
            energy += (input - energy) * (1f - exp(-tick * 12f))
            quiet = if (input < .018f) quiet + tick else 0f
            val targetJam = if (quiet > .18f) 1f else 0f
            jam += (targetJam - jam) * (1f - exp(-tick * 10f))
            val targetSpeed = if (quiet > .4f) 0f else (energy * 5f + kick * 2f).coerceAtMost(3f)
            val oldSpeed = speed
            speed += (targetSpeed - speed) * (1f - exp(-tick * 12f))
            if (speed < .002f) speed = 0f
            travel = (travel + (oldSpeed + speed) * .5f * rate.coerceIn(.25f, 3f) * tick * 180f) % 100000f
            kick *= exp(-tick * 7f)
        }
    }

    companion object {
        /** Android Visualizer FFT is signed interleaved real/imaginary bytes; sample rate is mHz. */
        fun bassEnergy(fft: ByteArray, samplingRateMilliHz: Int): Float {
            if (fft.size < 4 || samplingRateMilliHz <= 0) return 0f
            val hzPerBin = samplingRateMilliHz / 1000f / fft.size
            val first = max(1, (35f / hzPerBin).toInt())
            val last = (220f / hzPerBin).toInt().coerceAtMost(fft.size / 2 - 1)
            if (last < first) return 0f
            var power = 0f
            for (bin in first..last) {
                val real = fft[bin * 2].toInt() / 128f
                val imaginary = fft[bin * 2 + 1].toInt() / 128f
                power += real * real + imaginary * imaginary
            }
            return sqrt(power / (last - first + 1)).coerceIn(0f, 1f)
        }
    }
}
