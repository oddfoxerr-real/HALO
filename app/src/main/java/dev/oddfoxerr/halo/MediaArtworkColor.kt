package dev.oddfoxerr.halo

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata

/** A lightweight sampled palette for album-art lighting. No bitmap is stored or uploaded. */
object MediaArtworkColor {
    fun fromMetadata(metadata: MediaMetadata?, fallback: Int): Int {
        val cover = try {
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } catch (_: RuntimeException) { null }
        return fromBitmap(cover, fallback)
    }

    private fun fromBitmap(bitmap: Bitmap?, fallback: Int): Int {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return fallback
        return try {
            var bestColor = fallback
            var bestScore = 0f
            val hsv = FloatArray(3)
            val steps = 8
            for (y in 1..steps) {
                for (x in 1..steps) {
                    val px = bitmap.getPixel(
                        ((x - .5f) * bitmap.width / steps).toInt().coerceIn(0, bitmap.width - 1),
                        ((y - .5f) * bitmap.height / steps).toInt().coerceIn(0, bitmap.height - 1)
                    )
                    if (Color.alpha(px) < 192) continue
                    Color.colorToHSV(px, hsv)
                    val saturation = hsv[1]
                    val luminance = hsv[2]
                    if (saturation < .25f || luminance < .23f || luminance > .95f) continue
                    val score = saturation * .7f + luminance * .3f
                    if (score > bestScore) {
                        bestScore = score
                        bestColor = Color.HSVToColor(floatArrayOf(hsv[0], saturation.coerceAtLeast(.65f), luminance.coerceAtLeast(.68f)))
                    }
                }
            }
            bestColor
        } catch (_: RuntimeException) { fallback }
    }
}
