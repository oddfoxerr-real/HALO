package dev.oddfoxerr.halo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Music settings are independent of all notification profiles. Keeps v0.6–0.9 preferences. */
data class MusicConfiguration(
    val mode: EdgeMode = EdgeMode.DOTS,
    val color: Int = 0xFF9A7BFF.toInt(),
    val accent: Int = 0xFF52E0CB.toInt(),
    val sensitivity: Float = 1.5f,
    val beatImpact: Float = 1.4f,
    val thickness: Float = 1f,
    val glow: Float = .75f,
    val brightness: Float = 1f,
    val spread: Float = 1f,
    val speed: Float = 1f,
    val inset: Float = 8f,
    val corner: Float = 32f,
    val leftInset: Float = 4f,
    val rightInset: Float = 4f,
    val topInset: Float = 4f,
    val bottomInset: Float = 4f,
    val drawTop: Boolean = true,
    val drawBottom: Boolean = true,
    val drawLeft: Boolean = true,
    val drawRight: Boolean = true,
    val colorSource: EdgeColorSource = EdgeColorSource.MANUAL,
    val onlyPlaying: Boolean = true,
    val allowedSources: Set<String> = emptySet(),
    val respectQuiet: Boolean = true
) {
    fun save(context: Context) {
        context.getSharedPreferences("chainsaw_music", Context.MODE_PRIVATE).edit()
            .putString("edge_mode", mode.name).putInt("edge_color", color).putInt("edge_accent", accent)
            .putFloat("sensitivity", sensitivity).putFloat("beat_impact", beatImpact).putFloat("thickness", thickness).putFloat("glow", glow)
            .putFloat("brightness", brightness).putFloat("field_spread", spread)
            .putFloat("rate", speed).putFloat("edge_inset", inset).putFloat("edge_corner", corner)
            .putFloat("left_inset", leftInset).putFloat("right_inset", rightInset)
            .putFloat("top_inset", topInset).putFloat("bottom_inset", bottomInset)
            .putBoolean("draw_top", drawTop).putBoolean("draw_bottom", drawBottom)
            .putBoolean("draw_left", drawLeft).putBoolean("draw_right", drawRight)
            .putString("color_source", colorSource.name).putBoolean("only_while_playing", onlyPlaying)
            .putStringSet("allowed_sources", allowedSources.toSet()).putBoolean("respect_quiet", respectQuiet).apply()
    }
    companion object {
        fun read(context: Context): MusicConfiguration {
            val p = context.getSharedPreferences("chainsaw_music", Context.MODE_PRIVATE)
            fun value(key: String, default: Float, min: Float, max: Float): Float =
                p.getFloat(key, default).let { if (it.isFinite()) it.coerceIn(min, max) else default }
            return MusicConfiguration(
                mode = EdgeSpectrum.mode(p.getString("edge_mode", null)),
                color = p.getInt("edge_color", 0xFF9A7BFF.toInt()), accent = p.getInt("edge_accent", 0xFF52E0CB.toInt()),
                sensitivity = value("sensitivity", 1.5f, .5f, 5f),
                beatImpact = value("beat_impact", 1.4f, .5f, 3f), thickness = value("thickness", 1f, .6f, 3f),
                glow = value("glow", .75f, 0f, 1f), brightness = value("brightness", 1f, .2f, 1f),
                spread = value("field_spread", 1f, .3f, 2.8f),
                speed = value("rate", 1f, .25f, 3f),
                inset = value("edge_inset", 8f, 0f, 100f), corner = value("edge_corner", 32f, 0f, 100f),
                leftInset = value("left_inset", 4f, -24f, 120f),
                rightInset = value("right_inset", 4f, -24f, 120f),
                topInset = value("top_inset", 4f, -24f, 120f),
                bottomInset = value("bottom_inset", 4f, -24f, 120f),
                drawTop = p.getBoolean("draw_top", true), drawBottom = p.getBoolean("draw_bottom", true),
                drawLeft = p.getBoolean("draw_left", true), drawRight = p.getBoolean("draw_right", true),
                colorSource = MediaEdgePolicy.colorSource(p.getString("color_source", null)),
                onlyPlaying = p.getBoolean("only_while_playing", true),
                allowedSources = p.getStringSet("allowed_sources", emptySet())?.toSet().orEmpty(),
                respectQuiet = p.getBoolean("respect_quiet", true)
            )
        }
    }
}

data class MusicRuntimeState(val active: Boolean = false, val message: String = "Music lighting is off", val source: String = "")
object MusicRuntime {
    private val mutable = MutableStateFlow(MusicRuntimeState())
    val state = mutable.asStateFlow()
    fun publish(active: Boolean, message: String, source: String = "") { mutable.value = MusicRuntimeState(active, message, source) }
}
