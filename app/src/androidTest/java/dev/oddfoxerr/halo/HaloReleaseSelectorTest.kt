package dev.oddfoxerr.halo

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HaloReleaseSelectorTest {
    @Test fun officialNewerReleaseWithChecksumIsSelected() {
        val feed = """
        [
            {"tag_name":"v0.21.0","draft":false,"assets":[
                {"name":"HALO-v0.21.0-debug.apk",
                 "browser_download_url":"https://github.com/oddfoxerr-real/HALO/releases/download/v0.21.0/HALO-v0.21.0-debug.apk"},
                {"name":"SHA256SUMS.txt",
                 "browser_download_url":"https://github.com/oddfoxerr-real/HALO/releases/download/v0.21.0/SHA256SUMS.txt"}
            ]},
            {"tag_name":"v0.20.1","draft":false,"assets":[]}
        ]
        """.trimIndent()
        val selected = requireNotNull(HaloReleaseSelector.choose(feed, "0.20.1"))
        assertEquals("v0.21.0", selected.tag)
        assertEquals("HALO-v0.21.0-debug.apk", selected.fileName)
        assertNull(HaloReleaseSelector.choose(feed, "0.21.0"))
        val sha = "a".repeat(64)
        assertEquals(sha, HaloReleaseSelector.parseChecksum(
            sha + "  HALO-v0.21.0-debug.apk", selected.fileName))
        assertNull(HaloReleaseSelector.parseChecksum(
            sha + "  HALO-v0.21.0-debug.apk", "wrong.apk"))
    }
    @Test fun rejectsSpoofedGithubReleaseUrlsAndUnstableVersions() {
        val spoof = """
        [{"tag_name":"v0.21.0","draft":false,"assets":[
            {"name":"HALO-v0.21.0-debug.apk",
             "browser_download_url":"https://github.com.evil.example/oddfoxerr-real/HALO/releases/download/v0.21.0/HALO-v0.21.0-debug.apk"},
            {"name":"SHA256SUMS.txt",
             "browser_download_url":"https://github.com/oddfoxerr-real/HALO/releases/download/v0.21.0/SHA256SUMS.txt"}
        ]}]
        """.trimIndent()
        assertNull(HaloReleaseSelector.choose(spoof, "0.20.1"))
        assertTrue(HaloVersion.parse("0.22.0")!! > HaloVersion.parse("0.21.0")!!)
    }
}
