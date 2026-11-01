package dev.oddfoxerr.halo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise pixels from Android's hardware renderer, not just shader compilation. */
@RunWith(AndroidJUnit4::class)
class MinimalEdgeRenderTest {
    @Test fun minimalMaterialsStayAtTheEdgeAndReactToTheSignal() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        for (mode in EdgeMode.entries.filter { it != EdgeMode.ELECTRIC_ARCS }) {
            val quiet = render(mode, 0f)
            val soft = render(mode, .15f)
            val loud = render(mode, 1f)
            assertEquals("$mode must disappear in silence", 0L, alphaSum(quiet))
            assertTrue("$mode must respond to music", alphaSum(loud) > alphaSum(soft) * 2)
            for (y in 32 until HEIGHT - 32) {
                for (x in 32 until WIDTH - 32) {
                    assertEquals("$mode covers content at $x,$y", 0, Color.alpha(loud.getPixel(x, y)))
                }
            }
            quiet.recycle(); soft.recycle(); loud.recycle()
            val camera = render(mode, .7f, compact = true)
            assertTrue("$mode must also render around the camera", alphaSum(camera) > 0)
            assertEquals(0, Color.alpha(camera.getPixel(WIDTH / 2, HEIGHT / 2)))
            camera.recycle()
        }
    }

    private fun alphaSum(bitmap: Bitmap): Long {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.sumOf { Color.alpha(it).toLong() }
    }

    private fun render(mode: EdgeMode, level: Float, compact: Boolean = false): Bitmap {
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        val node = RenderNode("HALO material test")
        try {
            renderer.isOpaque = false
            renderer.setSurface(reader.surface)
            node.setPosition(0, 0, WIDTH, HEIGHT)
            val canvas = node.beginRecording(WIDTH, HEIGHT)
            val bounds = if (compact) RectF(WIDTH / 2f - 24f, HEIGHT / 2f - 24f,
                WIDTH / 2f + 24f, HEIGHT / 2f + 24f)
                else RectF(6f, 6f, WIDTH - 6f, HEIGHT - 6f)
            val rendered = ShaderEdgeEngine().render(canvas, WIDTH, HEIGHT, bounds,
                if (compact) 24f else 28f, mode,
                FloatArray(EdgeSpectrum.BAND_COUNT) { level }, level, 0f, 2f,
                1f, 1f, .75f, 1f, 0xFF4CAEFF.toInt(), 0xFFB7F5FF.toInt(),
                level * .5f, compact = compact,
                dynamicLow = level, dynamicMid = level * .6f,
                dynamicHigh = level * .4f, dynamicHit = level * .6f,
                dynamicForce = 2.4f, pixelDensity = 1f)
            node.endRecording()
            assertTrue("$mode failed to render", rendered)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            var image = reader.acquireLatestImage()
            val deadline = SystemClock.uptimeMillis() + 2_000L
            while (image == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(10L)
                image = reader.acquireLatestImage()
            }
            val frame = checkNotNull(image) { "No rendered frame for $mode" }
            frame.use {
                val buffer = checkNotNull(frame.hardwareBuffer)
                buffer.use {
                    val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(buffer,
                        ColorSpace.get(ColorSpace.Named.SRGB)))
                    try {
                        return checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
                    } finally { hardware.recycle() }
                }
            }
        } finally {
            renderer.destroy()
            node.discardDisplayList()
            reader.close()
        }
    }

    private companion object {
        const val WIDTH = 240
        const val HEIGHT = 480
    }
}
