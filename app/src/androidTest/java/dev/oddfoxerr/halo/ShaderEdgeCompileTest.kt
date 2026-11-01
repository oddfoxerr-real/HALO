package dev.oddfoxerr.halo

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android RuntimeShader compilation must be tested against an Android renderer. */
@RunWith(AndroidJUnit4::class)
class ShaderEdgeCompileTest {
    @Test fun compileOrganicEdgeShaderOnAndroid() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val shader = ShaderEdgeEngine().compileForTest()
            assertNotNull(shader)
            // All twenty-four designs share the same plain/video shader, including side flames.
            shader.setFloatUniform("frame", 8f, 28f, 1072f, 2372f)
            shader.setFloatUniform("videoFrame", 8f, 28f, 1072f, 2372f)
            shader.setIntUniform("videoMode", 1)
            shader.setFloatUniform("ambientMotion", 1f)
            for (side in listOf("top", "right", "bottom", "left")) {
                for (index in 0 until 4) {
                    shader.setFloatUniform("${side}${index}",
                        (index + 1) / 4f, .65f, .25f)
                }
            }
            for (mode in EdgeMode.entries) {
                shader.setIntUniform("design", mode.ordinal)
                assertNotNull(shader)
            }
            shader.setIntUniform("videoMode", 0)
            assertNotNull(shader)
        }
    }
}
