package dev.oddfoxerr.halo

import org.junit.Assert.*
import org.junit.Test

class MusicRenderLoopTest {
    @Test fun captureBurstsShareOnePendingFrame() {
        val loop = MusicRenderLoop()
        assertTrue(loop.request())
        repeat(100) { assertFalse(loop.request()) }
        assertTrue(loop.onFrame(1000L))
        assertTrue(loop.request())
        assertFalse(loop.onFrame(1008L))
        assertFalse(loop.request())
        assertTrue(loop.onFrame(1017L))
    }

    @Test fun highRefreshDisplayDoesNotDoubleRenderingWork() {
        val loop = MusicRenderLoop()
        var frames = 0
        // Simulate 120 Hz vsync plus incoming audio requesting extra frames.
        repeat(120) { refresh ->
            val now = refresh * 1000L / 120
            loop.request()
            if (loop.onFrame(now)) frames++
        }
        assertEquals(60, frames)
    }

    @Test fun standardRefreshDisplayKeepsEveryFrame() {
        val loop = MusicRenderLoop()
        repeat(60) { refresh ->
            loop.request()
            assertTrue(loop.onFrame(refresh * 1000L / 60))
        }
    }

    @Test fun hideAndResumeDiscardThePendingFrame() {
        val loop = MusicRenderLoop()
        loop.request()
        loop.onFrame(1000L)
        loop.request()
        loop.reset()
        assertFalse(loop.onFrame(1002L))
        assertTrue(loop.request())
        assertTrue(loop.onFrame(1002L))
    }

    @Test fun aNewSignalAfterSilenceDoesNotWaitForAnOldFrame() {
        val loop = MusicRenderLoop()
        loop.request()
        loop.onFrame(1000L)
        assertFalse(loop.onFrame(5000L))
        assertTrue(loop.request())
        assertTrue(loop.onFrame(5000L))
    }
}
