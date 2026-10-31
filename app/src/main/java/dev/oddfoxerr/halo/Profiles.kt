package dev.oddfoxerr.halo

/** Pure Kotlin profile and notification routing logic. No message text is processed or saved. */
enum class CharacterApp(val displayName: String, val packages: Set<String>, val initialColor: Long) {
    DISCORD("Discord", setOf("com.discord"), 0xFF8790FBL),
    WHATSAPP("WhatsApp", setOf("com.whatsapp", "com.whatsapp.w4b"), 0xFF4BE49DL),
    YOUTUBE("YouTube", setOf("com.google.android.youtube"), 0xFFFF5365L),
    GMAIL("Gmail", setOf("com.google.android.gm"), 0xFFF4B36FL),
    OTHER("Other apps", emptySet(), 0xFFAABEFFL);

    companion object {
        fun fromPackage(packageName: String): CharacterApp =
            entries.firstOrNull { packageName in it.packages } ?: OTHER
    }
}

data class CharacterProfile(
    val app: CharacterApp,
    val color: Long = app.initialColor,
    val side: EdgeSide = EdgeSide.RIGHT,
    val motion: CharacterMotion = CharacterMotion.ORIGINAL,
    val durationMs: Long = 4200L,
    val scale: Float = 1f,
    val glow: Float = 0.65f,
    val anchorY: Float = 0.50f,
    val edgeInsetDp: Float = 0f,
    val enabled: Boolean = true,
    val persistent: Boolean = true
)

enum class EdgeSide { LEFT, RIGHT }

/** Entrance motion independent of the character artwork and notification source. */
enum class CharacterMotion(val title: String) {
    ORIGINAL("Original"), SWOOP("Swoop"), ORBIT("Orbit"), SPRING("Spring"),
    GLITCH("Glitch"), FLOAT("Float"), BLAST("Blast")
}

/** Notification keys, not bodies, are held in memory; duplicates must not replay. */
class NotificationTracker {
    private data class ActiveEntry(val packageName: String, val indicatorEligible: Boolean)
    private val active = LinkedHashMap<String, ActiveEntry>()

    fun posted(key: String, packageName: String, indicatorEligible: Boolean = true): CharacterApp? {
        if (active.containsKey(key)) {
            // A notification can change its ranking without a new notification key.
            active[key] = ActiveEntry(packageName, indicatorEligible)
            return null
        }
        val app = CharacterApp.fromPackage(packageName)
        active[key] = ActiveEntry(packageName, indicatorEligible)
        return app
    }

    fun removed(key: String): Boolean = active.remove(key) != null

    fun restore(notifications: List<Pair<String, String>>) {
        active.clear()
        notifications.forEach { (key, packageName) -> active[key] = ActiveEntry(packageName, true) }
    }

    fun restoreEligible(notifications: List<Triple<String, String, Boolean>>) {
        active.clear()
        notifications.forEach { (key, packageName, eligible) -> active[key] = ActiveEntry(packageName, eligible) }
    }

    fun countFor(app: CharacterApp): Int = active.values.count { CharacterApp.fromPackage(it.packageName) == app }
    fun mostRecent(): CharacterApp? = active.values.lastOrNull()?.packageName?.let(CharacterApp::fromPackage)
    fun newestFirst(): List<CharacterApp> = active.values.toList().asReversed().map { CharacterApp.fromPackage(it.packageName) }
    fun newestPackages(): List<String> = active.values.toList().asReversed().map { it.packageName }
    fun newestIndicatorPackages(): List<String> = active.values.toList().asReversed()
        .filter { it.indicatorEligible }.map { it.packageName }
    fun clear() = active.clear()
    fun size(): Int = active.size
}
