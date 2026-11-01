package dev.oddfoxerr.halo

/**
 * Tracks the usability of the captured display independently from the
 * MediaProjection service. A black/protected frame is not an instruction to
 * kill the service: it is an instruction to remove the opaque artwork.
 */
internal enum class PaintedCaptureAction {
    PRESENT, KEEP, CLEAR, DISABLE_OVERLAY
}

internal class PaintedCaptureHealth {
    private var invalidFrames = 0
    var overlayRejected: Boolean = false
        private set

    fun reset() {
        invalidFrames = 0
        overlayRejected = false
    }

    fun observe(isValid: Boolean, overlayAttached: Boolean): PaintedCaptureAction {
        if (overlayRejected) return PaintedCaptureAction.CLEAR
        if (isValid) {
            invalidFrames = 0
            return PaintedCaptureAction.PRESENT
        }
        invalidFrames++
        if (overlayAttached && invalidFrames >= 8) {
            overlayRejected = true
            return PaintedCaptureAction.DISABLE_OVERLAY
        }
        return if (invalidFrames >= 2) PaintedCaptureAction.CLEAR
        else PaintedCaptureAction.KEEP
    }
}
