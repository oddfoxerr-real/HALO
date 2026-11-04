package dev.oddfoxerr.halo

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.BackoffPolicy
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Automatic, permission-prompt-free checks and downloads.
 * The package installer separately decides whether Android requires approval.
 */
internal object HaloUpdates {
    private const val PREFS = "halo_auto_updates_v1"
    private const val PERIODIC = "halo_update_periodic"
    private const val ON_START = "halo_update_on_start"
    data class Snapshot(val state: String, val version: String, val message: String,
                        val checkedAt: Long, val ready: Boolean)
    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun initialize(context: Context) {
        val app = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<HaloUpdateWorker>(
            6, TimeUnit.HOURS, 1, TimeUnit.HOURS
        ).setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request
        )
        if (System.currentTimeMillis() - prefs(app).getLong("checked_at", 0L) > 15 * 60_000L) {
            val initial = OneTimeWorkRequestBuilder<HaloUpdateWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                ON_START, ExistingWorkPolicy.KEEP, initial
            )
        }
    }

    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)
        val ready = preparedFile(context) != null
        val raw = p.getString("state", "checking").orEmpty()
        return Snapshot(if (raw == "ready" && !ready) "checking" else raw,
            p.getString("version", "").orEmpty(),
            p.getString("message", "Checks automatically every six hours.").orEmpty(),
            p.getLong("checked_at", 0L), ready)
    }

    fun preparedFile(context: Context): File? {
        val path = prefs(context).getString("path", null) ?: return null
        val root = File(context.filesDir, "verified_updates").canonicalFile
        val file = File(path).canonicalFile
        return file.takeIf { it.parentFile == root && it.isFile &&
            it.length() in 1L..140_000_000L }
    }

    fun save(context: Context, state: String, message: String, version: String = "",
             file: File? = null, digest: String = "") {
        prefs(context).edit().putString("state", state)
            .putString("message", message).putString("version", version)
            .putString("path", file?.absolutePath).putString("digest", digest).apply()
    }

    fun note(context: Context, state: String, message: String) {
        prefs(context).edit().putString("state", state)
            .putString("message", message).apply()
    }

    fun installPrepared(context: Context) {
        val app = context.applicationContext
        Thread({
            val file = preparedFile(app)
            val expected = prefs(app).getString("digest", "").orEmpty()
            if (file == null || !ReleaseDownload.matchesDigest(file, expected)) {
                save(app, "error", "The verified APK is missing; it will be re-downloaded.")
                initialize(app)
                return@Thread
            }
            try {
                ReleaseDownload.validateArchive(app, file)
                HaloUpdateInstaller.submit(app, file, interactive = true)
            } catch (e: Exception) {
                val message = e.message.orEmpty().take(180)
                if (message.contains("signing key", ignoreCase = true)) {
                    note(app, "incompatible", message)
                } else {
                    note(app, "ready", "Android needs installation approval: " + message)
                }
            }
        }, "HALO-install-update").start()
    }

    fun alreadyAttempted(context: Context, version: String) =
        prefs(context).getString("attempted", null) == version
    fun recordAttempt(context: Context, version: String) {
        prefs(context).edit().putString("attempted", version).apply()
    }
}

internal data class HaloVersion(val major: Int, val minor: Int, val patch: Int) :
    Comparable<HaloVersion> {
    override fun compareTo(other: HaloVersion): Int =
        compareValuesBy(this, other, HaloVersion::major, HaloVersion::minor, HaloVersion::patch)
    companion object {
        fun parse(name: String?): HaloVersion? {
            val parts = name?.removePrefix("v")?.split(".") ?: return null
            if (parts.size != 3) return null
            val ints = parts.map {
                if (it.length !in 1..6 || !it.all(Char::isDigit)) return null
                it.toIntOrNull() ?: return null
            }
            return HaloVersion(ints[0], ints[1], ints[2])
        }
    }
}

internal data class SignedRelease(val version: String, val name: String,
    val downloadUrl: String, val checksumUrl: String, val bytes: Long)

/** Rejects debug builds, wrong packages, unverified downloads and signature changes. */
internal object ReleaseDownload {
    const val RELEASES_URL =
        "https://api.github.com/repos/oddfoxerr-real/HALO/releases?per_page=30"
    private const val REPO_RELEASES =
        "https://github.com/oddfoxerr-real/HALO/releases/download/"

    fun select(json: String, installedName: String): SignedRelease? {
        val installed = HaloVersion.parse(installedName) ?: return null
        var best: SignedRelease? = null
        var top: HaloVersion? = null
        val releases = JSONArray(json)
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false)) continue
            val tag = release.optString("tag_name")
            val version = HaloVersion.parse(tag) ?: continue
            if (version <= installed || (top != null && version <= top)) continue
            val assets = release.optJSONArray("assets") ?: continue
            val apkName = "HALO-" + tag + "-release.apk"
            var apk: JSONObject? = null
            var checksum: JSONObject? = null
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                if (asset.optString("name") == apkName) apk = asset
                if (asset.optString("name") == "SHA256SUMS.txt") checksum = asset
            }
            val binary = apk ?: continue
            val sums = checksum ?: continue
            val prefix = REPO_RELEASES + tag + "/"
            val url = binary.optString("browser_download_url")
            val sumUrl = sums.optString("browser_download_url")
            val length = binary.optLong("size", 0)
            if (url != prefix + apkName || sumUrl != prefix + "SHA256SUMS.txt" ||
                length !in 1L..140_000_000L) continue
            top = version
            best = SignedRelease(tag, apkName, url, sumUrl, length)
        }
        return best
    }

    fun expectedDigest(checksums: String, fileName: String): String? {
        val pattern = Regex("^([A-Fa-f0-9]{64})\\s+\\*?([^/\\\\]+)$")
        return checksums.lineSequence().mapNotNull { line ->
            val match = pattern.matchEntire(line.trim())
            if (match?.groupValues?.get(2) == fileName) match.groupValues[1].lowercase()
            else null
        }.firstOrNull()
    }

    fun matchesDigest(file: File, hash: String): Boolean {
        if (!Regex("^[a-f0-9]{64}$").matches(hash)) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == hash
    }

    fun readText(url: String, limit: Int = 450_000): String {
        val out = java.io.ByteArrayOutputStream()
        open(url).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > limit) throw IOException("Server response too large")
                out.write(buffer, 0, read)
            }
        }
        return out.toString("UTF-8")
    }

    private fun open(url: String): java.io.InputStream {
        if (!url.startsWith("https://")) throw IOException("HTTPS required")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 35_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "HALO-Android-Updater")
        val status = conn.responseCode
        if (status !in 200..299 || conn.url.protocol != "https") {
            conn.disconnect()
            throw IOException("Server HTTP " + status)
        }
        return object : java.io.FilterInputStream(conn.inputStream) {
            override fun close() { try { super.close() } finally { conn.disconnect() } }
        }
    }

    fun download(release: SignedRelease, file: File, hash: String) {
        val temporary = File(file.parentFile, file.name + ".part")
        temporary.delete()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            open(release.downloadUrl).use { input ->
                temporary.outputStream().buffered().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > 140_000_000L) throw IOException("APK too large")
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                    }
                }
            }
            val downloaded = digest.digest().joinToString("") { "%02x".format(it) }
            if (count != release.bytes || downloaded != hash) {
                throw IOException("APK size or SHA-256 mismatch")
            }
            if (file.exists() && !file.delete()) throw IOException("Old update cannot be replaced")
            if (!temporary.renameTo(file)) throw IOException("Cannot save verified APK")
        } finally {
            temporary.delete()
        }
    }

    @Suppress("DEPRECATION")
    fun validateArchive(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val current = if (Build.VERSION.SDK_INT >= 33)
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageInfo(context.packageName, flags)
        val archive = if (Build.VERSION.SDK_INT >= 33)
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageArchiveInfo(apk.absolutePath, flags)
        if (archive == null || archive.packageName != context.packageName) {
            throw IOException("Downloaded file is not HALO")
        }
        if (archive.longVersionCode <= current.longVersionCode) {
            throw IOException("Update is not newer than installed HALO")
        }
        val oldKeys = current.signingInfo?.apkContentsSigners
            ?.map { it.toByteArray().toList() }?.toSet()
        val newKeys = archive.signingInfo?.apkContentsSigners
            ?.map { it.toByteArray().toList() }?.toSet()
        if (oldKeys.isNullOrEmpty() || oldKeys != newKeys) {
            throw IOException("Older HALO used a different signing key. " +
                "It needs one fresh installation before automatic updates can work.")
        }
    }
}

class HaloUpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        try {
            if (System.currentTimeMillis() -
                HaloUpdates.prefs(app).getLong("checked_at", 0L) < 10 * 60_000L) {
                return@withContext Result.success()
            }
            @Suppress("DEPRECATION")
            val local = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0.0.0"
            val latest = ReleaseDownload.select(
                ReleaseDownload.readText(ReleaseDownload.RELEASES_URL), local
            )
            HaloUpdates.prefs(app).edit().putLong("checked_at", System.currentTimeMillis()).apply()
            if (latest == null) {
                HaloUpdates.save(app, "current", "Up to date. Automatic checks are active.")
                return@withContext Result.success()
            }
            val checksum = ReleaseDownload.expectedDigest(
                ReleaseDownload.readText(latest.checksumUrl, 16_000), latest.name
            ) ?: throw IOException("Missing SHA-256 file checksum")
            val root = File(app.filesDir, "verified_updates")
            if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create download folder")
            val apk = File(root, latest.name)
            if (!apk.isFile || !ReleaseDownload.matchesDigest(apk, checksum)) {
                HaloUpdates.save(app, "downloading", "Downloading HALO " + latest.version, latest.version)
                ReleaseDownload.download(latest, apk, checksum)
            }
            try {
                ReleaseDownload.validateArchive(app, apk)
            } catch (e: IOException) {
                HaloUpdates.save(app, "incompatible", e.message.orEmpty(), latest.version)
                return@withContext Result.success()
            }
            root.listFiles()?.filter { it != apk && it.isFile }?.forEach { it.delete() }
            HaloUpdates.save(app, "ready",
                "HALO " + latest.version + " downloaded and verified.",
                latest.version, apk, checksum)
            if (!HaloUpdates.alreadyAttempted(app, latest.version)) {
                HaloUpdates.recordAttempt(app, latest.version)
                try {
                    HaloUpdateInstaller.submit(app, apk)
                } catch (_: Exception) {
                    HaloUpdates.note(app, "ready",
                        "Update downloaded. Android requires confirmation to install.")
                }
            }
            Result.success()
        } catch (e: Exception) {
            HaloUpdates.note(app, "error",
                "Update retry scheduled: " + e.message.orEmpty().take(140))
            Result.retry()
        }
    }
}
