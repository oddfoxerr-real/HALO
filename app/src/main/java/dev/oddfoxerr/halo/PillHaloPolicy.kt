package dev.oddfoxerr.halo

/** Platform-independent policy for the unlocked-screen media outline. */
object PillHaloPolicy {
    fun shouldShow(enabled: Boolean, allowed: Boolean, playing: Boolean, quiet: Boolean): Boolean =
        enabled && allowed && playing && !quiet

    fun mediaColor(packageName: String, fallback: Long): Long = when (packageName) {
        "com.spotify.music" -> 0xFF1ED760L
        "com.google.android.apps.youtube.music", "com.google.android.youtube" -> 0xFFFF5264L
        "com.soundcloud.android" -> 0xFFFF8A36L
        "com.discord" -> 0xFF8790FBL
        else -> fallback
    }
}
