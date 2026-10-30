package dev.oddfoxerr.halo

/** Behavior recreated from public feature descriptions, not copied from another application's code. */
enum class EdgeColorSource(val label: String) {
    MANUAL("My colors"),
    ALBUM_ART("Album artwork")
}

object MediaEdgePolicy {
    fun shouldShow(
        interactive: Boolean,
        unlocked: Boolean,
        playingPackage: String?,
        onlyWhilePlaying: Boolean,
        allowedSources: Set<String>
    ): Boolean {
        if (!interactive || !unlocked) return false
        if (onlyWhilePlaying && playingPackage.isNullOrBlank()) return false
        if (allowedSources.isNotEmpty() && (playingPackage == null || playingPackage !in allowedSources)) return false
        return true
    }

    fun colorSource(name: String?): EdgeColorSource =
        EdgeColorSource.entries.firstOrNull { it.name == name } ?: EdgeColorSource.MANUAL
}
