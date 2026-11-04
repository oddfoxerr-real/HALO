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

/**
 * A flat source used to look pixel-perfect under the old blur-only paint
 * shader. This GPU test requires visible brush lighting and pigment variation
 * from a uniform input, making the previous invisible effect regress visibly.
 */
@RunWith(AndroidJUnit4::class)
class VisibleBrushStrokeGpuTest {
    @Test
    fun flatSourceBecomesDistinctRaisedBrushStrokes() {
        if (Build.VERSION.SDK_INT < 33) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = Bitmap.createBitmap(192, 288, Bitmap.Config.ARGB_8888)
        original.eraseColor(Color.rgb(152, 118, 89))
        val view = PaintShaderView(context).apply {
            layout(0, 0, 192, 288)
            configure(PaintedScreenPreferences(
                style = PaintedStyle.OIL,
                strength = 1f,
                brushSize = 1.4f,
                relief = 1.6f
            ))
            show(original)
        }
        val reader = ImageReader.newInstance(
            192, 288, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val renderer = HardwareRenderer()
        val node = RenderNode("HALO impasto strokes")
        try {
            renderer.isOpaque = false
            renderer.setSurface(reader.surface)
            node.setPosition(0, 0, 192, 288)
            val canvas = node.beginRecording(192, 288)
            view.draw(canvas)
            node.endRecording()
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            var image = reader.acquireLatestImage()
            val deadline = SystemClock.uptimeMillis() + 3500L
            while (image == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(10L)
                image = reader.acquireLatestImage()
            }
            val output = checkNotNull(image).use { captured ->
                checkNotNull(captured.hardwareBuffer).use { buffer ->
                    val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(
                        buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                    try {
                        checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
                    } finally {
                        hardware.recycle()
                    }
                }
            }
            try {
                var minBrightness = 255
                var maxBrightness = 0
                val unique = HashSet<Int>()
                var altered = 0
                for (y in 20 until 260 step 7) {
                    for (x in 20 until 176 step 7) {
                        val pixel = output.getPixel(x, y)
                        val luminance = (Color.red(pixel) * 21 +
                            Color.green(pixel) * 72 +
                            Color.blue(pixel) * 7) / 100
                        minBrightness = minOf(minBrightness, luminance)
                        maxBrightness = maxOf(maxBrightness, luminance)
                        unique.add(pixel and 0x00ffffff)
                        if (kotlin.math.abs(Color.red(pixel) - 152) +
                            kotlin.math.abs(Color.green(pixel) - 118) +
                            kotlin.math.abs(Color.blue(pixel) - 89) > 9) altered++
                    }
                }
                assertTrue(
                    "Paint shader looks like a flat screenshot: insufficient contrast",
                    maxBrightness - minBrightness >= 12
                )
                assertTrue("No individual paint surface variation", unique.size >= 20)
                assertTrue("AGSL painting did not alter enough of the flat image", altered > 50)
            } finally {
                output.recycle()
            }
        } finally {
            view.clearSource()
            renderer.destroy()
            node.discardDisplayList()
            reader.close()
        }
    }
}
