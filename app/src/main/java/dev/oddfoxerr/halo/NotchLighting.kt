package dev.oddfoxerr.halo

import android.graphics.RectF
import android.view.View
import kotlin.math.abs

enum class NotchMode(val title: String) {
    OFF("Off"), AUTO("Auto"), CIRCLE("Camera ring"), PILL("Camera pill")
}

data class NotchSettings(
    val mode: NotchMode = NotchMode.OFF,
    val xOffsetDp: Float = 0f,
    val topDp: Float = 24f,
    val widthDp: Float = 30f,
    val heightDp: Float = 30f,
    val gapDp: Float = 4f
) {
    fun normalized() = copy(
        xOffsetDp = xOffsetDp.coerceIn(-120f, 120f),
        topDp = topDp.coerceIn(0f, 125f),
        widthDp = widthDp.coerceIn(12f, 160f),
        heightDp = heightDp.coerceIn(12f, 90f),
        gapDp = gapDp.coerceIn(0f, 24f)
    )
}

internal object NotchGeometry {
    fun manual(width: Int, density: Float, input: NotchSettings): RectF {
        val s = input.normalized()
        val w = (s.widthDp + 2f * s.gapDp) * density
        val h = if (s.mode == NotchMode.CIRCLE) w else (s.heightDp + 2f * s.gapDp) * density
        val cx = width / 2f + s.xOffsetDp * density
        val cy = s.topDp * density
        return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    fun find(view: View, input: NotchSettings): Pair<RectF, Boolean>? {
        val settings = input.normalized()
        if (settings.mode == NotchMode.OFF) return null
        if (settings.mode != NotchMode.AUTO) {
            return manual(view.width, view.resources.displayMetrics.density, settings) to
                (settings.mode == NotchMode.CIRCLE)
        }
        val cutout = view.rootWindowInsets?.displayCutout ?: return null
        val screenWidth = view.resources.displayMetrics.widthPixels
        val screenHeight = view.resources.displayMetrics.heightPixels
        val rect = cutout.boundingRects.filter { it.top < screenHeight * .3f }
            .minByOrNull { abs(it.centerX() - screenWidth / 2) } ?: return null
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val density = view.resources.displayMetrics.density
        val gap = settings.gapDp * density
        val expanded = RectF(rect.left - location[0] - gap, rect.top - location[1] - gap,
            rect.right - location[0] + gap, rect.bottom - location[1] + gap)
        return expanded to (abs(expanded.width() - expanded.height()) < 10f * density)
    }
}

