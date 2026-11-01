package dev.oddfoxerr.halo

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
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

    @Test fun allPaintStylesCompileAndProduceVisibleGpuPixels() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val width = 120
        val height = 240
        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val renderer = HardwareRenderer()
        val node = RenderNode("HALO painted GPU smoke")
        val sourceBitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        sourceBitmap.eraseColor(Color.rgb(200, 88, 37))
        try {
            renderer.isOpaque = false
            renderer.setSurface(reader.surface)
            node.setPosition(0, 0, width, height)
            val canvas = node.beginRecording(width, height)
            val shader = PaintedScreenView.compileForTest()
            val input = BitmapShader(sourceBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            input.setLocalMatrix(Matrix().apply {
                setScale(width / sourceBitmap.width.toFloat(), height / sourceBitmap.height.toFloat())
            })
            shader.setInputShader("screen", input)
            shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
            shader.setFloatUniform("brush", 48f)
            shader.setFloatUniform("strength", 1f)
            shader.setFloatUniform("relief", 1f)
            val paint = Paint().apply { this.shader = shader }
            for (style in PaintedStyle.entries) {
                shader.setIntUniform("style", style.ordinal)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            node.endRecording()
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            var received = reader.acquireLatestImage()
            val until = android.os.SystemClock.uptimeMillis() + 2000L
            while (received == null && android.os.SystemClock.uptimeMillis() < until) {
                android.os.SystemClock.sleep(10L)
                received = reader.acquireLatestImage()
            }
            val frame = checkNotNull(received) { "No GPU painted frame" }
            frame.use {
                val buffer = checkNotNull(it.hardwareBuffer)
                buffer.use { surface ->
                    val bitmap = checkNotNull(Bitmap.wrapHardwareBuffer(
                        surface, ColorSpace.get(ColorSpace.Named.SRGB)))
                    try {
                        val software = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                        try {
                            val color = software.getPixel(width / 2, height / 2)
                            assertEquals("Impasto must cover the complete picture", 255, Color.alpha(color))
                            // A constant-colour source must still show physical
                            // pigment ridges, rather than returning a flat tint.
                            var darkest = 255
                            var lightest = 0
                            for (sy in 12 until height - 12 step 13) {
                                for (sx in 12 until width - 12 step 13) {
                                    val px = software.getPixel(sx, sy)
                                    assertEquals("Full-screen oil paint has holes", 255, Color.alpha(px))
                                    val luminance = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3
                                    darkest = kotlin.math.min(darkest, luminance)
                                    lightest = kotlin.math.max(lightest, luminance)
                                }
                            }
                            assertTrue("Painted surface lacks discernible ridge lighting",
                                lightest - darkest >= 5)
                        } finally {
                            software.recycle()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            renderer.destroy()
            node.discardDisplayList()
            reader.close()
            sourceBitmap.recycle()
        }
    }
}
