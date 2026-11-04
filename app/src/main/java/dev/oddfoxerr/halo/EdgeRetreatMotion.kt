package dev.oddfoxerr.halo

import kotlin.math.abs
import kotlin.math.exp

/** Fast, reversible outward edge-slide, independent of device refresh rate. */
internal class EdgeRetreatMotion {
    var visibility = 1f
        private set
    private var target = 1f

    fun setTarget(visible: Boolean): Boolean {
        val next = if (visible) 1f else 0f
        if (next == target) return false
        target = next
        return true
    }

    fun jumpToVisible() {
        target = 1f
        visibility = 1f
    }

    fun advance(dt: Float): Boolean {
        if (abs(target - visibility) <= .001f) {
            visibility = target
            return false
        }
        val seconds = if (dt.isFinite()) dt.coerceIn(0f, .075f) else 0f
        val speed = if (target > visibility) 13f else 10f
        visibility += (target - visibility) * (1f - exp(-speed * seconds))
        if (abs(target - visibility) < .006f) visibility = target
        return visibility != target
    }
}
