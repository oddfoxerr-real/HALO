package dev.oddfoxerr.halo

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * Installs a verified same-signature HALO APK with Android PackageInstaller.
 * Never bypasses platform authorization or places a prompt over another app.
 */
internal object HaloUpdateInstaller {
    private const val ACTION_STATUS = "dev.oddfoxerr.halo.UPDATE_INSTALL_STATUS"
    private const val EXTRA_INTERACTIVE = "halo_install_interactive"
    private const val EXTRA_SESSION = "halo_install_session"
    private var foregroundActivity: WeakReference<Activity>? = null

    fun foreground(activity: Activity) {
        foregroundActivity = WeakReference(activity)
    }

    fun background(activity: Activity) {
        if (foregroundActivity?.get() === activity) foregroundActivity = null
    }

    /**
     * Returns false when the one-time source permission has not been granted.
     * The caller must not record the update as attempted in that case.
     */
    fun submit(context: Context, apk: File, interactive: Boolean = false): Boolean {
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            HaloUpdates.note(app, "permission",
                "Allow installs from HALO once. The verified update is already downloaded.")
            return false
        }
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("halo-verified-update.apk", 0, apk.length()).use { stream ->
                    apk.inputStream().buffered().use { input -> input.copyTo(stream, 64 * 1024) }
                    session.fsync(stream)
                }
                val callback = Intent(app, HaloUpdateInstallReceiver::class.java)
                    .setAction(ACTION_STATUS)
                    .putExtra(EXTRA_SESSION, sessionId)
                    .putExtra(EXTRA_INTERACTIVE, interactive)
                val result = PendingIntent.getBroadcast(
                    app, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                HaloUpdates.note(app, "installing",
                    "Android is preparing to install the verified HALO update.")
                session.commit(result.intentSender)
            }
            return true
        } catch (e: Exception) {
            if (sessionId >= 0) {
                try { installer.abandonSession(sessionId) } catch (_: Exception) {}
            }
            throw IOException("Android PackageInstaller rejected the update: " +
                e.message.orEmpty(), e)
        }
    }

    internal fun handleResult(context: Context, intent: Intent) {
        val app = context.applicationContext
        val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val details = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val version = HaloUpdates.prefs(app).getString("version", "").orEmpty()
        when (code) {
            PackageInstaller.STATUS_SUCCESS -> {
                HaloUpdateAlert.cancel(app)
                HaloUpdates.save(app, "current", "HALO updated successfully.")
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val approval = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                val activity = foregroundActivity?.get()
                if (approval != null && activity != null &&
                    !activity.isFinishing && !activity.isDestroyed) {
                    // The user is currently in HALO; show Android's genuine
                    // installation confirmation, not a custom fake permission.
                    HaloUpdates.note(app, "approval", "Confirm the update in Android's installer.")
                    try {
                        activity.startActivity(approval)
                    } catch (_: Exception) {
                        HaloUpdates.note(app, "ready",
                            "Android confirmation is required. Open HALO to install.")
                    }
                } else if (approval != null && HaloUpdateAlert.show(app, approval, version)) {
                    // Tapping the notification opens Android's *own* consent
                    // flow; no background activity launch restriction bypass.
                    HaloUpdates.note(app, "approval",
                        "Update ready. Tap the HALO update notification to approve installation.")
                } else {
                    HaloUpdates.note(app, "ready",
                        "Android requires confirmation. Open HALO to install your downloaded update.")
                }
            }
            else -> {
                HaloUpdateAlert.cancel(app)
                HaloUpdates.note(app, "ready",
                    "Android could not finish the update: " +
                        details.take(150).ifBlank { "installation was declined or unavailable" })
            }
        }
    }
}

/** Optional system notification when PackageInstaller needs approval in background. */
internal object HaloUpdateAlert {
    private const val CHANNEL_ID = "halo_verified_update"
    private const val NOTIFICATION_ID = 8102

    fun show(context: Context, confirmation: Intent, version: String): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return false
        return try {
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "HALO app updates", NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Android installation approval for downloaded HALO updates"
                }
            )
            val action = PendingIntent.getActivity(
                app, NOTIFICATION_ID,
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("HALO " + version + " is ready")
                .setContentText("Tap to approve the verified Android update.")
                .setContentIntent(action)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun cancel(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}

class HaloUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HaloUpdateInstaller.handleResult(context, intent)
    }
}
