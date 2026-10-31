package dev.oddfoxerr.halo

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies AGSL compilation and uniform contracts on a real Android framework. */
@RunWith(AndroidJUnit4::class)
class ShaderEdgeCompileTest {
    @Test fun compileAllGpuDesignBranches() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val shader = ShaderEdgeEngine().compileForTest()
            assertNotNull(shader)
            shader.setFloatUniform("resolution", 1080f, 2400f)
            shader.setFloatUniform("frame", 8f, 48f, 1072f, 2352f)
            shader.setFloatUniform("radius", 60f)
            shader.setFloatUniform("seconds", 2f)
            shader.setFloatUniform("phase", .25f)
            shader.setFloatUniform("bass", .72f)
            shader.setFloatUniform("audio", .40f, .62f, .37f, .84f)
            shader.setFloatUniform("thickness", 2f)
            shader.setFloatUniform("spread", 1f)
            shader.setFloatUniform("glow", .7f)
            shader.setFloatUniform("brightness", .95f)
            shader.setFloatUniform("first", .55f, .32f, .95f)
            shader.setFloatUniform("second", .12f, .90f, .73f)
            shader.setFloatUniform("density", 1.3f)
            shader.setFloatUniform("dotSize", 1.1f)
            for (mode in EdgeMode.entries) {
                shader.setIntUniform("design", mode.ordinal)
                for (gradient in EdgeGradient.entries) {
                    shader.setIntUniform("gradientMode", gradient.ordinal)
                }
            }
        }
    }
}
