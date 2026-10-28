package dev.oddfoxerr.halo

import android.content.Context
import java.util.Calendar

/** Local settings only. No message text, contacts, or network requests. */
class ProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("halo_profiles_v2", Context.MODE_PRIVATE)

    fun read(app: CharacterApp): CharacterProfile {
        val key = app.name
        return CharacterProfile(
            app = app,
            color = prefs.getLong("${key}_color", app.initialColor),
            side = if (prefs.getString("${key}_side", "RIGHT") == "LEFT") EdgeSide.LEFT else EdgeSide.RIGHT,
            durationMs = prefs.getLong("${key}_duration", 4200L).coerceIn(1800, 10000),
            scale = prefs.getFloat("${key}_scale", 1f).coerceIn(.60f, 1.60f),
            glow = prefs.getFloat("${key}_glow", .65f).coerceIn(0f, 1f),
            anchorY = prefs.getFloat("${key}_anchor_y", .50f).coerceIn(.20f, .85f),
            edgeInsetDp = prefs.getFloat("${key}_edge_inset", 0f).coerceIn(0f, 32f),
            enabled = prefs.getBoolean("${key}_enabled", true),
            persistent = prefs.getBoolean("${key}_persistent", true)
        )
    }
    fun write(profile: CharacterProfile) {
        val key = profile.app.name
        prefs.edit()
            .putLong("${key}_color", profile.color)
            .putString("${key}_side", profile.side.name)
            .putLong("${key}_duration", profile.durationMs.coerceIn(1800L, 10000L))
            .putFloat("${key}_scale", profile.scale.coerceIn(.60f, 1.60f))
            .putFloat("${key}_glow", profile.glow.coerceIn(0f, 1f))
            .putFloat("${key}_anchor_y", profile.anchorY.coerceIn(.20f, .85f))
            .putFloat("${key}_edge_inset", profile.edgeInsetDp.coerceIn(0f, 32f))
            .putBoolean("${key}_enabled", profile.enabled)
            .putBoolean("${key}_persistent", profile.persistent)
            .apply()
    }

    fun readRule(packageName: String): ApplicationRule {
        val name = prefs.getString("rule.$packageName.character", "")
        val character = CharacterApp.entries.firstOrNull { it.name == name }
            ?: CharacterApp.fromPackage(packageName)
        val color = prefs.getLong("rule.$packageName.color", -1L)
        return ApplicationRule(
            packageName = packageName,
            character = character,
            enabled = prefs.getBoolean("rule.$packageName.enabled", true),
            colorOverride = color.takeIf { it >= 0L }
        )
    }
    fun writeRule(rule: ApplicationRule) {
        val known = configuredPackages.toMutableSet().apply { add(rule.packageName) }
        prefs.edit().putStringSet("configured_packages", known)
            .putString("rule.${rule.packageName}.character", rule.character.name)
            .putBoolean("rule.${rule.packageName}.enabled", rule.enabled)
            .putLong("rule.${rule.packageName}.color", rule.colorOverride ?: -1L)
            .apply()
    }
    fun clearRule(packageName: String) {
        val known = configuredPackages.toMutableSet().apply { remove(packageName) }
        prefs.edit().putStringSet("configured_packages", known)
            .remove("rule.$packageName.character")
            .remove("rule.$packageName.enabled")
            .remove("rule.$packageName.color")
            .apply()
    }
    val configuredPackages: Set<String> get() = prefs.getStringSet("configured_packages", emptySet())?.toSet().orEmpty()

    fun readForPackage(packageName: String): CharacterProfile {
        val rule = readRule(packageName)
        val base = read(rule.character)
        return base.copy(color = rule.colorOverride ?: base.color, enabled = rule.enabled && base.enabled)
    }

    var masterEnabled: Boolean
        get() = prefs.getBoolean("master_enabled", true)
        set(value) { prefs.edit().putBoolean("master_enabled", value).apply() }
    var quietEnabled: Boolean
        get() = prefs.getBoolean("quiet_enabled", false)
        set(value) { prefs.edit().putBoolean("quiet_enabled", value).apply() }
    var quietStart: Int
        get() = prefs.getInt("quiet_start", 22 * 60)
        set(value) { prefs.edit().putInt("quiet_start", value.coerceIn(0, 1439)).apply() }
    var quietEnd: Int
        get() = prefs.getInt("quiet_end", 7 * 60)
        set(value) { prefs.edit().putInt("quiet_end", value.coerceIn(0, 1439)).apply() }
    var showSilent: Boolean
        get() = prefs.getBoolean("show_silent", false)
        set(value) { prefs.edit().putBoolean("show_silent", value).apply() }
    var onlyScreenOn: Boolean
        get() = prefs.getBoolean("screen_on_only", true)
        set(value) { prefs.edit().putBoolean("screen_on_only", value).apply() }
    var dynamicColors: Boolean
        get() = prefs.getBoolean("dynamic_colors", false)
        set(value) { prefs.edit().putBoolean("dynamic_colors", value).apply() }

    fun isQuietNow(): Boolean {
        val c = Calendar.getInstance()
        val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return QuietHoursPolicy.isQuiet(quietEnabled, quietStart, quietEnd, minute)
    }
}
