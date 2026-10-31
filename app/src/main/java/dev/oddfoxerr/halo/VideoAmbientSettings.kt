package dev.oddfoxerr.halo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Video lighting is opt-in and independent of microphone/music capture. */
data class VideoAmbientSettings(
    val brightness: Float = .95f,
    val glow: Float = .8f,
    val spread: Float = 1.0f,
    val smoothing: Float = .58f
) {
    fun normalized(): VideoAmbientSettings = copy(
        brightness = brightness.coerceIn(.2f, 1f),
        glow = glow.coerceIn(0f, 1f),
        spread = spread.coerceIn(.5f, 2f),
        smoothing = smoothing.coerceIn(.05f, 1f)
    )

    fun save(context: Context) {
        val s = normalized()
        context.getSharedPreferences("halo_video", Context.MODE_PRIVATE).edit()
            .putFloat("brightness", s.brightness)
            .putFloat("glow", s.glow)
            .putFloat("spread", s.spread)
            .putFloat("smoothing", s.smoothing)
            .apply()
    }

    companion object {
        fun read(context: Context): VideoAmbientSettings {
            val p = context.getSharedPreferences("halo_video", Context.MODE_PRIVATE)
            fun readFloat(key: String, fallback: Float) =
                p.getFloat(key, fallback).let { if (it.isFinite()) it else fallback }
            return VideoAmbientSettings(
                readFloat("brightness", .95f), readFloat("glow", .8f),
                readFloat("spread", 1f), readFloat("smoothing", .58f)
            ).normalized()
        }
    }
}

data class VideoAmbientState(
    val active: Boolean = false,
    val message: String = "Video lighting is off",
    val frames: Int = 0
)

object VideoAmbientRuntime {
    private val current = MutableStateFlow(VideoAmbientState())
    val state = current.asStateFlow()
    fun publish(active: Boolean, message: String, frames: Int = 0) {
        current.value = VideoAmbientState(active, message, frames)
    }
}
