package dev.oddfoxerr.halo

/** Pure display policy for floating effects; never draws over a locked or sleeping screen. */
object ChainsawOverlayPolicy {
    fun mayRender(interactive: Boolean, keyguardLocked: Boolean): Boolean =
        interactive && !keyguardLocked
}
