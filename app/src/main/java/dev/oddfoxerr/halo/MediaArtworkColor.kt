package dev.oddfoxerr.halo

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import kotlin.math.abs
import kotlin.math.min

/** Harmonized two-color palette built from groups of album pixels, not the brightest pixel. */
data class ArtworkPalette(val primary: Int, val accent: Int)

object MediaArtworkColor {
    fun fromMetadata(metadata: MediaMetadata?, fallback: Int): Int =
        paletteFromMetadata(metadata, fallback, fallback).primary

    fun paletteFromMetadata(metadata: MediaMetadata?, primaryFallback: Int, accentFallback: Int): ArtworkPalette {
        val cover = try {
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } catch (_: RuntimeException) { null }
        return fromBitmap(cover, primaryFallback, accentFallback)
    }

    fun fromBitmap(bitmap: Bitmap?, primaryFallback: Int, accentFallback: Int): ArtworkPalette {
        val fallback = ArtworkPalette(primaryFallback, accentFallback)
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return fallback
        return try {
            // Twenty-four coarse hue clusters suppress noisy single-pixel highlights,
            // dark shadows, gray backgrounds and ugly over-saturated cover edges.
            val weight = FloatArray(24)
            val satSum = FloatArray(24)
            val valSum = FloatArray(24)
            val hueSin = FloatArray(24)
            val hueCos = FloatArray(24)
            val hsv = FloatArray(3)
            for (y in 0 until 16) for (x in 0 until 16) {
                val pixel = bitmap.getPixel(
                    ((x + .5f) * bitmap.width / 16).toInt().coerceIn(0, bitmap.width - 1),
                    ((y + .5f) * bitmap.height / 16).toInt().coerceIn(0, bitmap.height - 1)
                )
                if (Color.alpha(pixel) < 200) continue
                Color.colorToHSV(pixel, hsv)
                val saturation = hsv[1]
                val value = hsv[2]
                if (saturation < .20f || value < .19f || value > .98f) continue
                // More vivid pixels matter, but entire hue regions beat outliers.
                val quality = (.35f + saturation * .65f) * (.4f + value * .6f)
                val index = (hsv[0] / 15f).toInt().coerceIn(0, 23)
                weight[index] += quality
                satSum[index] += saturation * quality
                valSum[index] += value * quality
                val radians = Math.toRadians(hsv[0].toDouble())
                hueSin[index] += kotlin.math.sin(radians).toFloat() * quality
                hueCos[index] += kotlin.math.cos(radians).toFloat() * quality
            }
            val ranked = weight.indices.sortedByDescending { index ->
                weight[index] + weight[(index + 23) % 24] * .32f + weight[(index + 1) % 24] * .32f
            }
            val winner = ranked.firstOrNull()?.takeIf { weight[it] >= .5f } ?: return fallback

            fun center(bin: Int): Float {
                val raw = Math.toDegrees(kotlin.math.atan2(hueSin[bin].toDouble(), hueCos[bin].toDouble())).toFloat()
                return (raw + 360f) % 360f
            }
            fun color(bin: Int, accent: Boolean = false): Int {
                val saturation = (satSum[bin] / weight[bin]).coerceIn(.56f, .83f)
                val value = (valSum[bin] / weight[bin]).coerceIn(.74f, .96f)
                return Color.HSVToColor(floatArrayOf(
                    center(bin), saturation, if (accent) value.coerceAtLeast(.84f) else value.coerceAtLeast(.80f)
                ))
            }
            val base = color(winner)
            val secondary = ranked.firstOrNull {
                val separation = abs(center(it) - center(winner)).let { delta -> min(delta, 360f - delta) }
                separation in 38f..152f && weight[it] >= maxOf(.8f, weight[winner] * .22f)
            }
            val companion = if (secondary != null) color(secondary, accent = true) else {
                val primaryHsv = FloatArray(3)
                Color.colorToHSV(base, primaryHsv)
                Color.HSVToColor(floatArrayOf(
                    (primaryHsv[0] + 42f) % 360f,
                    (primaryHsv[1] * .85f).coerceIn(.48f, .75f),
                    (primaryHsv[2] + .06f).coerceIn(.82f, .98f)
                ))
            }
            ArtworkPalette(base, companion)
        } catch (_: RuntimeException) { fallback }
    }
}
