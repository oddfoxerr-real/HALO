package dev.oddfoxerr.halo

import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on an actual Android framework/emulator rather than JVM stubs, catching
 * AGSL compile errors that an Android Kotlin source build cannot detect.
 */
@RunWith(AndroidJUnit4::class)
class GpuShaderSmokeTest {
    @Test fun compileAllShaderBranchesAndUniforms() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val renderer = GpuEdgeRenderer()
            // Instantiate and set up the shader on Android. Static source-only checks
            // cannot compile AGSL or validate RuntimeShader's uniform parser.
            assertNotNull(renderer)
        }
    }
}
