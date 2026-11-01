package dev.oddfoxerr.halo

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class PaintedScreenGpuTest {
    @Test fun rgbaRowsDecodeWithPaddingAndBlackPrivacyFramesStayClear() {
        val width = 16
        val height = 16
        val stride = width * 4 + 8
        val raw = ByteBuffer.allocate(stride * height)
        for (y in 0 until height) for (x in 0 until width) {
            val at = y * stride + x * 4
            raw.put(at, 54.toByte())
            raw.put(at + 1, 148.toByte())
            raw.put(at + 2, 207.toByte())
            raw.put(at + 3, 255.toByte())
        }
        val decoded = PaintedFrameDecoder.decode(raw, width, height, stride, 4)
        assertNotNull(decoded)
        assertEquals(Color.rgb(54, 148, 207), decoded!!.getPixel(12, 12))
        decoded.recycle()
        assertEquals(null, PaintedFrameDecoder.decode(
            ByteBuffer.allocate(stride * height), width, height, stride, 4))
    }

    @Test fun allStylesPaintOpaquePigmentWithVisibleRelief() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val source = Bitmap.createBitmap(96, 160, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(192, 91, 42))
        try {
            for (style in PaintedStyle.entries) {
                val rendered = gpuRender(source, style, 25f, 1f)
                try {
                    var darkest = 255
                    var lightest = 0
                    for (y in 15 until 225 step 12) for (x in 12 until 110 step 11) {
                        val pixel = rendered.getPixel(x, y)
                        assertEquals("$style: transparent hole", 255, Color.alpha(pixel))
                        val luminance = (Color.red(pixel) + Color.green(pixel) +
                            Color.blue(pixel)) / 3
                        darkest = kotlin.math.min(darkest, luminance)
                        lightest = kotlin.math.max(lightest, luminance)
                    }
                    assertTrue("$style has no visible brush shading", lightest - darkest >= 4)
                } finally { rendered.recycle() }
            }
        } finally { source.recycle() }
    }

    @Test fun smoothGradientDoesNotTurnIntoPixelTiles() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val width = 120
        val height = 240
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val red = 35 + (x * 185 / (width - 1))
            pixels[y * width + x] = Color.rgb(red, 106, 123)
        }
        source.setPixels(pixels, 0, width, 0, 0, width, height)
        try {
            val render = gpuRender(source, PaintedStyle.OIL, 22f, 1f)
            try {
                val distinct = HashSet<Int>()
                for (x in 12..108 step 3) {
                    val color = render.getPixel(x, height / 2)
                    assertEquals(255, Color.alpha(color))
                    distinct.add(Color.red(color))
                }
                assertTrue(
                    "Painter output is still stepped/pixelated: ${distinct.size} shades",
                    distinct.size >= 17
                )
                val left = Color.red(render.getPixel(8, height / 2))
                val right = Color.red(render.getPixel(112, height / 2))
                assertTrue("Continuous gradient lost source colours", right - left > 60)
            } finally { render.recycle() }
        } finally { source.recycle() }
    }

    private fun gpuRender(source: Bitmap, style: PaintedStyle, brush: Float, relief: Float): Bitmap {
        val width = 120
        val height = 240
        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val renderer = HardwareRenderer()
        val node = RenderNode("HALO continuous painter shader test")
        try {
            renderer.isOpaque = false
            renderer.setSurface(reader.surface)
            node.setPosition(0, 0, width, height)
            val canvas = node.beginRecording(width, height)
            val effect = PaintedScreenView.compileForTest()
            val bitmapShader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            bitmapShader.setLocalMatrix(Matrix().apply {
                setScale(width / source.width.toFloat(), height / source.height.toFloat())
            })
            effect.setInputShader("screen", bitmapShader)
            effect.setFloatUniform("resolution", width.toFloat(), height.toFloat())
            effect.setFloatUniform("brush", brush)
            effect.setFloatUniform("strength", 1f)
            effect.setFloatUniform("relief", relief)
            effect.setIntUniform("style", style.ordinal)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(),
                Paint().apply { shader = effect })
            node.endRecording()
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

            var frame = reader.acquireLatestImage()
            val deadline = SystemClock.uptimeMillis() + 2500L
            while (frame == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(10L)
                frame = reader.acquireLatestImage()
            }
            val acquired = checkNotNull(frame) { "Android did not return a GPU shader frame" }
            return acquired.use { image ->
                val buffer = checkNotNull(image.hardwareBuffer)
                buffer.use {
                    val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(
                        it, ColorSpace.get(ColorSpace.Named.SRGB)))
                    try {
                        checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
                    } finally { hardware.recycle() }
                }
            }
        } finally {
            renderer.destroy()
            node.discardDisplayList()
            reader.close()
        }
    }
}
