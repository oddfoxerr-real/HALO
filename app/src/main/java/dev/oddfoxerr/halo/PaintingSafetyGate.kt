package dev.oddfoxerr.halo

/**
 * Safety invariant: an untrusted full-screen Android overlay must never
 * replace the phone display to imitate a global shader. Samsung/Android may
 * block underlying app touches and darken the screen. Painting is allowed
 * only inside HALO's own activity using user-selected images.
 */
internal object PaintingSafetyGate {
    const val FULLSCREEN_PAINTING_ALLOWED = false

    fun rejectProjectionStart(action: String?, painted: Boolean): Boolean =
        !FULLSCREEN_PAINTING_ALLOWED &&
            action == VideoAmbilightService.ACTION_START && painted
}
