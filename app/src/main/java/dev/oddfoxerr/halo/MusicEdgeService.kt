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
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * User-started Muviz-style music edge. Audio is not recorded: this uses Android's
 * low-resolution Visualizer FFT callback, not AudioRecord or MediaProjection.
 * Android playback capture availability varies by player and device.
 */
class MusicEdgeService : Service() {
    companion object {
        const val ACTION_START = "dev.oddfoxerr.halo.MUSIC_START"
        const val ACTION_STOP = "dev.oddfoxerr.halo.MUSIC_STOP"
        private const val CHANNEL = "halo_music_edge_v2"
        private const val NOTICE_ID = 709
        @Volatile var isRunning: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var edgeView: ChainsawView? = null
    private var visualizer: Visualizer? = null
    private var previousCaptureMs = 0L
    private var sensitivity = 1.5f
    private var rate = 1f
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    private val audioCapture = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
            val view = edgeView ?: return
            if (fft == null || !isRunning) return
            if (!Settings.canDrawOverlays(this@MusicEdgeService)) {
                stopSelf()
                return
            }
            val allowed = ChainsawOverlayPolicy.mayRender(power.isInteractive, keyguard.isKeyguardLocked)
            if (!allowed) {
                view.visibility = View.INVISIBLE
                previousCaptureMs = SystemClock.elapsedRealtime()
                return
            }
            view.visibility = View.VISIBLE
            val now = SystemClock.elapsedRealtime()
            val spectrum = EdgeSpectrum.fromFft(fft, samplingRate)
            view.updateSpectrum(spectrum, EdgeSpectrum.bass(spectrum),
                (now - previousCaptureMs) / 1000f, sensitivity, rate)
            previousCaptureMs = now
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            createChannel()
            val notification = createNotification()
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTICE_ID, notification)
            }

            val prefs = getSharedPreferences("chainsaw_music", MODE_PRIVATE)
            sensitivity = prefs.getFloat("sensitivity", 1.5f).coerceIn(.5f, 5f)
            rate = prefs.getFloat("rate", 1f).coerceIn(.25f, 3f)
            val view = ChainsawView(applicationContext).apply {
                mode = EdgeSpectrum.mode(prefs.getString("edge_mode", "WAVE"))
                edgeColor = prefs.getInt("edge_color", android.graphics.Color.rgb(170, 91, 255))
                thickness = prefs.getFloat("thickness", 1f).coerceIn(.6f, 1.6f)
                glow = prefs.getFloat("glow", .6f).coerceIn(0f, 1f)
                visibility = if (ChainsawOverlayPolicy.mayRender(power.isInteractive, keyguard.isKeyguardLocked))
                    View.VISIBLE else View.INVISIBLE
            }
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                // Keep safely below Android 12+ obscuring-opacity threshold for pass-through touches.
                alpha = .40f
            }
            manager.addView(view, lp)
            edgeView = view
            windowManager = manager

            val source = Visualizer(0)
            visualizer = source
            source.captureSize = Visualizer.getCaptureSizeRange().first().coerceAtLeast(256)
            source.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            val captureRate = Visualizer.getMaxCaptureRate().coerceAtMost(25000).coerceAtLeast(1000)
            check(source.setDataCaptureListener(audioCapture, captureRate, false, true) == Visualizer.SUCCESS) {
                "FFT callbacks are unavailable"
            }
            check(source.setEnabled(true) == Visualizer.SUCCESS) { "Unable to start music visualizer" }
            previousCaptureMs = SystemClock.elapsedRealtime()
            isRunning = true
            HaloOverlay.dismiss()
            PillHaloOverlay.dismiss()
            NotificationEdgeOverlay.dismiss()
            HaloNotificationListener.requestPillRefresh()
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "HALO music edge", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Active only when you start the music visualizer"
                setShowBadge(false)
            }
        )
    }

    private fun createNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MusicEdgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MusicEdgeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("HALO Music Edge")
            .setContentText("Reactive music lighting • tap to edit")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "Stop visualizer", stop
            ).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        visualizer?.let {
            try { it.setDataCaptureListener(null, 0, false, false) } catch (_: Exception) { }
            try { it.enabled = false } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        visualizer = null
        edgeView?.let { view ->
            try { windowManager?.removeViewImmediate(view) } catch (_: Exception) { }
        }
        edgeView = null
        windowManager = null
        HaloNotificationListener.requestPillRefresh()
        super.onDestroy()
    }
}
