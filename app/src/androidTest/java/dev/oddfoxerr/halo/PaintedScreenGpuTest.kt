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
    @Test fun systemWidePainterIsRejectedBeforeAnyOverlayOrProjectionStarts() {
        assertTrue(
            "Legacy opaque full-screen painting must never start",
            PaintingSafetyGate.rejectProjectionStart(
                VideoAmbilightService.ACTION_START, true, false)
        )
        assertTrue(
            "Explicit safe TextureView session must be permitted",
            !PaintingSafetyGate.rejectProjectionStart(
                VideoAmbilightService.ACTION_START, true, true)
        )
        assertTrue(PaintingSafetyGate.isTouchThroughOpacity(
            PaintingSafetyGate.MAX_LIVE_OVERLAY_ALPHA))
        assertTrue(!PaintingSafetyGate.isTouchThroughOpacity(.9f))
        assertTrue(
            "Other capture modes must remain available",
            !PaintingSafetyGate.rejectProjectionStart(
                VideoAmbilightService.ACTION_START, false)
        )
        assertTrue(
            "Refresh must not unexpectedly be blocked",
            !PaintingSafetyGate.rejectProjectionStart(
                VideoAmbilightService.ACTION_REFRESH, true)
        )
    }

    @Test fun livePainterIsTransparentAndDoesNotCopyAnOpaqueScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val layer = LivePaintSurface(context)
        assertTrue("Live overlay must be a normal transparent View",
            layer is android.view.View)
        assertTrue("Live overlay must not allocate a TextureView buffer",
            layer !is android.view.TextureView)
        assertTrue("Live overlay must not capture input", !layer.isClickable)
        assertTrue("Live overlay must not take keyboard focus", !layer.isFocusable)
        assertTrue("Live layer must not be opaque", !layer.isOpaque)
        val photo = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
        photo.eraseColor(Color.rgb(195, 167, 141))
        val settings = PaintedScreenPreferences()
        val planned = PaintedStrokePlanner.plan(photo, settings)
        val painting = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
        val painter = BrushCanvasPainter()
        try {
            painter.draw(
                android.graphics.Canvas(painting),
                planned, settings, 120, 240,
                includeBackground = false
            )
            var transparent = 0
            var painted = 0
            for (y in 0 until 240 step 5) for (x in 0 until 120 step 5) {
                val alpha = Color.alpha(painting.getPixel(x, y))
                if (alpha == 0) transparent++
                if (alpha > 0) painted++
            }
            assertTrue("No visible brush marks", painted > 2)
            // Multiple translucent bristles may legitimately overlap into an
            // opaque *individual pixel*. The whole overlay window is
            // independently capped at 0.48 opacity. What must never return is
            // the old full-screen opaque screenshot backdrop.
            assertTrue("Backplate is opaque and can darken the phone", transparent > 5)
        } finally {
            planned.dispose()
            painter.release()
            painting.recycle()
            layer.release()
        }
    }

    @Test fun transparentViewNeverPaintsAnOpaqueBackplate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val surface = LivePaintSurface(context)
        surface.layout(0, 0, 120, 240)
        val output = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
        try {
            output.eraseColor(Color.TRANSPARENT)
            surface.draw(android.graphics.Canvas(output))
            assertEquals("Empty painter must reveal underlying content",
                0, Color.alpha(output.getPixel(60, 120)))
        } finally {
            surface.release()
            output.recycle()
        }
    }

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

    @Test fun blockedPainterAutomaticallyRetriesWhenCapturableAppReturns() {
        val controller=PaintedCaptureHealth()
        assertEquals(PaintedCaptureAction.PRESENT,controller.observe(true,false,1000L))
        for (i in 1..7) controller.observe(false,true,1000L+i*100L)
        assertEquals(PaintedCaptureAction.DISABLE_OVERLAY,
            controller.observe(false,true,1800L))
        assertTrue(controller.overlayRejected)
        for (i in 1..5) {
            assertEquals(PaintedCaptureAction.CLEAR,
                controller.observe(true,false,2500L+i*50L))
        }
        assertTrue("Must respect cooldown before reattaching",controller.overlayRejected)
        assertEquals(PaintedCaptureAction.PRESENT,
            controller.observe(true,false,10000L))
        assertTrue("Recovery did not clear the rejection latch",!controller.overlayRejected)
    }

    @Test fun nativeHardwarePainterRendersMultipleDistinctFrames() {
        val painter = BrushCanvasPainter()
        val first = Bitmap.createBitmap(120,240,Bitmap.Config.ARGB_8888)
        first.eraseColor(Color.rgb(210,45,36))
        val second = Bitmap.createBitmap(120,240,Bitmap.Config.ARGB_8888)
        second.eraseColor(Color.rgb(20,145,215))
        val settings = PaintedScreenPreferences(strength=1f)
        val a = PaintedStrokePlanner.plan(first,settings)
        val b = PaintedStrokePlanner.plan(second,settings)
        val front = Bitmap.createBitmap(120,240,Bitmap.Config.ARGB_8888)
        val back = Bitmap.createBitmap(120,240,Bitmap.Config.ARGB_8888)
        try {
            painter.draw(android.graphics.Canvas(front),a,settings,120,240)
            painter.draw(android.graphics.Canvas(back),b,settings,120,240)
            val colourA = front.getPixel(60,120)
            val colourB = back.getPixel(60,120)
            assertEquals(255,Color.alpha(colourA))
            assertEquals(255,Color.alpha(colourB))
            val difference = kotlin.math.abs(Color.red(colourA)-Color.red(colourB)) +
                kotlin.math.abs(Color.green(colourA)-Color.green(colourB)) +
                kotlin.math.abs(Color.blue(colourA)-Color.blue(colourB))
            assertTrue("Painter froze after initial frame; output did not change",difference>180)
        } finally {
            a.dispose()
            b.dispose()
            front.recycle()
            back.recycle()
            painter.release()
        }
    }

    @Test fun brushPassesCoverBothTopAndBottomWithoutExhaustingFrameBudget() {
        val original = Bitmap.createBitmap(360,780,Bitmap.Config.ARGB_8888)
        original.eraseColor(Color.rgb(125,120,75))
        val frame = PaintedStrokePlanner.plan(original,PaintedScreenPreferences())
        try {
            assertTrue("Brush plan missing",frame.strokes.size>400)
            assertTrue("Too many UI brush stamps",frame.strokes.size<=1200)
            assertTrue("No strokes in upper region",frame.strokes.any{ it.y<160f })
            assertTrue("No strokes near lower edge",frame.strokes.any{ it.y>620f })
            assertTrue("Only coarse strokes planned",
                frame.strokes.map{it.thickness.toInt()}.distinct().size>12)
        } finally { frame.dispose() }
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
