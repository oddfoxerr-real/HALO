package dev.oddfoxerr.halo

import android.content.Context

/**
 * Video has its own restrained visual language. Music, notification and camera
 * presets remain untouched by movie playback preferences.
 */
enum class VideoLightStyle(val label: String) {
    CINEMATIC("Cinematic glow"),
    SOFT_PULSE("Soft breathing"),
    SELECTED("Selected animation")
}

data class VideoLightingPreferences(
    val style: VideoLightStyle = VideoLightStyle.CINEMATIC,
    val intensity: Float = .58f,
    val widthDp: Float = 18f,
    val response: Float = .85f,
    val sampleInset: Float = .055f
) {
    companion object {
        const val STORE = "halo_video_settings"
        fun read(context: Context): VideoLightingPreferences {
            val preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val style = VideoLightStyle.entries.firstOrNull {
                it.name == preferences.getString("style", VideoLightStyle.CINEMATIC.name)
            } ?: VideoLightStyle.CINEMATIC
            fun safe(key: String, fallback: Float, min: Float, max: Float): Float {
                val value = preferences.getFloat(key, fallback)
                return if (value.isFinite()) value.coerceIn(min, max) else fallback
            }
            return VideoLightingPreferences(
                style,
                safe("intensity", .58f, .12f, 1f),
                safe("width_dp", 18f, 6f, 36f),
                safe("response", .85f, .12f, 1f),
                safe("sample_inset", .055f, .03f, .13f)
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString("style", style.name)
            .putFloat("intensity", intensity.coerceIn(.12f, 1f))
            .putFloat("width_dp", widthDp.coerceIn(6f, 36f))
            .putFloat("response", response.coerceIn(.12f, 1f))
            .putFloat("sample_inset", sampleInset.coerceIn(.03f, .13f))
            .apply()
    }
}
