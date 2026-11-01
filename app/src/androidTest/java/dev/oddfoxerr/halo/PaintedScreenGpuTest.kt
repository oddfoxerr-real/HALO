package dev.oddfoxerr.halo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class PaintedScreenGpuTest {
    @Test fun rgbaDecoderRespectsRowStrideAndBlackPrivacy() {
        val width=16
        val height=16
        val stride=width*4+8
        val data=ByteBuffer.allocate(stride*height)
        for(y in 0 until height) for(x in 0 until width) {
            val p=y*stride+x*4
            data.put(p,54.toByte())
            data.put(p+1,148.toByte())
            data.put(p+2,207.toByte())
            data.put(p+3,255.toByte())
        }
        val bitmap=PaintedFrameDecoder.decode(data,width,height,stride,4)
        assertNotNull(bitmap)
        assertEquals(Color.rgb(54,148,207),bitmap!!.getPixel(8,8))
        bitmap.recycle()
        assertEquals(null,PaintedFrameDecoder.decode(
            ByteBuffer.allocate(stride*height),width,height,stride,4))
    }

    @Test fun imagePlannerHasRealMultiscaleBrushes() {
        val image=Bitmap.createBitmap(120,240,Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.rgb(155,86,37))
        val planned=PaintedStrokePlanner.plan(image,PaintedScreenPreferences())
        try {
            assertTrue(planned.strokes.size>150)
            assertTrue(planned.strokes.size<=2600)
            assertTrue(planned.strokes.map { it.length.toInt() }.distinct().size>=12)
            assertTrue(planned.strokes.map { it.brush }.distinct().size>=3)
        } finally { planned.dispose() }
    }

    @Test fun nativePainterGpuHasStrokesAndNotPixelArt() {
        val src=Bitmap.createBitmap(120,240,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(120*240)
        for(y in 0 until 240) for(x in 0 until 120) {
            pixels[y*120+x]=Color.rgb(40+x*185/119,100+y/8,121)
        }
        src.setPixels(pixels,0,120,0,0,120,240)
        val image=render(src,PaintedStyle.OIL)
        try {
            val unique=HashSet<Int>()
            var minLum=255
            var maxLum=0
            for(x in 8..110 step 3) {
                val color=image.getPixel(x,120)
                assertEquals("Brush canvas has transparent gaps",255,Color.alpha(color))
                unique.add(Color.red(color))
                val lum=(Color.red(color)+Color.green(color)+Color.blue(color))/3
                minLum=kotlin.math.min(minLum,lum)
                maxLum=kotlin.math.max(maxLum,lum)
            }
            assertTrue("Blocky mosaic detected",unique.size>=10)
            assertTrue("Paint colours lost image structure",
                Color.red(image.getPixel(113,120))-Color.red(image.getPixel(6,120))>50)
            assertTrue("Paint marks have no visible pigment variation",maxLum-minLum>10)
        } finally { image.recycle() }
    }

    @Test fun allBrushStylesDrawNontransparentCanvas() {
        for(style in PaintedStyle.entries) {
            val src=Bitmap.createBitmap(120,240,Bitmap.Config.ARGB_8888)
            src.eraseColor(Color.rgb(145,97,40))
            val frame=render(src,style)
            try {
                assertEquals(255,Color.alpha(frame.getPixel(20,20)))
                assertEquals(255,Color.alpha(frame.getPixel(85,180)))
            } finally { frame.recycle() }
        }
    }

    @Test fun protectedFramesDoNotShutDownPaintingService() {
        val guard=PaintedCaptureHealth()
        assertEquals(PaintedCaptureAction.PRESENT,guard.observe(true,false))
        assertEquals(PaintedCaptureAction.KEEP,guard.observe(false,true))
        assertEquals(PaintedCaptureAction.CLEAR,guard.observe(false,true))
        repeat(5) {
            assertEquals(PaintedCaptureAction.CLEAR,guard.observe(false,true))
        }
        assertEquals(PaintedCaptureAction.DISABLE_OVERLAY,guard.observe(false,true))
        assertTrue(guard.overlayRejected)
        assertEquals(PaintedCaptureAction.CLEAR,guard.observe(true,false))
        guard.reset()
        assertEquals(PaintedCaptureAction.PRESENT,guard.observe(true,false))
    }

    @Test fun clearingPainterErasesStaleFrameImmediately() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val image=Bitmap.createBitmap(120,240,Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.rgb(120,60,40))
        val planned=PaintedStrokePlanner.plan(image,PaintedScreenPreferences())
        val painter=PaintedScreenView(context)
        painter.layout(0,0,120,240)
        painter.setFrame(planned)
        val before=Bitmap.createBitmap(120,240,Bitmap.Config.ARGB_8888)
        painter.draw(android.graphics.Canvas(before))
        assertEquals(255,Color.alpha(before.getPixel(80,120)))
        painter.clear()
        val after=Bitmap.createBitmap(120,240,Bitmap.Config.ARGB_8888)
        painter.draw(android.graphics.Canvas(after))
        assertEquals("Stale brush overlay survived clear",0,
            Color.alpha(after.getPixel(80,120)))
        before.recycle()
        after.recycle()
    }

    private fun render(source:Bitmap,style:PaintedStyle):Bitmap {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=PaintedScreenPreferences(style=style,strength=.96f,relief=1.1f)
        val frame=PaintedStrokePlanner.plan(source,preferences)
        val view=PaintedScreenView(context)
        view.layout(0,0,120,240)
        view.configure(preferences)
        view.setFrame(frame)
        val reader=ImageReader.newInstance(120,240,PixelFormat.RGBA_8888,2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val hardware=HardwareRenderer()
        val node=RenderNode("HALO native brush painter")
        try {
            hardware.isOpaque=false
            hardware.setSurface(reader.surface)
            node.setPosition(0,0,120,240)
            val canvas=node.beginRecording(120,240)
            view.draw(canvas)
            node.endRecording()
            hardware.setContentRoot(node)
            hardware.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            var captured=reader.acquireLatestImage()
            val deadline=SystemClock.uptimeMillis()+2500L
            while(captured==null && SystemClock.uptimeMillis()<deadline) {
                SystemClock.sleep(10L)
                captured=reader.acquireLatestImage()
            }
            return checkNotNull(captured).use { img ->
                checkNotNull(img.hardwareBuffer).use { buffer ->
                    val bitmap=checkNotNull(Bitmap.wrapHardwareBuffer(
                        buffer,ColorSpace.get(ColorSpace.Named.SRGB)))
                    try { checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888,false)) }
                    finally { bitmap.recycle() }
                }
            }
        } finally {
            hardware.destroy()
            node.discardDisplayList()
            reader.close()
            frame.dispose()
        }
    }
}
