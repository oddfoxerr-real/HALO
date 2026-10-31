package dev.oddfoxerr.halo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * User-consented screen-pixel sampling for TV-style Ambilight.
 * Frames never leave this process, never touch storage, and are never uploaded.
 *
 * IMPORTANT: each granted MediaProjection creates exactly ONE virtual display.
 * Screen rotation / selected-app resizing uses resize() + setSurface(), not a new token.
 */
class VideoAmbientService : Service() {
    companion object {
        const val ACTION_START = "dev.oddfoxerr.halo.VIDEO_START"
        const val ACTION_STOP = "dev.oddfoxerr.halo.VIDEO_STOP"
        const val ACTION_REFRESH = "dev.oddfoxerr.halo.VIDEO_REFRESH"
        const val EXTRA_RESULT = "projection_result"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL = "halo_video_ambilight"
        private const val NOTICE_ID = 718
        @Volatile var isRunning = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowManager: WindowManager? = null
    private var overlay: VideoAmbientView? = null
    private var dpi = 0
    private var lastRead = 0L
    private var frameCount = 0
    private var stopping = false
    private var sizeWidth = 0
    private var sizeHeight = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post {
                if (!stopping) {
                    VideoAmbientRuntime.publish(false, "Screen sharing ended · start again to resume")
                    stopSelf()
                }
            }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            worker.post {
                if (!stopping && width > 0 && height > 0) resizeSurface(width, height)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("HALO-video-RGB").apply { start() }
        worker = Handler(workerThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_REFRESH -> { refresh(); return START_NOT_STICKY }
            ACTION_START -> Unit
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        if (isRunning || !Settings.canDrawOverlays(this)) {
            if (!Settings.canDrawOverlays(this)) {
                VideoAmbientRuntime.publish(false, "Enable display-over-apps before starting video lighting")
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val consent = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        val result = intent.getIntExtra(EXTRA_RESULT, 0)
        if (result != android.app.Activity.RESULT_OK || consent == null) {
            VideoAmbientRuntime.publish(false, "Screen-sharing consent required")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "HALO Video Ambilight", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Displays real-time colors from consented video capture" }
            )
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTICE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTICE_ID, notification())
            // Capture needs exclusive edge lighting; never stack two pass-through overlays.
            stopService(Intent(this, MusicEdgeService::class.java))
            NotificationEdgeOverlay.dismiss()
            val wm = getSystemService(WindowManager::class.java)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                alpha = .76f
                if (Build.VERSION.SDK_INT >= 30)
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else if (Build.VERSION.SDK_INT >= 28)
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            val view = VideoAmbientView(applicationContext)
            windowManager = wm
            overlay = view
            refresh()
            wm.addView(view, params)
            val manager = getSystemService(MediaProjectionManager::class.java)
            val granted = manager.getMediaProjection(result, consent)
                ?: throw IllegalStateException("Android did not return a projection session")
            projection = granted
            granted.registerCallback(projectionCallback, worker)

            // Downscale to reduce CPU/battery use without sacrificing 16-zone color fidelity.
            val bounds = wm.maximumWindowMetrics.bounds
            dpi = resources.displayMetrics.densityDpi
            val dimensions = dimensions(bounds.width(), bounds.height())
            sizeWidth = dimensions.first
            sizeHeight = dimensions.second
            val reader = makeReader(sizeWidth, sizeHeight)
            imageReader = reader
            virtualDisplay = granted.createVirtualDisplay(
                "HALO Ambilight (colors only)", sizeWidth, sizeHeight, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, worker
            )
            isRunning = true
            VideoAmbientRuntime.publish(true, "Reading screen colors · no video saved")
        } catch (error: Exception) {
            Log.e("HALO-video", "Could not initialize video lighting", error)
            VideoAmbientRuntime.publish(false,
                "Video capture unavailable: ${error.message?.take(110) ?: "Android restricted projection"}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, VideoAmbientService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("HALO Video Ambilight")
            .setContentText("Screen colors only · tap Stop to end capture")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "Stop", stop).build())
            .build()
    }

    private fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        val maxSide = maxOf(width, height).coerceAtLeast(1).toFloat()
        val ratio = minOf(1f, 540f / maxSide)
        return maxOf(8, (width * ratio).toInt()) to maxOf(8, (height * ratio).toInt())
    }

    private fun makeReader(width: Int, height: Int): ImageReader {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ source ->
            val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null }
            if (image != null) try {
                val timestamp = SystemClock.elapsedRealtime()
                if (timestamp - lastRead >= 85L && !stopping) {
                    lastRead = timestamp
                    val plane = image.planes.firstOrNull()
                    if (plane != null) {
                        val colors = VideoColorSampler.sample(
                            plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                        frameCount++
                        main.post {
                            overlay?.updateColors(colors)
                            if (frameCount % 24 == 0)
                                VideoAmbientRuntime.publish(true, "Matching video colors in real time", frameCount)
                        }
                    }
                }
            } catch (error: RuntimeException) {
                Log.w("HALO-video", "Skipping unreadable capture frame", error)
            } finally { image.close() }
        }, worker)
        return reader
    }

    private fun resizeSurface(contentWidth: Int, contentHeight: Int) {
        val dimensions = dimensions(contentWidth, contentHeight)
        if (dimensions.first == sizeWidth && dimensions.second == sizeHeight) return
        val display = virtualDisplay ?: return
        val previous = imageReader
        val next = makeReader(dimensions.first, dimensions.second)
        try {
            display.setSurface(null)
            display.resize(dimensions.first, dimensions.second, dpi)
            display.setSurface(next.surface)
            imageReader = next
            sizeWidth = dimensions.first
            sizeHeight = dimensions.second
            previous?.close()
        } catch (error: RuntimeException) {
            next.close()
            Log.w("HALO-video", "Projection resize was refused", error)
            try { previous?.let { display.setSurface(it.surface) } } catch (_: RuntimeException) { }
        }
    }

    private fun refresh() {
        val settings = VideoAmbientSettings.read(this)
        overlay?.apply {
            brightness = settings.brightness
            glow = settings.glow
            smoothing = settings.smoothing
            spread = settings.spread
        }
    }

    override fun onDestroy() {
        stopping = true
        isRunning = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.let {
            try { it.unregisterCallback(projectionCallback) } catch (_: RuntimeException) { }
            try { it.stop() } catch (_: RuntimeException) { }
        }
        projection = null
        overlay?.let {
            try { windowManager?.removeViewImmediate(it) } catch (_: RuntimeException) { }
        }
        overlay = null
        windowManager = null
        if (::workerThread.isInitialized) workerThread.quitSafely()
        VideoAmbientRuntime.publish(false, "Video lighting stopped", frameCount)
        super.onDestroy()
    }
}
