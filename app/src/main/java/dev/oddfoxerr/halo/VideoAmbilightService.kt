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
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

/**
 * User-authorized, one-session MediaProjection capture for video-driven Ambilight.
 * Frames are sampled to sixteen RGB values, never recorded or transmitted.
 * Audio capture is not used. MediaProjection is released as soon as Stop is pressed
 * or the user revokes consent.
 */
class VideoAmbilightService : Service() {
    companion object {
        const val ACTION_START = "dev.oddfoxerr.halo.VIDEO_START"
        const val ACTION_STOP = "dev.oddfoxerr.halo.VIDEO_STOP"
        const val ACTION_REFRESH = "dev.oddfoxerr.halo.VIDEO_REFRESH"
        const val EXTRA_RESULT = "captureResult"
        const val EXTRA_DATA = "captureData"
        private const val NOTIFICATION_ID = 822
        private const val CHANNEL = "halo_video_ambilight"
        @Volatile var isRunning = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: VideoAmbilightView? = null
    private var windowManager: WindowManager? = null
    private var settings = VideoAmbilightSettings()
    private val smoother = VideoColorSmoother()
    private var closing = false
    private var frames = 0L
    private var lastFrame = 0L
    private var captureWidth = 0
    private var captureHeight = 0

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { if (!closing) stopSelf() }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0 && !closing) {
                worker.post { resizeCapture(width, height) }
            }
        }
    }

    private val frameListener = ImageReader.OnImageAvailableListener { input ->
        val image = try { input.acquireLatestImage() } catch (_: IllegalStateException) { null }
        if (image == null) return@OnImageAvailableListener
        try {
            if (closing) return@OnImageAvailableListener
            val now = SystemClock.elapsedRealtime()
            val limit = 1000L / settings.frameRate
            if (now-lastFrame < limit) return@OnImageAvailableListener
            lastFrame=now
            val plane=image.planes.firstOrNull() ?: return@OnImageAvailableListener
            val colors=VideoEdgeSampler.sample(
                plane.buffer,image.width,image.height,plane.rowStride,plane.pixelStride,
                settings.sampleDepth,settings.saturation
            )
            val smooth=smoother.update(colors,settings.smoothing)
            frames++
            main.post {
                if (!closing) {
                    overlay?.updateColors(smooth)
                    if (frames % 25L == 1L) VideoAmbientRuntime.publish(true,
                        "Live video colors • ${settings.frameRate} fps limit",frames)
                }
            }
        } catch (error: RuntimeException) {
            Log.w("HALO","Could not sample video frame",error)
        } finally {
            image.close()
        }
    }

    override fun onCreate() {
        super.onCreate()
        workerThread=HandlerThread("HALO-video-color-sampler").apply { start() }
        worker=Handler(workerThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_REFRESH -> {
                settings=VideoAmbilightSettings.read(this)
                overlay?.configure(settings)
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        if (isRunning) return START_NOT_STICKY
        val result=intent.getIntExtra(EXTRA_RESULT,0)
        val captureData = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA,Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        if (result != android.app.Activity.RESULT_OK || captureData == null ||
            !Settings.canDrawOverlays(this)) {
            VideoAmbientRuntime.publish(false,"Allow overlay access and approve screen sharing")
            stopSelf()
            return START_NOT_STICKY
        }
        settings=VideoAmbilightSettings.read(this)
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL,"HALO Video Ambilight",NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) })
            startForeground(NOTIFICATION_ID,notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            // A new MediaProjection object and virtual display are acquired exactly once
            // per user-approved capture session as required by Android 14+.
            val manager=getSystemService(MediaProjectionManager::class.java)
            projection=manager.getMediaProjection(result,captureData)
                ?: error("Android did not provide screen capture")
            projection?.registerCallback(callback,main)
            val screen=getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val width=screen.width().coerceAtLeast(32)
            val height=screen.height().coerceAtLeast(32)
            addOverlay()
            val size=scaled(width,height)
            createReader(size.first,size.second)
            val displayReader=reader ?: error("Capture reader was not created")
            virtualDisplay=projection?.createVirtualDisplay(
                "HALO Ambilight",size.first,size.second,
                resources.displayMetrics.densityDpi.coerceAtLeast(1),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                displayReader.surface,null,worker
            )
            check(virtualDisplay != null) { "Android did not create a virtual display" }
            isRunning=true
            VideoAmbientRuntime.publish(true,"Sampling your screen locally")
            // Video colors take precedence over the FFT/music layer.
            stopService(Intent(this,MusicEdgeService::class.java))
            NotificationEdgeOverlay.dismiss()
        } catch (error: Exception) {
            Log.e("HALO","Video Ambilight startup failed",error)
            VideoAmbientRuntime.publish(false,"Capture unavailable: ${error.message?.take(110) ?: "Android restriction"}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun scaled(width: Int,height: Int): Pair<Int,Int> {
        val scale=320f / max(width,height)
        return max(32,(width*scale).toInt()) to max(32,(height*scale).toInt())
    }

    private fun createReader(width: Int,height: Int) {
        captureWidth=width
        captureHeight=height
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
            .also { it.setOnImageAvailableListener(frameListener,worker) }
    }

    /** Android 14+ callback reports capture-surface resize, including app capture and rotation. */
    private fun resizeCapture(width: Int,height: Int) {
        if (closing) return
        val dimensions=scaled(width,height)
        if (dimensions.first==captureWidth && dimensions.second==captureHeight) return
        val next=ImageReader.newInstance(dimensions.first,dimensions.second,PixelFormat.RGBA_8888,2)
        next.setOnImageAvailableListener(frameListener,worker)
        try {
            virtualDisplay?.resize(dimensions.first,dimensions.second,
                resources.displayMetrics.densityDpi.coerceAtLeast(1))
            virtualDisplay?.surface=next.surface
            val previous=reader
            reader=next
            captureWidth=dimensions.first
            captureHeight=dimensions.second
            previous?.setOnImageAvailableListener(null,null)
            previous?.close()
        } catch (error: RuntimeException) {
            next.close()
            Log.w("HALO","Video capture resize rejected",error)
        }
    }

    private fun addOverlay() {
        val wm=getSystemService(WindowManager::class.java)
        val view=VideoAmbilightView(applicationContext)
        view.configure(settings)
        val params=WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity=Gravity.TOP or Gravity.START
            alpha=.78f
            layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(view,params)
        overlay=view
        windowManager=wm
    }

    private fun notification(): Notification {
        val open=PendingIntent.getActivity(this,21,Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,22,
            Intent(this,VideoAmbilightService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("HALO Video Ambilight")
            .setContentText("Screen color sampling active • tap Stop to end sharing")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause,"Stop",stop).build())
            .build()
    }

    override fun onDestroy() {
        closing=true
        isRunning=false
        main.removeCallbacksAndMessages(null)
        overlay?.let { view ->
            try { windowManager?.removeViewImmediate(view) } catch (_: RuntimeException) { }
        }
        overlay=null
        windowManager=null
        try { virtualDisplay?.release() } catch (_: RuntimeException) { }
        virtualDisplay=null
        reader?.setOnImageAvailableListener(null,null)
        reader?.close()
        reader=null
        try { projection?.unregisterCallback(callback) } catch (_: RuntimeException) { }
        try { projection?.stop() } catch (_: RuntimeException) { }
        projection=null
        workerThread.quitSafely()
        VideoAmbientRuntime.publish(false,"Video Ambilight stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
