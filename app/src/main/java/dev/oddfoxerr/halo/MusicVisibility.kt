package dev.oddfoxerr.halo

/** Gate evaluated independently of capture callbacks, including during missing/blocked audio. */
object MusicVisibility {
    fun reason(interactive: Boolean, unlocked: Boolean, quiet: Boolean, notification: Boolean,
               sessionAllowed: Boolean, freshSignal: Boolean, audible: Boolean): String? = when {
        !interactive || !unlocked -> "Paused while screen is off or locked"
        quiet -> "Paused for quiet hours"
        notification -> "Giving this notification the edge"
        !sessionAllowed -> "Waiting for a selected music app"
        !freshSignal -> "Waiting for audio data · this player may restrict visualization"
        !audible -> "Waiting for music · silent audio"
        else -> null
    }
}
