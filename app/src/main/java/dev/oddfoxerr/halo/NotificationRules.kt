package dev.oddfoxerr.halo

/** A real installed application can select any of HALO's character animations. */
data class ApplicationRule(
    val packageName: String,
    val character: CharacterApp = CharacterApp.fromPackage(packageName),
    val enabled: Boolean = true,
    val colorOverride: Long? = null
)

/** Window supports overnight intervals, a full-day interval, and disabled schedules. */
object QuietHoursPolicy {
    fun isQuiet(enabled: Boolean, startMinute: Int, endMinute: Int, minuteNow: Int): Boolean {
        if (!enabled) return false
        val start = startMinute.coerceIn(0, 1439)
        val end = endMinute.coerceIn(0, 1439)
        val now = ((minuteNow % 1440) + 1440) % 1440
        return when {
            start == end -> true
            start < end -> now in start until end
            else -> now >= start || now < end
        }
    }
}

data class NotificationUiEvent(
    val packageName: String,
    val timestamp: Long,
    val type: EventType
)
enum class EventType { RECEIVED, DISMISSED }

/** Restricts silent notifications in the animation AND the persistent indicator path. */
object NotificationEligibilityPolicy {
    /** Null importance is an unknown ranking, not evidence of a silent notification. */
    fun isAlerting(showSilent: Boolean, importance: Int?, defaultImportance: Int = 3): Boolean =
        showSilent || importance == null || importance >= defaultImportance
}
