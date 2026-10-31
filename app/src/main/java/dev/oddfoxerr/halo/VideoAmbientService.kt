package dev.oddfoxerr.halo

import android.app.Activity
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
import android.view.Gravity
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Opt-in, ephemeral MediaProjection screen sampler.
 *
 * - Every session uses fresh user consent; never persists the capture Intent.
 * - Captures are downscaled, sampled and discarded on a worker thread.
 * - Nothing is recorded, uploaded, networked or placed in persistent storage.
 * - Captures which are DRM/FLAG_SECURE protected can be black and remain protected.
 */
class VideoAmbientService : Service() {
    companion object {
        const val ACTION_START = "dev.oddfoxerr.halo.AMBILIGHT_START"
        const val ACTION_STOP = "dev.oddfoxerr.halo.AMBILIGHT_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_CONSENT = "consent_data"
        private const val CHANNEL = "halo_video_ambilight"
        private const val NOTICE_ID = 741
        @Volatile var isRunning = false
            private set
    }

    private val shuttingDown = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("HALO Ambilight Frame Sampling")
    private lateinit var worker: Handler
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: VideoAmbientView? = null
    private var windows: WindowManager? = null
    private var lastFrameAt = 0L
    private var lastColors = IntArray(VideoColorSampler.ZONE_COUNT) { 0xFF000000.toInt() }
    private var frameCount = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        workerThread.start()
        worker = Handler(workerThread.looper)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "Video Ambilight", NotificationManager.IMPORTANCE_LOW
        ))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || isRunning || !Settings.canDrawOverlays(this)) {
            if (!isRunning) stopSelf()
            return START_NOT_STICKY
        }
        val consent = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
        else intent.getParcelableExtra<Intent>(EXTRA_CONSENT)
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        if (code != Activity.RESULT_OK || consent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            startForeground(NOTICE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            // Music and notification overlays must never compete with video colors.
            stopService(Intent(this, MusicEdgeService::class.java))
            NotificationEdgeOverlay.dismiss()
            val manager = getSystemService(MediaProjectionManager::class.java)
            val session = manager.getMediaProjection(code, consent)
                ?: throw SecurityException("Screen capture approval was not granted")
            projection = session
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { if (!shuttingDown.get()) stopSelf() }
                }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    worker.post { if (!shuttingDown.get()) resizeCapture(width, height) }
                }
            }
            projectionCallback = callback
            session.registerCallback(callback, main)
            createOverlay()
            val displayMetrics = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            worker.post {
                try {
                    if (!shuttingDown.get()) startCapture(session, displayMetrics.width(), displayMetrics.height())
                } catch (error: Exception) {
                    main.post {
                        VideoAmbientRuntime.update(VideoAmbientStatus(message = "Screen capture failed: ${error.message}"))
                        stopSelf()
                    }
                }
            }
            isRunning = true
            VideoAmbientRuntime.update(VideoAmbientStatus(active = true, message = "Starting live screen colors"))
            START_NOT_STICKY
        } catch (error: Exception) {
            VideoAmbientRuntime.update(VideoAmbientStatus(message = "Cannot start Video Ambilight: ${error.message}"))
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, VideoAmbientService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("HALO Video Ambilight")
            .setContentText("Matching live screen colors • Stop anytime")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build())
            .build()
    }

    private fun createOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_SECURE
        val parameters = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = .78f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val drawing = VideoAmbientView(applicationContext)
        drawing.configure(MusicConfiguration.read(this))
        wm.addView(drawing, parameters)
        windows = wm
        overlay = drawing
    }

    /** A projection token creates exactly one VirtualDisplay for its entire lifetime. */
    private fun startCapture(session: MediaProjection, displayWidth: Int, displayHeight: Int) {
        val pair = downscale(displayWidth, displayHeight)
        val imageReader = makeReader(pair.first, pair.second)
        reader = imageReader
        virtualDisplay = session.createVirtualDisplay(
            "HALO Frame Colors", pair.first, pair.second,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, worker
        )
    }

    private fun downscale(w: Int, h: Int): Pair<Int, Int> {
        val factor = 480f / max(w, h).coerceAtLeast(1)
        return Pair(max(48, (w * factor).roundToInt()), max(48, (h * factor).roundToInt()))
    }

    private fun makeReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ source ->
                val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null }
                image?.use { frame ->
                    if (shuttingDown.get()) return@use
                    val timestamp = SystemClock.elapsedRealtime()
                    if (timestamp - lastFrameAt < 45L) return@use
                    lastFrameAt = timestamp
                    val plane = frame.planes.firstOrNull() ?: return@use
                    val colors = VideoColorSampler.sample(
                        plane.buffer, frame.width, frame.height, plane.rowStride, plane.pixelStride
                    )
                    lastColors = VideoColorSampler.smooth(lastColors, colors, .76f)
                    frameCount++
                    val sampled = lastColors.copyOf()
                    val frames = frameCount
                    main.post {
                        if (shuttingDown.get()) return@post
                        overlay?.updateZones(sampled)
                        if (frames % 4L == 0L) {
                            VideoAmbientRuntime.update(VideoAmbientStatus(
                                active = true,
                                message = if (VideoColorSampler.isBlack(sampled))
                                    "Black frame • dark scene or protected video" else "Following live video colors",
                                zones = sampled.toList(), frameCount = frames
                            ))
                        }
                    }
                }
            }, worker)
        }

    private fun resizeCapture(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val display = virtualDisplay ?: return
        val size = downscale(width, height)
        val next = makeReader(size.first, size.second)
        try {
            display.resize(size.first, size.second, resources.displayMetrics.densityDpi)
            display.setSurface(next.surface)
            val previous = reader
            reader = next
            previous?.setOnImageAvailableListener(null, null)
            previous?.close()
        } catch (error: RuntimeException) {
            next.close()
            main.post { stopSelf() }
        }
    }

    override fun onDestroy() {
        shuttingDown.set(true)
        isRunning = false
        overlay?.let { try { windows?.removeViewImmediate(it) } catch (_: RuntimeException) {} }
        overlay = null
        windows = null
        worker.post {
            reader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            reader?.close()
            virtualDisplay = null
            reader = null
            projection?.let { session ->
                projectionCallback?.let { session.unregisterCallback(it) }
                session.stop()
            }
            projection = null
        }
        workerThread.quitSafely()
        VideoAmbientRuntime.update(VideoAmbientStatus(message = "Video Ambilight stopped"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
