package dev.oddfoxerr.halo

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import java.util.Calendar

/** Local, event-driven notification listener. Does not read or persist message bodies. */
class HaloNotificationListener : NotificationListenerService() {
    private val tracker = NotificationTracker()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: ProfileStore
    private val quietCheck = object : Runnable {
        override fun run() {
            refreshIndicator()
            scheduleQuietCheck()
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> HaloOverlay.dismiss()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                    refreshIndicator()
                    scheduleQuietCheck()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = ProfileStore(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // These are protected system broadcasts; no general app broadcast permission is needed.
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        main.post {
            val ranking = currentRanking
            val present = try {
                activeNotifications?.filter(::isRelevant)?.map { sbn ->
                    Triple(sbn.key, sbn.packageName, isAlerting(importanceOf(sbn, ranking)))
                }.orEmpty()
            } catch (_: Exception) { emptyList() }
            tracker.restoreEligible(present)
            NotificationActivityState.connected(tracker.size())
            refreshIndicator()
            scheduleQuietCheck()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = onIncoming(sbn, null)

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) =
        onIncoming(sbn, importanceOf(sbn, rankingMap))

    private fun importanceOf(sbn: StatusBarNotification, rankingMap: RankingMap?): Int? {
        if (rankingMap == null) return null
        val ranking = Ranking()
        return if (rankingMap.getRanking(sbn.key, ranking)) ranking.importance else null
    }

    private fun isAlerting(importance: Int?): Boolean =
        NotificationEligibilityPolicy.isAlerting(store.showSilent, importance, NotificationManager.IMPORTANCE_DEFAULT)

    private fun onIncoming(sbn: StatusBarNotification, importance: Int?) {
        main.post {
            if (!isRelevant(sbn)) return@post
            val eligible = isAlerting(importance)
            if (tracker.posted(sbn.key, sbn.packageName, eligible) == null) {
                refreshIndicatorIfIdle()
                return@post
            }
            NotificationActivityState.event(sbn.packageName, EventType.RECEIVED, tracker.size())
            scheduleQuietCheck()
            if (!eligible || !mayShow()) {
                refreshIndicatorIfIdle()
                return@post
            }
            val profile = store.readForPackage(sbn.packageName)
            if (profile.enabled) HaloOverlay.showNotification(this, profile, true)
            else refreshIndicatorIfIdle()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        main.post {
            if (tracker.removed(sbn.key)) {
                NotificationActivityState.event(sbn.packageName, EventType.DISMISSED, tracker.size())
                refreshIndicator()
                scheduleQuietCheck()
            }
        }
    }

    override fun onListenerDisconnected() {
        main.post {
            tracker.clear()
            main.removeCallbacks(quietCheck)
            NotificationActivityState.disconnected()
            HaloOverlay.dismiss()
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) { }
        HaloOverlay.dismiss()
        super.onDestroy()
    }

    private fun isRelevant(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val flags = sbn.notification.flags
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return false
        if ((flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return false
        if (sbn.notification.category == Notification.CATEGORY_SERVICE) return false
        return true
    }

    private fun mayShow(): Boolean {
        if (!store.masterEnabled || store.isQuietNow() || !Settings.canDrawOverlays(this)) return false
        if (store.onlyScreenOn) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!power.isInteractive) return false
        }
        return true
    }

    private fun refreshIndicatorIfIdle() {
        if (!HaloOverlay.isAnimating()) refreshIndicator()
    }

    private fun refreshIndicator() {
        if (!mayShow()) {
            HaloOverlay.dismiss()
            return
        }
        val profile = tracker.newestIndicatorPackages().asSequence()
            .map(store::readForPackage).firstOrNull { it.enabled && it.persistent }
        if (profile == null) HaloOverlay.dismiss() else HaloOverlay.showIndicator(this, profile)
    }

    private fun scheduleQuietCheck() {
        main.removeCallbacks(quietCheck)
        if (!store.quietEnabled || tracker.size() == 0) return
        // At most one callback per minute while quiet hours and active notifications coexist.
        val now = Calendar.getInstance()
        val delay = 60_100L - now.get(Calendar.SECOND) * 1_000L - now.get(Calendar.MILLISECOND)
        main.postDelayed(quietCheck, delay.coerceAtLeast(1_000L))
    }
}
