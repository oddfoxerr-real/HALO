package dev.oddfoxerr.halo

/** Coalesces capture/UI requests and limits continuous rendering to roughly 60 fps. */
class MusicRenderLoop {
    private var lastDrawAt: Long? = null
    private var pending = false

    fun request(): Boolean {
        if (pending) return false
        pending = true
        return true
    }

    /** Use the display's vsync time so time spent drawing does not lower the frame rate. */
    fun onFrame(frameTimeMs: Long): Boolean {
        if (!pending) return false
        if (lastDrawAt?.let { frameTimeMs - it in 0L until 16L } == true) return false
        pending = false
        lastDrawAt = frameTimeMs
        return true
    }

    fun reset() {
        pending = false
        lastDrawAt = null
    }
}
