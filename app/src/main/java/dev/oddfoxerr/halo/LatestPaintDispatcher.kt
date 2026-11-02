package dev.oddfoxerr.halo

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A latest-frame mailbox between MediaProjection and the painter.
 *
 * The MediaProjection listener is NOT allowed to wait for brush generation,
 * SurfaceHolder canvas locks, or GPU work. Only this thread does those jobs.
 * If painting is behind, replace the pending image instead of queueing
 * hundreds of obsolete screenshots.
 */
internal class LatestPaintDispatcher(
    private val surface: () -> LivePaintSurface?,
    private val preferences: () -> PaintedScreenPreferences,
    private val onRendered: (frames: Long, fps: Float) -> Unit
) {
    private val renderThread = HandlerThread("HALO-live-paint-render").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val newest = AtomicReference<Bitmap?>(null)
    private val enqueued = AtomicBoolean(false)
    @Volatile private var terminated = false
    private var rendered = 0L
    private var windowFrames = 0
    private var windowStarted = SystemClock.uptimeMillis()

    fun submit(image: Bitmap) {
        if (terminated) {
            image.recycle()
            return
        }
        newest.getAndSet(image)?.let {
            if (!it.isRecycled) it.recycle()
        }
        schedule()
    }

    private fun schedule() {
        if (!terminated && enqueued.compareAndSet(false, true)) {
            if (!renderHandler.post { renderOnce() }) enqueued.set(false)
        }
    }

    private fun renderOnce() {
        try {
            val image = newest.getAndSet(null) ?: return
            if (terminated) {
                image.recycle()
                return
            }
            val settings = preferences()
            val planned = try {
                PaintedStrokePlanner.plan(image, settings)
            } catch (error: Exception) {
                if (!image.isRecycled) image.recycle()
                Log.e("HALO", "Could not create brush strokes for screen frame", error)
                return
            }
            val output = surface()
            if (output == null) {
                planned.dispose()
                return
            }
            if (output.present(planned, settings)) {
                rendered++
                windowFrames++
                val now = SystemClock.uptimeMillis()
                val elapsed = now - windowStarted
                if (elapsed >= 2000L) {
                    onRendered(rendered, windowFrames * 1000f / elapsed.coerceAtLeast(1L))
                    windowStarted = now
                    windowFrames = 0
                }
            }
        } finally {
            enqueued.set(false)
            if (newest.get() != null && !terminated) schedule()
        }
    }

    fun clear() {
        newest.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
        if (!terminated) renderHandler.post { surface()?.clearSurface() }
    }

    fun retireSurface(surface: LivePaintSurface) {
        renderHandler.post {
            surface.clearSurface()
            surface.release()
        }
    }

    fun close(lastSurface: LivePaintSurface?) {
        if (terminated) return
        terminated = true
        newest.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
        renderHandler.post {
            lastSurface?.clearSurface()
            lastSurface?.release()
        }
        renderThread.quitSafely()
    }
}
