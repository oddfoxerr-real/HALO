package dev.oddfoxerr.halo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VideoAmbilightSettings(
    val brightness: Float = .84f,
    val smoothing: Float = .55f,
    val saturation: Float = 1.18f,
    val sampleDepth: Float = .075f,
    val thicknessDp: Float = 11f,
    val diffusionDp: Float = 13f,
    val frameRate: Int = 20
) {
    fun normalized() = copy(
        brightness = brightness.coerceIn(.10f, 1f),
        smoothing = smoothing.coerceIn(0f, .94f),
        saturation = saturation.coerceIn(0f, 1.8f),
        sampleDepth = sampleDepth.coerceIn(.025f, .19f),
        thicknessDp = thicknessDp.coerceIn(3f, 34f),
        diffusionDp = diffusionDp.coerceIn(2f, 40f),
        frameRate = frameRate.coerceIn(10, 30)
    )
    fun save(context: Context) {
        val s = normalized()
        context.getSharedPreferences("halo_video_ambilight", Context.MODE_PRIVATE).edit()
            .putFloat("brightness",s.brightness).putFloat("smoothing",s.smoothing)
            .putFloat("saturation",s.saturation).putFloat("sampleDepth",s.sampleDepth)
            .putFloat("thickness",s.thicknessDp).putFloat("diffusion",s.diffusionDp)
            .putInt("fps",s.frameRate).apply()
    }
    companion object {
        fun read(context: Context): VideoAmbilightSettings {
            val p = context.getSharedPreferences("halo_video_ambilight", Context.MODE_PRIVATE)
            fun float(name: String, default: Float): Float {
                val value = p.getFloat(name,default)
                return if(value.isFinite()) value else default
            }
            return VideoAmbilightSettings(
                brightness=float("brightness",.84f), smoothing=float("smoothing",.55f),
                saturation=float("saturation",1.18f), sampleDepth=float("sampleDepth",.075f),
                thicknessDp=float("thickness",11f), diffusionDp=float("diffusion",13f),
                frameRate=p.getInt("fps",20)
            ).normalized()
        }
    }
}

data class VideoAmbientState(
    val active: Boolean = false,
    val message: String = "Video Ambilight is off",
    val frames: Long = 0L
)

object VideoAmbientRuntime {
    private val mutable = MutableStateFlow(VideoAmbientState())
    val state = mutable.asStateFlow()
    fun publish(active: Boolean, message: String, frames: Long = 0) {
        mutable.value = VideoAmbientState(active,message,frames)
    }
}
