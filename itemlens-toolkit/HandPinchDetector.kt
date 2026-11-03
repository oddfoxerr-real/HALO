package com.oddfinder.itemlens

import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import com.google.ar.core.Frame
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import android.content.Context
import kotlin.math.hypot

data class PinchPoint(val x: Float, val y: Float, val touching: Boolean, val justPressed: Boolean)

/**
 * Executes on ARCore's GL thread. Uses the same ARCore CPU camera frame, so
 * no competing CameraX capture session is necessary.
 *
 * Pinch threshold is relative to index-pinky MCP span; this is not a
 * physical distance measurement, and never creates 3D points by itself.
 */
class HandPinchDetector(context: Context) {
    private var detector: HandLandmarker? = null
    private var initialized = false
    private var lastInference = 0L
    private var pressed = false
    private var consecutiveClosed = 0
    private var lastPressAt = 0L

    private fun instance(context: Context): HandLandmarker? {
        if (!initialized) {
            initialized = true
            detector = try {
                val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
                HandLandmarker.createFromOptions(
                    context,
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(base)
                        .setNumHands(1)
                        .setMinHandDetectionConfidence(0.55f)
                        .setMinHandPresenceConfidence(0.55f)
                        .setMinTrackingConfidence(0.5f)
                        .setRunningMode(RunningMode.VIDEO)
                        .build()
                )
            } catch (_: Exception) { null }
        }
        return detector
    }
    fun detect(context: Context, frame: Frame, now: Long): PinchPoint? {
        if (now - lastInference < 170L) return null
        lastInference = now
        val model = instance(context) ?: return null
        return try {
            frame.acquireCameraImage().use { image ->
                val bitmap = convertYuv(image)
                val mpImage = BitmapImageBuilder(bitmap).build()
                val result = model.detectForVideo(mpImage, now)
                bitmap.recycle()
                val hand = result.landmarks().firstOrNull()
                if (hand == null || hand.size < 21) {
                    pressed = false
                    consecutiveClosed = 0
                    return null
                }
                val thumb = hand[4]
                val index = hand[8]
                val indexBase = hand[5]
                val pinkyBase = hand[17]
                val palmSpan = hypot(
                    (indexBase.x() - pinkyBase.x()).toDouble(),
                    (indexBase.y() - pinkyBase.y()).toDouble()
                ).coerceAtLeast(0.025)
                val relativeGap = hypot(
                    (thumb.x() - index.x()).toDouble(),
                    (thumb.y() - index.y()).toDouble()
                ) / palmSpan
                val closed = if (pressed) relativeGap < 0.48 else relativeGap < 0.33
                consecutiveClosed = if (closed) consecutiveClosed + 1 else 0
                val began = !pressed && consecutiveClosed >= 2 && now - lastPressAt > 650L
                if (began) {
                    pressed = true
                    lastPressAt = now
                } else if (!closed) {
                    pressed = false
                }
                PinchPoint(
                    (thumb.x() + index.x()) * 0.5f,
                    (thumb.y() + index.y()) * 0.5f,
                    pressed,
                    began
                )
            }
        } catch (_: Exception) {
            pressed = false
            consecutiveClosed = 0
            null
        }
    }

    fun close() {
        try { detector?.close() } catch (_: Exception) {}
        detector = null
        initialized = false
    }

    private fun convertYuv(image: Image): Bitmap {
        val targetWidth = 256
        val targetHeight = (image.height.toFloat() * targetWidth / image.width).toInt().coerceIn(128, 320)
        val pixels = IntArray(targetWidth * targetHeight)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (outY in 0 until targetHeight) {
            val y = (outY * image.height / targetHeight).coerceIn(0, image.height - 1)
            for (outX in 0 until targetWidth) {
                val x = (outX * image.width / targetWidth).coerceIn(0, image.width - 1)
                val yi = y * yPlane.rowStride + x * yPlane.pixelStride
                val ui = (y / 2) * uPlane.rowStride + (x / 2) * uPlane.pixelStride
                val vi = (y / 2) * vPlane.rowStride + (x / 2) * vPlane.pixelStride
                val Y = (yBuf.get(yi).toInt() and 255) - 16
                val U = (uBuf.get(ui).toInt() and 255) - 128
                val V = (vBuf.get(vi).toInt() and 255) - 128
                val c = Y.coerceAtLeast(0)
                val r = ((298 * c + 409 * V + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * U - 208 * V + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * U + 128) shr 8).coerceIn(0, 255)
                pixels[outY * targetWidth + outX] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    }
}
