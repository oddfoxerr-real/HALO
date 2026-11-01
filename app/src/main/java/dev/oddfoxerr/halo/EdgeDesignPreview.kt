package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.view.View

/** A still frame from the real material, with no frame loop or audio capture per tile. */
internal class EdgeDesignPreview(context: Context) : View(context) {
    private val engine = if (Build.VERSION.SDK_INT >= 33) ShaderEdgeEngine() else null
    private val frame = RectF()
    private val bands = FloatArray(EdgeSpectrum.BAND_COUNT) { index ->
        when (index) { in 0..5 -> .55f; in 6..15 -> .28f; else -> .18f }
    }
    private var mode = EdgeMode.ELECTRIC_ARCS
    private var first = 0xFF9A7BFF.toInt()
    private var second = 0xFF52E0CB.toInt()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun configure(design: EdgeMode, primary: Int, accent: Int) {
        if (mode == design && first == primary && second == accent) return
        mode = design
        first = primary
        second = accent
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = resources.displayMetrics.density * .65f
        val inset = 5f * unit
        frame.set(inset, inset, width - inset, height - inset)
        if (frame.width() <= 0f || frame.height() <= 0f) return
        engine?.render(canvas, width, height, frame, 12f * unit, mode,
            bands, .5f, .12f, .4f, unit, 1f, .5f, 1f,
            first, second, .4f,
            dynamicLow = .55f, dynamicMid = .28f, dynamicHigh = .18f,
            dynamicHit = .4f, dynamicForce = 2.4f, pixelDensity = unit)
    }
}
