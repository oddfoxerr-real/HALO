package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.view.View

/** The media projection worker sends only a small palette to this GPU view. */
internal class VideoAmbilightView(context: Context) : View(context) {
    private val renderer = VideoAmbientShader()
    private val density = resources.displayMetrics.density
    private var palette: IntArray? = null
    private var options = VideoAmbilightSettings()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun configure(value: VideoAmbilightSettings) {
        options = value.normalized()
        invalidate()
    }

    fun updateColors(value: IntArray) {
        if (value.size != VideoEdgeSampler.COUNT) return
        palette = value.copyOf()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val colors = palette ?: return
        val s = options
        renderer.draw(canvas, width, height, colors,
            4f * density, 34f * density,
            s.thicknessDp * density, s.brightness, s.diffusionDp * density)
    }
}
