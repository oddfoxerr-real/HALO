package com.oddfinder.itemlens

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import com.google.ar.core.Frame
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class SeenObject(val label: String, val confidence: Float, val x: Float, val y: Float)

/**
 * On-device recognition of common COCO classes (bottle, chair, laptop, etc).
 * Results are only candidates; depth hits must confirm actual 3D position.
 * Model inference is off the ARCore render thread. No user images leave the device.
 */
class AutoObjectScanner(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor { job ->
        Thread(job, "ItemLens-ObjectScanner").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private val results = ConcurrentLinkedQueue<List<SeenObject>>()
    private var model: ObjectDetector? = null
    private var initAttempted = false
    private var lastSubmittedAt = 0L
    @Volatile var unavailableReason: String? = null
        private set
    @Volatile private var closed = false

    fun scan(frame: Frame, now: Long) {
        if (closed || unavailableReason != null || busy.get() || now - lastSubmittedAt < 1000L) return
        lastSubmittedAt = now
        if (!busy.compareAndSet(false, true)) return
        val bitmap = try {
            frame.acquireCameraImage().use { image -> copyRgb(image, 320) }
        } catch (_: Exception) {
            busy.set(false)
            return
        }
        worker.execute {
            try {
                if (closed) return@execute
                if (!initAttempted) {
                    initAttempted = true
                    model = ObjectDetector.createFromOptions(
                        context,
                        ObjectDetector.ObjectDetectorOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
                            .setRunningMode(RunningMode.VIDEO)
                            .setScoreThreshold(0.67f)
                            .setMaxResults(8)
                            .build()
                    )
                }
                val prediction = model?.detectForVideo(
                    BitmapImageBuilder(bitmap).build(), SystemClock.uptimeMillis()
                )
                if (prediction != null) {
                    results.offer(prediction.detections().mapNotNull { detection ->
                        val category = detection.categories().firstOrNull() ?: return@mapNotNull null
                        val label = category.categoryName()?.trim()?.lowercase(Locale.US) ?: return@mapNotNull null
                        val confidence = category.score()
                        if (label.isBlank() || confidence < 0.67f) return@mapNotNull null
                        val box = detection.boundingBox()
                        val midX = (box.left + box.right) / (2f * bitmap.width)
                        val midY = (box.top + box.bottom) / (2f * bitmap.height)
                        SeenObject(label, confidence, midX.coerceIn(0f,1f), midY.coerceIn(0f,1f))
                    })
                    while (results.size > 2) results.poll()
                }
            } catch (error: Exception) {
                unavailableReason = error.localizedMessage ?: "Object recognition unavailable"
            } finally {
                bitmap.recycle()
                busy.set(false)
            }
        }
    }

    fun poll(): List<SeenObject>? {
        var found: List<SeenObject>? = null
        while (true) {
            val next = results.poll() ?: return found
            found = next
        }
    }

    fun close() {
        closed = true
        worker.shutdown()
        results.clear()
        if (!busy.get()) {
            runCatching { model?.close() }
            model = null
        }
    }

    private fun copyRgb(image: Image, targetWidth: Int): Bitmap {
        val targetHeight = (image.height.toFloat() * targetWidth / image.width).toInt().coerceIn(120, 480)
        val pixels = IntArray(targetWidth * targetHeight)
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        for (py in 0 until targetHeight) {
            val iy = (py * image.height / targetHeight).coerceIn(0, image.height - 1)
            for (px in 0 until targetWidth) {
                val ix = (px * image.width / targetWidth).coerceIn(0, image.width - 1)
                val y = (yBuf.get(iy*yP.rowStride + ix*yP.pixelStride).toInt() and 255) - 16
                val u = (uBuf.get(iy/2*uP.rowStride + ix/2*uP.pixelStride).toInt() and 255) - 128
                val v = (vBuf.get(iy/2*vP.rowStride + ix/2*vP.pixelStride).toInt() and 255) - 128
                val c = y.coerceAtLeast(0)
                val red = ((298*c + 409*v + 128) shr 8).coerceIn(0,255)
                val green = ((298*c - 100*u - 208*v + 128) shr 8).coerceIn(0,255)
                val blue = ((298*c + 516*u + 128) shr 8).coerceIn(0,255)
                pixels[py*targetWidth+px] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(pixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    }
}
