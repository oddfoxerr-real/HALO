package dev.oddfoxerr.halo

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * An independent painter thread: ImageReader's capture callback never waits
 * for CPU brush planning or SurfaceHolder.lockHardwareCanvas().
 *
 * Only the most recent unpainted bitmap is retained. Old frames are recycled
 * immediately rather than allowing the rendering queue to grow indefinitely.
 */
internal class LatestPaintDispatcher(
    private val surface: () -> LivePaintSurface?,
    private val preferences: () -> PaintedScreenPreferences
) {
    private val thread=HandlerThread("HALO-paint-render").apply{start()}
    private val handler=Handler(thread.looper)
    private val latest=AtomicReference<Bitmap?>(null)
    private val scheduled=AtomicBoolean(false)
    @Volatile private var closed=false

    fun offer(bitmap:Bitmap) {
        if(closed) {
            bitmap.recycle()
            return
        }
        val replaced=latest.getAndSet(bitmap)
        if(replaced!=null) {
            replaced.recycle()
            PaintCaptureMetrics.onDropped()
        }
        schedule()
    }

    private fun schedule() {
        if(!closed && scheduled.compareAndSet(false,true)) {
            handler.post { consume() }
        }
    }

    private fun consume() {
        try {
            // Single processing step: newer capture frames supersede the
            // previous ones. Posting another step yields to clears and stop.
            val input=latest.getAndSet(null) ?: return
            if(closed) {input.recycle();return}
            val result=try {
                PaintedStrokePlanner.plan(input,preferences())
            } catch (failure:Exception) {
                if(!input.isRecycled) input.recycle()
                Log.e("HALO","Paint worker cannot plan the live frame",failure)
                PaintCaptureMetrics.onPainted(false)
                return
            }
            val display=surface()
            val presented=if(display==null) {
                result.dispose()
                false
            } else display.present(result,preferences())
            PaintCaptureMetrics.onPainted(presented)
        } finally {
            scheduled.set(false)
            if(latest.get()!=null && !closed) schedule()
        }
    }

    fun clear() {
        latest.getAndSet(null)?.recycle()
        if(!closed) handler.post { surface()?.clearSurface() }
    }

    /** Drain the old secure surface without stopping the render worker. */
    fun retireSurface(releaseSurface:LivePaintSurface) {
        handler.post {
            releaseSurface.clearSurface()
            releaseSurface.release()
        }
    }

    fun close(releaseSurface:LivePaintSurface?) {
        if(closed) return
        closed=true
        latest.getAndSet(null)?.recycle()
        handler.post {
            releaseSurface?.clearSurface()
            releaseSurface?.release()
        }
        thread.quitSafely()
    }
}
