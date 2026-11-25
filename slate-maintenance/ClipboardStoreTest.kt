package com.odd.slatekeyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ClipboardStoreTest {
    private lateinit var context: Context
    private lateinit var store: ClipboardStore
    private lateinit var clipboard: ClipboardManager
    private val uri = Uri.parse("content://test.images/photo")
    private val bytes = ByteArray(1024) { (it % 251).toByte() }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        KeyboardPrefs.setClipboardHistory(context, true)
        store = ClipboardStore(context)
        store.clearAll()
        File(context.filesDir, "slate_clip_images").deleteRecursively()
        clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private fun copyText(value: String, target: ClipboardStore = store) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Test", value))
        target.capture(clipboard, true)
    }

    // Queue a barrier on the real store worker; no timing-based sleep or fake store.
    // Supports the original instance executor as well as the fixed shared executor.
    private fun drain(target: ClipboardStore = store) {
        val field = ClipboardStore::class.java.getDeclaredField("io")
        field.isAccessible = true
        val owner = if (Modifier.isStatic(field.modifiers)) null else target
        (field.get(owner) as ExecutorService).submit {}.get(10, TimeUnit.SECONDS)
    }

    private class PausedImage(private val bytes: ByteArray) : InputStream() {
        val copying = CountDownLatch(1)
        val resume = CountDownLatch(1)
        private var sent = false
        override fun read(): Int = throw UnsupportedOperationException("Use bulk reads")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!sent) {
                check(length >= bytes.size)
                bytes.copyInto(buffer, offset)
                sent = true
                return bytes.size
            }
            // The first chunk has already been written to the real filesystem.
            copying.countDown()
            check(resume.await(10, TimeUnit.SECONDS)) { "Test did not release image stream" }
            return -1
        }
    }

    private fun duringImageCopy(action: () -> Unit) {
        val stream = PausedImage(bytes)
        shadowOf(context.contentResolver).registerInputStream(uri, stream)
        store.captureImage(uri, "image/png")
        try {
            assertTrue("Image copy must start", stream.copying.await(10, TimeUnit.SECONDS))
            action()
        } finally {
            stream.resume.countDown()
            drain()
        }
    }

    @Test fun inFlightImageSurvivesTextCaptureFromAnotherStore() {
        duringImageCopy { copyText("Keep this text", ClipboardStore(context)) }
        val images = store.items().filter { it.isImage }
        assertEquals("Image must survive concurrent text capture", 1, images.size)
        assertArrayEquals(bytes, store.thumbnailFile(images.single())!!.readBytes())
        assertTrue(store.items().any { it.text == "Keep this text" })
        assertFalse(File(context.filesDir, "slate_clip_images").listFiles().orEmpty()
            .any { it.extension == "pending" })
    }

    @Test fun clearAllFromAnotherStoreDiscardsInFlightImage() {
        copyText("Will be cleared")
        duringImageCopy { ClipboardStore(context).clearAll() }
        assertTrue("Clear all must not be undone by an import", store.items().isEmpty())
        assertTrue(File(context.filesDir, "slate_clip_images").listFiles().orEmpty().isEmpty())
    }

    @Test fun clearUnpinnedKeepsPinnedTextAndDiscardsInFlightImage() {
        copyText("Pinned")
        store.togglePin(store.items().single())
        copyText("Unpinned")
        duringImageCopy { ClipboardStore(context).clearUnpinned() }
        assertEquals(listOf("Pinned"), store.items().map { it.text })
        assertTrue(store.items().single().pinned)
        assertTrue(File(context.filesDir, "slate_clip_images").listFiles().orEmpty().isEmpty())
    }

    @Test fun disablingHistoryDiscardsInFlightImage() {
        duringImageCopy { KeyboardPrefs.setClipboardHistory(context, false) }
        assertTrue(store.items().isEmpty())
        assertTrue(File(context.filesDir, "slate_clip_images").listFiles().orEmpty().isEmpty())
    }

    @Test fun oversizedImageLeavesNoFileOrHistoryEntry() {
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(8_000_001)))
        store.captureImage(uri, "image/png")
        drain()
        assertTrue(store.items().isEmpty())
        assertTrue(File(context.filesDir, "slate_clip_images").listFiles().orEmpty().isEmpty())
    }

    @Test fun failedImageReadKeepsExistingTextAndRemovesPartialFile() {
        copyText("Existing")
        val stream = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("Source access revoked")
        }
        shadowOf(context.contentResolver).registerInputStream(uri, stream)
        store.captureImage(uri, "image/png")
        drain()
        assertEquals(listOf("Existing"), store.items().map { it.text })
        assertTrue(File(context.filesDir, "slate_clip_images").listFiles().orEmpty().isEmpty())
    }
}
