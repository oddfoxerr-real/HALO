package dev.oddfoxerr.halo

/** Pure policy for a visually safe Android 12+ pass-through paint window. */
internal object ScreenPaintOverlayPolicy {
    const val MAX_TOUCH_THROUGH_ALPHA = 0.79f
    const val PREFERRED_ALPHA = 0.68f

    fun shouldDisplayFrame(
        hasUsableFrame: Boolean,
        capturedAppVisible: Boolean
    ): Boolean = hasUsableFrame && capturedAppVisible

    fun opacityIsSafe(alpha: Float): Boolean =
        alpha.isFinite() && alpha > 0f && alpha <= MAX_TOUCH_THROUGH_ALPHA
}
