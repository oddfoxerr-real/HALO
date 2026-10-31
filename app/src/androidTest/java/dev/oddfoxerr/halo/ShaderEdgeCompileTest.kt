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
            val video = ShaderEdgeEngine().compileVideoForTest()
            video.setFloatUniform("frame", 8f, 28f, 1072f, 2372f)
            video.setFloatUniform("width", 35f)
            video.setFloatUniform("softness", .8f)
            video.setFloatUniform("brightness", 1f)
            video.setFloatUniform("topColor", 1f, 0f, 0f)
            video.setFloatUniform("rightColor", 0f, 1f, 0f)
            video.setFloatUniform("bottomColor", 0f, 0f, 1f)
            video.setFloatUniform("leftColor", 1f, 1f, 0f)
            assertNotNull(video)
        }
    }
}
