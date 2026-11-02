package dev.oddfoxerr.halo

/**
 * Legacy full-screen painting must never restart from an old notification,
 * stale launch intent, or third-party invocation.
 *
 * Only a new user-consented, safe compositor session may start: it uses
 * TextureView + translucent brush strokes, with no opaque captured backdrop
 * and never creates the secure SurfaceView that obstructed phone controls.
 */
internal object PaintingSafetyGate {
    const val LIVE_TEXTURE_VIEW_ENABLED = true
    const val MAX_LIVE_OVERLAY_ALPHA = .48f

    fun rejectProjectionStart(
        action: String?,
        painted: Boolean,
        safeCompositorRequested: Boolean = false
    ): Boolean = painted && action == VideoAmbilightService.ACTION_START &&
        !(LIVE_TEXTURE_VIEW_ENABLED && safeCompositorRequested)

    fun isTouchThroughOpacity(alpha: Float): Boolean =
        alpha.isFinite() && alpha >= 0f && alpha <= MAX_LIVE_OVERLAY_ALPHA
}
