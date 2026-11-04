package dev.oddfoxerr.halo

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock

/**
 * Opt-in foreground-app detection. Only transient in-memory state is kept.
 * No app usage log is stored or transmitted.
 */
internal class ForegroundMusicFocus(private val context: Context) {
    companion object {
        fun hasAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            return ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }
    }

    val homePackages: Set<String> by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pm = context.packageManager
        val packages = pm.queryIntentActivities(intent, 0).mapNotNull {
            it.activityInfo?.packageName
        }.toMutableSet()
        pm.resolveActivity(intent, 0)?.activityInfo?.packageName?.let {
            packages.add(it)
        }
        packages.add("com.sec.android.app.launcher")
        packages
    }

    private val usage by lazy { context.getSystemService(UsageStatsManager::class.java) }
    private var lastPoll = 0L
    private var recent: String? = null

    fun currentPackage(elapsed: Long = SystemClock.elapsedRealtime()): String? {
        if (elapsed - lastPoll in 0L until 320L) return recent
        lastPoll = elapsed
        if (!hasAccess(context)) {
            recent = null
            return null
        }
        val now = System.currentTimeMillis()
        try {
            val events = usage.queryEvents(now - 18_000L, now)
            val entry = UsageEvents.Event()
            var lastTime = Long.MIN_VALUE
            var selected: String? = null
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(entry)
                if (entry.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                val pkg = entry.packageName
                if (pkg.isNullOrBlank() || pkg == "com.android.systemui" ||
                    pkg == "android") continue
                if (entry.timeStamp >= lastTime) {
                    lastTime = entry.timeStamp
                    selected = pkg
                }
            }
            if (selected != null) recent = selected
        } catch (_: RuntimeException) {
            recent = null
        }
        return recent
    }
}

internal object MusicFocusRouting {
    fun revealFor(
        foregroundPackage: String?,
        playingPackage: String?,
        homePackages: Set<String>,
        ownPackage: String
    ): Boolean {
        if (foregroundPackage.isNullOrBlank()) return true
        return foregroundPackage == ownPackage ||
            foregroundPackage == playingPackage ||
            foregroundPackage in homePackages
    }
}
