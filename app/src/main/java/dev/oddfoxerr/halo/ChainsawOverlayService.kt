package dev.oddfoxerr.halo

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * Explicitly user-started floating music visualization.
 *
 * No AudioRecord, no MediaProjection, no uploads and no boot auto-start. Android's Visualizer
 * supplies a low-quality FFT of the output mix when the device and player permit it.
 * The microphone foreground-service category is required for while-in-use audio permission
 * continuity; HALO never opens the microphone input itself.
 */
class ChainsawOverlayService : Service() {
    companion object {
        const val ACTION_START = "dev.oddfoxerr.halo.action.START_CHAINSAW_OVERLAY"
        const val ACTION_STOP = "dev.oddfoxerr.halo.action.STOP_CHAINSAW_OVERLAY"
        private const val CHANNEL = "halo_chainsaw_live"
        private const val NOTIFICATION_ID = 701
        @Volatile var isRunning = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var visualizer: Visualizer? = null
    private var overlay: ChainsawView? = null
    private var windowManager: WindowManager? = null
    private var fft = ByteArray(0)
    private var previousFrame = 0L
    private var lastBassSignal = 0L
    private var consecutiveCaptureErrors = 0
    private var sensitivity = 1.5f
    private var chainRate = 1f
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    private val frame = object : Runnable {
        override fun run() {
            val view = overlay ?: return
            val source = visualizer ?: return
            if (!Settings.canDrawOverlays(this@ChainsawOverlayService)) {
                stopSelf()
                return
            }

            val interactive = power.isInteractive && !keyguard.isKeyguardLocked
            if (!interactive) {
                view.visibility = View.INVISIBLE
                previousFrame = SystemClock.elapsedRealtime()
                handler.postDelayed(this, 300L)
                return
            }

            view.visibility = View.VISIBLE
            val now = SystemClock.elapsedRealtime()
            val bass = try {
                if (source.getFft(fft) == Visualizer.SUCCESS) {
                    consecutiveCaptureErrors = 0
                    ChainDynamics.bassEnergy(fft, source.samplingRate)
                } else {
                    consecutiveCaptureErrors++
                    0f
                }
            } catch (_: RuntimeException) {
                consecutiveCaptureErrors++
                0f
            }
            if (consecutiveCaptureErrors >= 30) {
                stopSelf()
                return
            }

            if (bass > .004f) lastBassSignal = now
            val stableBass = if (now - lastBassSignal > 1000L) 0f else bass
            view.motor.step(stableBass, (now - previousFrame) / 1000f, sensitivity, chainRate)
            previousFrame = now
            view.invalidate()
            handler.postDelayed(this, 33L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY

        try {
            createNotificationChannel()
            val notification = createNotification()
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }

            val preferences = getSharedPreferences("chainsaw_music", MODE_PRIVATE)
            sensitivity = preferences.getFloat("sensitivity", 1.5f).coerceIn(.5f, 5f)
            chainRate = preferences.getFloat("rate", 1f).coerceIn(.25f, 3f)
            createOverlay(
                thickness = preferences.getFloat("thickness", 1f).coerceIn(.6f, 1.6f),
                glow = preferences.getFloat("glow", .6f).coerceIn(0f, 1f)
            )
            val source = Visualizer(0)
            visualizer = source
            source.captureSize = Visualizer.getCaptureSizeRange().last()
            source.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            fft = ByteArray(source.captureSize)
            check(source.setEnabled(true) == Visualizer.SUCCESS) { "Visualizer unavailable" }
            overlay?.motor?.reset()
            previousFrame = SystemClock.elapsedRealtime()
            lastBassSignal = previousFrame
            isRunning = true
            handler.post(frame)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createOverlay(thickness: Float, glow: Float) {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Android 12+ imposes obscuring-opacity limits on untrusted touch-pass-through
            // overlays. Keep this full-screen transparent window below that limit.
            alpha = .60f
        }
        val view = ChainsawView(applicationContext).apply {
            this.thickness = thickness
            this.glow = glow
            visibility = if (power.isInteractive && !keyguard.isKeyguardLocked) View.VISIBLE else View.INVISIBLE
        }
        manager.addView(view, params)
        windowManager = manager
        overlay = view
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "HALO floating music edge", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Visible while the user-started chainsaw edge is analyzing music"
                setShowBadge(false)
            }
        )
    }

    private fun createNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ChainsawOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ChainsawActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("HALO Chainsaw Edge")
            .setContentText("Visualizing music when Android permits audio access")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "Stop edge", stop
            ).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(frame)
        visualizer?.let { source ->
            try { source.enabled = false } catch (_: Exception) { }
            try { source.release() } catch (_: Exception) { }
        }
        visualizer = null
        overlay?.let { view ->
            try { windowManager?.removeViewImmediate(view) } catch (_: Exception) { }
        }
        overlay = null
        windowManager = null
        isRunning = false
        super.onDestroy()
    }
}
