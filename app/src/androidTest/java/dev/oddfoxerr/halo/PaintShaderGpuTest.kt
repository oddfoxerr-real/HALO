package dev.oddfoxerr.halo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Draws an actual AGSL paint frame through HardwareRenderer, not just a
 * software Canvas or a mock that could silently pass while drawing unfiltered
 * images on the real device.
 */
@RunWith(AndroidJUnit4::class)
class PaintShaderGpuTest {
    @Test
    fun painterTransformsPixelsOnGpuAndRetainsImageStructure() {
        if (Build.VERSION.SDK_INT < 33) return

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(160 * 240)
        for (y in 0 until 240) {
            for (x in 0 until 160) {
                val red = 25 + x * 170 / 159
                val green = if (y > 110) 172 else 67
                val blue = 48 + y * 128 / 239
                pixels[y * 160 + x] = Color.rgb(red, green, blue)
            }
        }
        image.setPixels(pixels, 0, 160, 0, 0, 160, 240)
        val view = PaintShaderView(context)
        view.layout(0, 0, 160, 240)
        view.configure(PaintedScreenPreferences(
            strength = 1f, brushSize = 2.3f, relief = 1.6f
        ))
        view.show(image)

        val reader = ImageReader.newInstance(
            160, 240, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val gpu = HardwareRenderer()
        val node = RenderNode("HALO real paint shader GPU")
        try {
            gpu.isOpaque = false
            gpu.setSurface(reader.surface)
            node.setPosition(0, 0, 160, 240)
            val recording = node.beginRecording(160, 240)
            view.draw(recording)
            node.endRecording()
            gpu.setContentRoot(node)
            gpu.createRenderRequest().setWaitForPresent(true).syncAndDraw()

            var captured = reader.acquireLatestImage()
            val deadline = SystemClock.uptimeMillis() + 3000L
            while (captured == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(10L)
                captured = reader.acquireLatestImage()
            }

            val output = checkNotNull(captured).use { frame ->
                checkNotNull(frame.hardwareBuffer).use { buffer ->
                    val wrapped = checkNotNull(Bitmap.wrapHardwareBuffer(
                        buffer, ColorSpace.get(ColorSpace.Named.SRGB)
                    ))
                    try {
                        checkNotNull(wrapped.copy(Bitmap.Config.ARGB_8888, false))
                    } finally {
                        wrapped.recycle()
                    }
                }
            }

            try {
                var transformed = 0
                for (y in 16..220 step 13) {
                    for (x in 16..140 step 11) {
                        val original = image.getPixel(x, y)
                        val painted = output.getPixel(x, y)
                        val delta = abs(Color.red(original) - Color.red(painted)) +
                            abs(Color.green(original) - Color.green(painted)) +
                            abs(Color.blue(original) - Color.blue(painted))
                        if (delta > 6) transformed++
                    }
                }
                assertTrue(
                    "The GPU returned raw unpainted pixels; AGSL did not run",
                    transformed > 25
                )
                assertTrue(
                    "The painted image lost the gradient of the source",
                    Color.red(output.getPixel(145, 80)) >
                        Color.red(output.getPixel(15, 80)) + 60
                )
                assertTrue(
                    "The painted frame is unexpectedly transparent",
                    Color.alpha(output.getPixel(80, 120)) >= 240
                )
            } finally {
                output.recycle()
            }
        } finally {
            view.clearSource()
            gpu.destroy()
            node.discardDisplayList()
            reader.close()
        }
    }
}
