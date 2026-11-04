package dev.oddfoxerr.halo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class HaloUpdaterPolicyTest {
    @Test fun numericVersionsSortCorrectly() {
        assertTrue(HaloVersion.parse("v0.20.22")!! > HaloVersion.parse("0.20.9")!!)
        assertTrue(HaloVersion.parse("v1.0.0")!! > HaloVersion.parse("0.99.99")!!)
        assertEquals(HaloVersion(0, 20, 22), HaloVersion.parse("v0.20.22"))
        assertNull(HaloVersion.parse("v0.20.22-debug"))
        assertNull(HaloVersion.parse("../0.20.22"))
        assertNull(HaloVersion.parse("v0.20.22.1"))
        assertNull(HaloVersion.parse("v0.x.22"))
    }

    @Test fun excludesDebugAssetsAndWrongHosts() {
        val releases = """
        [
          {"tag_name":"v0.20.24","draft":false,"assets":[
            {"name":"HALO-v0.20.24-debug.apk",
             "browser_download_url":"https://github.com/oddfoxerr-real/HALO/releases/download/v0.20.24/HALO-v0.20.24-debug.apk","size":59000000},
            {"name":"SHA256SUMS.txt",
             "browser_download_url":"https://github.com/oddfoxerr-real/HALO/releases/download/v0.20.24/SHA256SUMS.txt"}]},
          {"tag_name":"v0.20.23","draft":false,"assets":[
            {"name":"HALO-v0.20.23-release.apk",
             "browser_download_url":"https://other.example/HALO-v0.20.23-release.apk","size":59000000},
            {"name":"SHA256SUMS.txt",
             "browser_download_url":"https://github.com/oddfoxerr-real/HALO/releases/download/v0.20.23/SHA256SUMS.txt"}]},
          {"tag_name":"v0.20.22","draft":false,"assets":[
            {"name":"HALO-v0.20.22-release.apk",
             "browser_download_url":"https://github.com/oddfoxerr-real/HALO/releases/download/v0.20.22/HALO-v0.20.22-release.apk","size":59000000},
            {"name":"SHA256SUMS.txt",
             "browser_download_url":"https://github.com/oddfoxerr-real/HALO/releases/download/v0.20.22/SHA256SUMS.txt"}]}
        ]
        """.trimIndent()
        val selected = ReleaseDownload.select(releases, "0.20.21")
        assertEquals("v0.20.22", selected?.version)
        assertEquals("HALO-v0.20.22-release.apk", selected?.name)
        assertNull(ReleaseDownload.select(releases, "0.20.22"))
    }

    @Test fun digestIsBoundToExactFileNameAndDetectsTampering() {
        val apk = File.createTempFile("halo-update-", ".apk")
        try {
            apk.writeText("verified demo")
            val digest = MessageDigest.getInstance("SHA-256").digest(apk.readBytes())
                .joinToString("") { "%02x".format(it) }
            val sums = digest + "  HALO-v0.20.22-release.apk\n" +
                ("f".repeat(64)) + "  other.apk\n"
            assertEquals(digest, ReleaseDownload.expectedDigest(
                sums, "HALO-v0.20.22-release.apk"))
            assertNull(ReleaseDownload.expectedDigest(sums, "../evil.apk"))
            assertTrue(ReleaseDownload.matchesDigest(apk, digest))
            apk.appendText("changed")
            assertFalse(ReleaseDownload.matchesDigest(apk, digest))
        } finally {
            apk.delete()
        }
    }
}
