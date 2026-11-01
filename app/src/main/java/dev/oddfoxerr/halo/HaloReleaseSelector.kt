package dev.oddfoxerr.halo

import org.json.JSONArray
import java.net.URI

/** Only versioned APK assets from the official HALO GitHub Releases feed are eligible. */
internal data class HaloVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<HaloVersion> {
    override fun compareTo(other: HaloVersion): Int =
        compareValuesBy(this, other, HaloVersion::major, HaloVersion::minor, HaloVersion::patch)

    companion object {
        private val valid = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
        fun parse(text: String?): HaloVersion? {
            val match = valid.matchEntire(text ?: "") ?: return null
            return try {
                HaloVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
            } catch (_: NumberFormatException) { null }
        }
    }
}

internal data class HaloUpdateRelease(
    val tag: String,
    val version: HaloVersion,
    val apkUrl: String,
    val checksumUrl: String,
    val fileName: String
)

internal object HaloReleaseSelector {
    private const val PREFIX = "https://github.com/oddfoxerr-real/HALO/releases/download/"

    fun choose(raw: String, installed: String): HaloUpdateRelease? {
        val current = HaloVersion.parse(installed) ?: return null
        val releases = JSONArray(raw)
        val results = ArrayList<HaloUpdateRelease>()
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", true)) continue
            val tag = release.optString("tag_name")
            val version = HaloVersion.parse(tag) ?: continue
            if (!tag.startsWith("v") || version <= current) continue
            val apkName = "HALO-${tag}.apk"
            val assets = release.optJSONArray("assets") ?: continue
            var apk: String? = null
            var checksum: String? = null
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name")
                val location = asset.optString("browser_download_url")
                if (!isTrustedReleaseUrl(location, tag, name)) continue
                if (name == apkName) apk = location
                if (name == "SHA256SUMS.txt") checksum = location
            }
            if (apk != null && checksum != null) {
                results += HaloUpdateRelease(tag, version, apk, checksum, apkName)
            }
        }
        return results.maxByOrNull { it.version }
    }

    private fun isTrustedReleaseUrl(url: String, tag: String, file: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        if (uri.scheme != "https" || uri.host != "github.com" ||
            uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null) return false
        return url == "${PREFIX}${tag}/${file}"
    }

    fun parseChecksum(text: String, expectedFile: String): String? {
        val line = text.lineSequence().firstOrNull {
            val pieces = it.trim().split(Regex("\\s+"))
            pieces.size == 2 && pieces[1].removePrefix("*") == expectedFile
        } ?: return null
        val checksum = line.trim().split(Regex("\\s+"))[0]
        return checksum.lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }
}
