package dev.oddfoxerr.halo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.view.View
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
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicit-consent screen sampling. Never saves pixels, records video or sends frame data. */
class VideoAmbilightService : Service() {
    companion object {
        const val ACTION_START = "dev.oddfoxerr.halo.VIDEO_START"
        const val ACTION_STOP = "dev.oddfoxerr.halo.VIDEO_STOP"
        const val ACTION_REFRESH = "dev.oddfoxerr.halo.VIDEO_REFRESH"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_PAINTED = "projection_painted"
        private const val CHANNEL = "halo_video_ambilight"
        private const val NOTICE_ID = 711

        private val statusValue = MutableStateFlow("Video lighting is off")
        val status = statusValue.asStateFlow()
        @Volatile var isRunning = false
            private set
        @Volatile var isPainted = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private var reader: ImageReader? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var windowManager: WindowManager? = null
    private var view: ChainsawView? = null
    @Volatile private var livePaintSurface: LivePaintSurface? = null
    private var paintDispatcher: LatestPaintDispatcher? = null
    @Volatile private var paintSettings = PaintedScreenPreferences()
    private var fpsWindowStarted = 0L
    private var fpsFrames = 0
    @Volatile private var framesRendered = 0L
    @Volatile private var overlay: View? = null
    private var lastSampleAt = 0L
    private var colors = VideoEdgeColors.BLACK
    @Volatile private var lighting = VideoLightingPreferences()
    @Volatile private var stopping = false
    private var framesSeen = 0
    private var blankPaintFrames = 0
    private val paintHealth = PaintedCaptureHealth()
    @Volatile private var lastUsablePaintFrameAt = 0L
    private val painterWatchdog = object : Runnable {
        override fun run() {
            if (!stopping && isPainted) {
                val now = SystemClock.uptimeMillis()
                if (overlay != null && now - lastUsablePaintFrameAt > 4000L) {
                    // An unchanged screen can legitimately emit no new frames.
                    // Do not erase the one correctly rendered painting.
                    statusValue.value = "Painting active · no new screen changes"
                }
                main.postDelayed(this, 1200L)
            }
        }
    }
    private var width = 0
    private var height = 0
    private var densityDpi = 0

    override fun onCreate() {
        super.onCreate()
        lighting = VideoLightingPreferences.read(this)
        thread = HandlerThread("HALO-video-color-analysis").apply { start() }
        worker = Handler(thread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Hard safety gate: older versions launched an opaque system-wide
        // screen-copy SurfaceView. On Samsung it darkened the display and
        // prevented interactions with unrelated applications. Never start
        // this mode, even from an old notification or stale launch Intent.
        if (PaintingSafetyGate.rejectProjectionStart(
                intent?.action, intent?.getBooleanExtra(EXTRA_PAINTED, false) == true)) {
            statusValue.value =
                "Full-screen painting is disabled: use the safe in-app preview."
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_REFRESH -> {
                lighting = VideoLightingPreferences.read(this)
                if (isPainted) paintSettings = PaintedScreenPreferences.read(this)
                else {
                    view?.configure(MusicConfiguration.read(this))
                    view?.configureVideo(lighting)
                }
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> return START_NOT_STICKY
        }
        if (isRunning || !Settings.canDrawOverlays(this)) {
            if (!Settings.canDrawOverlays(this)) statusValue.value = "Allow display over other apps first"
            return START_NOT_STICKY
        }
        val request = intent ?: return START_NOT_STICKY
        val resultCode = request.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = if (Build.VERSION.SDK_INT >= 33)
            request.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else request.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null || resultCode != android.app.Activity.RESULT_OK) {
            statusValue.value = "Screen capture consent was not granted"
            stopSelf()
            return START_NOT_STICKY
        }
        isPainted = request.getBooleanExtra(EXTRA_PAINTED, false)
        paintHealth.reset()
        paintSettings = PaintedScreenPreferences.read(this)
        framesRendered = 0L
        fpsFrames = 0
        fpsWindowStarted = SystemClock.uptimeMillis()
        lastUsablePaintFrameAt = SystemClock.uptimeMillis()
        if (isPainted && Build.VERSION.SDK_INT < 33) {
            statusValue.value = "Painted Screen requires Android 13 or newer"
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "HALO Video Lighting", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) })
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTICE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(NOTICE_ID, notification())

            isRunning = true
            statusValue.value = if (isPainted) "Starting live screen painting…" else "Starting screen-color analysis…"
            // One live video mode at a time. Reusing a separate audio overlay would obscure input.
            stopService(Intent(this, MusicEdgeService::class.java))
            NotificationEdgeOverlay.dismiss()
            PillHaloOverlay.dismiss()
            val display = getSystemService(WindowManager::class.java).defaultDisplay
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            densityDpi = dm.densityDpi
            val divisor = if (isPainted) 3 else 6
            width = (dm.widthPixels / divisor).coerceAtLeast(96)
            height = (dm.heightPixels / divisor).coerceAtLeast(96)
            // Do not put the painter over the permission chooser or the first
            // capture frame. Create its secure overlay only after valid pixels
            // arrive; this avoids capturing our own surface on session startup.
            if (!isPainted) showOverlay() else {
                paintDispatcher = LatestPaintDispatcher(
                    surface = { livePaintSurface },
                    preferences = { paintSettings },
                    onRendered = { count, fps ->
                        framesRendered = count
                        lastUsablePaintFrameAt = SystemClock.uptimeMillis()
                        statusValue.value = "Live painting · %.1f fps · %d frames".format(
                            java.util.Locale.US, fps, count)
                    }
                )
                main.postDelayed(painterWatchdog, 1200L)
            }

            val manager = getSystemService(MediaProjectionManager::class.java)
            val capture = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("Screen projection not provided by Android")
            projection = capture
            capture.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post {
                        if (!stopping) {
                            hidePaintLayer()
                            statusValue.value = "Android ended screen sharing · restart from HALO"
                            stopSelf()
                        }
                    }
                }
                override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
                    worker.post { resize(newWidth, newHeight) }
                }
            }, main)

            worker.post {
                try { startCapture(capture) }
                catch (error: Exception) {
                    Log.e("HALO", "Video projection unavailable", error)
                    statusValue.value = "Video capture could not start"
                    main.post { stopSelf() }
                }
            }
        } catch (error: Exception) {
            Log.e("HALO", "Video lighting startup failure", error)
            statusValue.value = "Android denied video capture or overlay access"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        val window = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12+ blocks touches through untrusted full-screen
            // TYPE_APPLICATION_OVERLAY windows whose alpha is above .8.
            // Keep the live painter below the touch-obscuring threshold.
            // The brush SURFACE is still fully painted; window blending
            // allows underlying apps to remain interactive.
            alpha = if (isPainted) .78f else .78f
            // Unlike older builds, only SurfaceView is secure; the transparent
            // WindowManager overlay itself is intentionally NOT FLAG_SECURE.
            // Full-window security could redact the entire capture on Samsung.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val drawing: View = if (isPainted) {
            LivePaintSurface(applicationContext).also { livePaintSurface = it }
        } else {
            ChainsawView(applicationContext).also {
                it.configure(MusicConfiguration.read(this))
                it.configureVideo(lighting)
                it.setVideoColors(VideoEdgeColors.BLACK)
                view = it
            }
        }
        window.addView(drawing, params)
        windowManager = window
        overlay = drawing
    }

    private fun startCapture(capture: MediaProjection) {
        createReader()
        virtualDisplay = capture.createVirtualDisplay(
            "HALO display-color sampling", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, worker
        )
        statusValue.value = if (isPainted) "Waiting for a valid screen frame…" else
            "Cinematic edge lighting · accurate live screen colors"
    }

    private fun createReader() {
        val created = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        created.setOnImageAvailableListener({ source ->
            val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null }
            if (image != null) try {
                val now = SystemClock.uptimeMillis()
                if (now - lastSampleAt >= (if (isPainted) 50L else 33L) && !stopping) {
                    lastSampleAt = now
                    val plane = image.planes.firstOrNull()
                    if (plane != null) {
                        if (isPainted) {
                            // MediaProjection acquisition must be independent
                            // of brush planning and hardware Canvas drawing.
                            // ImageReader will stall if this callback is blocked
                            // for multiple rendered frames.
                            val bitmap = try {
                                PaintedFrameDecoder.decode(
                                    plane.buffer, image.width, image.height,
                                    plane.rowStride, plane.pixelStride)
                            } catch (error: RuntimeException) {
                                Log.e("HALO", "Screen capture decode failed", error)
                                null
                            }
                            framesSeen++
                            when (paintHealth.observe(bitmap != null, overlay != null)) {
                                PaintedCaptureAction.DISABLE_OVERLAY -> {
                                    bitmap?.recycle()
                                    statusValue.value =
                                        "Android temporarily blocked live screen capture"
                                    main.post { disablePaintOverlay() }
                                }
                                PaintedCaptureAction.CLEAR -> {
                                    bitmap?.recycle()
                                    paintDispatcher?.clear()
                                }
                                PaintedCaptureAction.KEEP -> bitmap?.recycle()
                                PaintedCaptureAction.PRESENT -> {
                                    if (bitmap != null) {
                                        lastUsablePaintFrameAt = SystemClock.uptimeMillis()
                                        if (overlay == null) {
                                            bitmap.recycle()
                                            main.post {
                                                if (!stopping && overlay == null) {
                                                    try { showOverlay() }
                                                    catch (error: Exception) {
                                                        Log.e("HALO", "Live overlay rejected", error)
                                                        statusValue.value = "Live overlay is not permitted"
                                                    }
                                                }
                                            }
                                        } else {
                                            paintDispatcher?.submit(bitmap) ?: bitmap.recycle()
                                        }
                                    }
                                }
                            }
                        } else {
                            val profile = lighting
                            val value = VideoColorSampler.sample(
                                plane.buffer, image.width, image.height,
                                plane.rowStride, plane.pixelStride, profile.sampleInset)
                            colors = if (framesSeen == 0) value else
                                VideoColorSampler.smooth(colors, value, profile.response)
                            framesSeen++
                            val current = colors
                            main.post { if (!stopping) view?.setVideoColors(current) }
                            if (framesSeen == 15 && VideoColorSampler.isDark(current))
                                statusValue.value = "Captured picture is dark; protected videos may be unavailable"
                            else if (framesSeen > 0 && framesSeen % 40 == 0 && !VideoColorSampler.isDark(current))
                                statusValue.value = "Cinematic lighting · spatial colors following the scene"
                        }
                    }
                }
            } finally { image.close() }
        }, worker)
        reader = created
    }

    private fun resize(fullWidth: Int, fullHeight: Int) {
        if (stopping || virtualDisplay == null || fullWidth < 16 || fullHeight < 16) return
        val divisor = if (isPainted) 3 else 6
        val newWidth = (fullWidth / divisor).coerceAtLeast(96)
        val newHeight = (fullHeight / divisor).coerceAtLeast(96)
        if (newWidth == width && newHeight == height) return
        width = newWidth; height = newHeight
        val old = reader
        old?.setOnImageAvailableListener(null, null)
        createReader()
        virtualDisplay?.resize(width, height, densityDpi)
        virtualDisplay?.setSurface(reader!!.surface)
        old?.close()
        framesSeen = 0
        blankPaintFrames = 0
        if (isPainted) {
            paintHealth.reset()
            lastUsablePaintFrameAt = SystemClock.uptimeMillis()
            paintDispatcher?.clear()
        }
    }

    /** The screen must never retain a stale painted screenshot. */
    private fun hidePaintLayer() {
        // SurfaceHolder drawing belongs to the capture worker. Its secure
        // contents are cleared there instead of mutating a View's bitmap on UI.
        paintDispatcher?.clear()
    }

    /** Pause only the unsafe visual overlay, retain MediaProjection status. */
    private fun disablePaintOverlay() {
        if (stopping) return
        hidePaintLayer()
        overlay?.let { attached ->
            try { windowManager?.removeViewImmediate(attached) }
            catch (error: RuntimeException) {
                Log.w("HALO", "Overlay removal failed", error)
            }
        }
        val previous = livePaintSurface
        livePaintSurface = null
        previous?.let { paintDispatcher?.retireSurface(it) }
        overlay = null
        windowManager = null
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTICE_ID, notification())
        } catch (error: RuntimeException) {
            Log.w("HALO", "Could not update capture notification", error)
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, VideoAmbilightService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(if (isPainted) "HALO Painted Screen" else "HALO Video Ambilight")
            .setContentText(
                when {
                    isPainted && paintHealth.overlayRejected ->
                        "Painting paused: Android blocked capture · tap to manage"
                    isPainted -> "Painting screen locally · tap to manage"
                    else -> "Matching screen colors locally · tap to manage"
                })
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause,
                "Stop screen capture", stop).build()).build()
    }

    override fun onDestroy() {
        stopping = true
        isRunning = false
        main.removeCallbacks(painterWatchdog)
        // Hide the entire painting surface before releasing capture, so
        // MediaProjection cannot leave stale pixels visible after stop.
        hidePaintLayer()
        if (!statusValue.value.startsWith("Android ended screen sharing") &&
            !statusValue.value.startsWith("Painting unavailable") &&
            !statusValue.value.startsWith("Painting overlay")) {
            statusValue.value = "Screen effects are off"
        }
        // Complete any in-flight secure canvas post before thread shutdown.
        try { virtualDisplay?.release() } catch (_: RuntimeException) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: RuntimeException) {}
        reader = null
        try { projection?.stop() } catch (_: RuntimeException) {}
        projection = null
        overlay?.let { try { windowManager?.removeViewImmediate(it) } catch (_: RuntimeException) {} }
        val previousSurface = livePaintSurface
        livePaintSurface = null
        paintDispatcher?.close(previousSurface)
        paintDispatcher = null
        view = null; overlay = null; windowManager = null
        isPainted = false
        thread.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
