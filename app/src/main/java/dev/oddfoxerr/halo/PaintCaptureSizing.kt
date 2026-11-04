package dev.oddfoxerr.halo

/** Prevent MediaProjection frames exceeding PaintedFrameDecoder's bounds. */
internal object PaintCaptureSizing {
    const val MAX_SAMPLE_WIDTH = 1024
    const val MAX_SAMPLE_HEIGHT = 2048

    fun divisorFor(
        fullWidth: Int,
        fullHeight: Int,
        fullDisplayPainting: Boolean,
        isPainted: Boolean
    ): Int {
        if (!fullDisplayPainting) return if (isPainted) 3 else 6
        val widthLimit = (fullWidth.coerceAtLeast(1) + MAX_SAMPLE_WIDTH - 1) /
            MAX_SAMPLE_WIDTH
        val heightLimit = (fullHeight.coerceAtLeast(1) + MAX_SAMPLE_HEIGHT - 1) /
            MAX_SAMPLE_HEIGHT
        return maxOf(2, widthLimit, heightLimit)
    }
}
