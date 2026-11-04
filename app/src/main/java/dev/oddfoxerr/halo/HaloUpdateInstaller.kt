package dev.oddfoxerr.halo

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * Self-update via the platform PackageInstaller, never a privileged shell or
 * hidden UI automation. Android decides whether silent install is permitted.
 */
internal object HaloUpdateInstaller {
    private const val ACTION_STATUS = "dev.oddfoxerr.halo.UPDATE_INSTALL_STATUS"
    private const val EXTRA_INTERACTIVE = "halo_install_interactive"
    private var foreground: WeakReference<Activity>? = null

    fun foreground(activity: Activity) {
        foreground = WeakReference(activity)
    }

    fun background(activity: Activity) {
        if (foreground?.get() === activity) foreground = null
    }

    fun submit(context: Context, apk: File, interactive: Boolean = false) {
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            HaloUpdates.note(app, "ready",
                "Update downloaded. Android requires 'Install unknown apps' access " +
                "for HALO before it can install updates.")
            return
        }
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("halo-update.apk", 0, apk.length()).use { output ->
                    apk.inputStream().buffered().use { input -> input.copyTo(output, 64 * 1024) }
                    session.fsync(output)
                }
                val callback = Intent(app, HaloUpdateInstallReceiver::class.java)
                    .setAction(ACTION_STATUS)
                    .putExtra(EXTRA_INTERACTIVE, interactive)
                val pending = PendingIntent.getBroadcast(
                    app, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                HaloUpdates.note(app, "installing", "Android is installing the verified update.")
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            if (sessionId >= 0) try { installer.abandonSession(sessionId) } catch (_: Exception) {}
            throw IOException("PackageInstaller rejected update: " + e.message.orEmpty(), e)
        }
    }

    internal fun handleResult(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                HaloUpdates.save(context, "current", "HALO updated successfully.")
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val interactive = intent.getBooleanExtra(EXTRA_INTERACTIVE, false)
                @Suppress("DEPRECATION")
                val confirmation = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                val activity = foreground?.get()
                if (interactive && activity != null && confirmation != null) {
                    HaloUpdates.note(context, "approval", "Android installation confirmation opened.")
                    try {
                        activity.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        HaloUpdates.note(context, "ready",
                            "Update ready. Android must approve the installation: " +
                            e.message.orEmpty().take(100))
                    }
                } else {
                    // Never force a permission dialog over some other app.
                    // The verified APK remains ready for a user-initiated install.
                    HaloUpdates.note(context, "ready",
                        "Update downloaded. Android needs installation approval.")
                }
            }
            else -> {
                HaloUpdates.note(context, "ready",
                    "Android could not install this update: " + message.take(150))
            }
        }
    }
}

class HaloUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HaloUpdateInstaller.handleResult(context, intent)
    }
}
