package dev.oddfoxerr.halo

import android.os.SystemClock

/**
 * One black capture does not end a MediaProjection session.
 * A permanently rejected overlay used to disable painting even after
 * navigating back to capturable apps. This controller now attempts a
 * limited, delayed recovery only after a run of non-black underlying frames.
 */
internal enum class PaintedCaptureAction {
    PRESENT, KEEP, CLEAR, DISABLE_OVERLAY
}

internal class PaintedCaptureHealth {
    private var invalidFrames = 0
    private var recoveredFrames = 0
    private var rejections = 0
    private var retryNotBefore = 0L
    @Volatile var overlayRejected: Boolean = false
        private set

    fun reset() {
        invalidFrames = 0
        recoveredFrames = 0
        rejections = 0
        retryNotBefore = 0L
        overlayRejected = false
    }

    fun observe(
        isValid: Boolean,
        overlayAttached: Boolean,
        now: Long = SystemClock.uptimeMillis()
    ): PaintedCaptureAction {
        if (overlayRejected) {
            if (isValid && !overlayAttached) recoveredFrames++
            else recoveredFrames = 0
            // Capture is healthy again ONLY with the insecure parent window
            // removed. Reattach gradually; an unusable secure surface never
            // triggers a nonstop flash/restart cycle.
            if (recoveredFrames >= 5 && now >= retryNotBefore) {
                overlayRejected = false
                recoveredFrames = 0
                invalidFrames = 0
                return PaintedCaptureAction.PRESENT
            }
            return PaintedCaptureAction.CLEAR
        }
        if (isValid) {
            invalidFrames = 0
            return PaintedCaptureAction.PRESENT
        }
        invalidFrames++
        if (overlayAttached && invalidFrames >= 8) {
            overlayRejected = true
            recoveredFrames = 0
            rejections++
            val shift = (rejections - 1).coerceIn(0, 4)
            retryNotBefore = now + (8000L shl shift)
            return PaintedCaptureAction.DISABLE_OVERLAY
        }
        return if (invalidFrames >= 2) PaintedCaptureAction.CLEAR
        else PaintedCaptureAction.KEEP
    }
}
