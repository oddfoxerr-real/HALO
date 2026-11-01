package dev.oddfoxerr.halo

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** WorkManager persists periodic checks across a reboot or process shutdown. */
object HaloUpdater {
    private const val PREF = "halo_auto_updater"
    private const val CHANNEL = "halo_verified_updates"
    private const val NOTICE = 939
    private const val SIX_HOURS = 21600000L

    internal fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    internal fun apk(context: Context) =
        File(context.cacheDir, "halo-updates/verified-halo.apk")

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val worker = WorkManager.getInstance(context)
        worker.enqueueUniquePeriodicWork("halo-periodic-update",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<HaloUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints).build())
        if (System.currentTimeMillis() - prefs(context).getLong("last_check", 0) > SIX_HOURS) {
            worker.enqueueUniqueWork("halo-startup-update",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<HaloUpdateWorker>()
                    .setConstraints(constraints).build())
        }
    }

    fun readyVersion(context: Context): String? {
        val version = prefs(context).getString("ready", null) ?: return null
        return version.takeIf { apk(context).isFile && apk(context).length() > 100000L }
    }

    internal fun status(context: Context, description: String) {
        prefs(context).edit().putString("status", description).apply()
    }

    fun promptOnAppOpen(activity: Activity) {
        if (readyVersion(activity) == null) return
        val now = System.currentTimeMillis()
        val store = prefs(activity)
        if (now - store.getLong("prompted", 0) < 43200000L) return
        store.edit().putLong("prompted", now).apply()
        activity.startActivity(Intent(activity, HaloUpdateInstallActivity::class.java))
    }

    internal fun ready(context: Context, version: String) {
        prefs(context).edit().putString("ready", version).putString("status",
            "Downloaded and verified version " + version).apply()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            status(context, "Update downloaded: open HALO to approve installation")
            return
        }
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "HALO automatic updates", NotificationManager.IMPORTANCE_DEFAULT))
        val action = PendingIntent.getActivity(context, NOTICE,
            Intent(context, HaloUpdateInstallActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(NOTICE, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("HALO " + version + " is ready")
            .setContentText("Verified in background · tap to approve Android installation")
            .setContentIntent(action).setAutoCancel(true).build())
    }

    internal fun clear(context: Context) {
        prefs(context).edit().remove("ready").apply()
        apk(context).delete()
        context.getSystemService(NotificationManager::class.java).cancel(NOTICE)
    }

    /** Only install higher versions of this exact app signed by its existing certificate. */
    internal fun compatible(context: Context, file: File): Boolean {
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val archive = manager.getPackageArchiveInfo(
            file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        @Suppress("DEPRECATION")
        val installed = manager.getPackageInfo(
            context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.packageName != context.packageName ||
            archive.longVersionCode <= installed.longVersionCode) return false
        val newer = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val current = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        return !newer.isNullOrEmpty() && newer == current
    }
}

/** Checks official GitHub Releases and stages a verified APK without opening any UI. */
class HaloUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val prefs = HaloUpdater.prefs(app)
        try {
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            @Suppress("DEPRECATION")
            val installed = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
            val release = HaloReleaseSelector.choose(
                textFrom("https://api.github.com/repos/oddfoxerr-real/HALO/releases?per_page=10"),
                installed) ?: run {
                    HaloUpdater.status(app, "HALO is up to date")
                    return@withContext Result.success()
                }
            val number = release.tag.removePrefix("v")
            if (prefs.getString("blocked", null) == number) return@withContext Result.success()
            if (prefs.getString("ready", null) == number &&
                HaloUpdater.readyVersion(app) != null) return@withContext Result.success()
            val checksum = HaloReleaseSelector.parseChecksum(
                textFrom(release.checksumUrl), release.fileName)
                ?: throw IllegalStateException("Missing checksum manifest entry")
            val destination = HaloUpdater.apk(app)
            val folder = requireNotNull(destination.parentFile)
            check(folder.isDirectory || folder.mkdirs())
            val temp = File(folder, "incoming.apk")
            temp.delete()
            val digest = try { saveApk(release.apkUrl, temp) }
                catch (failure: Exception) { temp.delete(); throw failure }
            if (!digest.equals(checksum, ignoreCase = true)) {
                temp.delete()
                throw IllegalStateException("SHA-256 mismatch")
            }
            if (!HaloUpdater.compatible(app, temp)) {
                temp.delete()
                prefs.edit().putString("blocked", number).apply()
                HaloUpdater.status(app, "Release " + number +
                    " uses a different signing certificate: configure stable release signing")
                return@withContext Result.success()
            }
            if (destination.exists() && !destination.delete())
                throw IllegalStateException("Cannot replace cached APK")
            if (!temp.renameTo(destination))
                throw IllegalStateException("Cannot stage verified APK")
            HaloUpdater.ready(app, number)
            Result.success()
        } catch (failure: Exception) {
            HaloUpdater.status(app, "Updater error: " + failure.javaClass.simpleName)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    private fun connection(link: String): HttpURLConnection {
        var target = link
        repeat(5) {
            val address = URI(target)
            val host = address.host?.lowercase()
            if (address.scheme != "https" ||
                host !in setOf("github.com", "api.github.com",
                    "release-assets.githubusercontent.com", "objects.githubusercontent.com") ||
                address.userInfo != null) throw IllegalStateException("Untrusted download host")
            val http = URL(target).openConnection() as HttpURLConnection
            http.instanceFollowRedirects = false
            http.connectTimeout = 12000
            http.readTimeout = 20000
            http.setRequestProperty("User-Agent", "HALO-Verified-Updater")
            val code = http.responseCode
            if (code in 200..299) return http
            if (code in listOf(301,302,303,307,308)) {
                val next = http.getHeaderField("Location")
                    ?: throw IllegalStateException("Missing redirect location")
                http.disconnect()
                target = address.resolve(next).toString()
            } else {
                http.disconnect()
                throw IllegalStateException("GitHub HTTP " + code)
            }
        }
        throw IllegalStateException("Too many GitHub redirects")
    }

    private fun textFrom(url: String): String {
        val remote = connection(url)
        return try {
            val output = ByteArrayOutputStream()
            remote.inputStream.use { input ->
                val data = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(data)
                    if (count < 0) break
                    total += count
                    if (total > 1000000) throw IllegalStateException("Response too large")
                    output.write(data, 0, count)
                }
            }
            output.toString("UTF-8")
        } finally { remote.disconnect() }
    }

    private fun saveApk(url: String, file: File): String {
        val remote = connection(url)
        val hash = MessageDigest.getInstance("SHA-256")
        try {
            var size = 0L
            remote.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val data = ByteArray(65536)
                    while (true) {
                        if (isStopped) throw IllegalStateException("Updater cancelled")
                        val count = input.read(data)
                        if (count < 0) break
                        size += count
                        if (size > 160L * 1024L * 1024L)
                            throw IllegalStateException("APK too large")
                        output.write(data, 0, count)
                        hash.update(data, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (size < 100000L) throw IllegalStateException("Incomplete APK")
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        } finally { remote.disconnect() }
    }
}

/** A user tap or foreground-app opening hands off to Android's installer. */
class HaloUpdateInstallActivity : Activity() {
    private var requestingPermission = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installIfPermitted()
    }
    override fun onResume() {
        super.onResume()
        if (requestingPermission && packageManager.canRequestPackageInstalls()) {
            requestingPermission = false
            installIfPermitted()
        }
    }
    private fun installIfPermitted() {
        if (HaloUpdater.readyVersion(this) == null) { finish(); return }
        val packageFile = HaloUpdater.apk(this)
        if (!HaloUpdater.compatible(this, packageFile)) {
            HaloUpdater.clear(this)
            finish()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            if (requestingPermission) return
            requestingPermission = true
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(
            this, "$packageName.updates", packageFile)
        startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }
}
