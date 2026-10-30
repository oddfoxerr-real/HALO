package dev.oddfoxerr.halo

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.audiofx.Visualizer
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * User-started Muviz-style music edge. Audio is not recorded: this uses Android's
 * low-resolution Visualizer FFT callback, not AudioRecord or MediaProjection.
 * Android playback capture availability varies by player and device.
 */
class MusicEdgeService : Service() {
    companion object {
        const val ACTION_START = "dev.oddfoxerr.halo.MUSIC_START"
        const val ACTION_STOP = "dev.oddfoxerr.halo.MUSIC_STOP"
        const val ACTION_REFRESH = "dev.oddfoxerr.halo.MUSIC_REFRESH"
        private const val CHANNEL = "halo_music_edge_v2"
        private const val NOTICE_ID = 709
        @Volatile var isRunning: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var edgeView: ChainsawView? = null
    private var visualizer: Visualizer? = null
    private var previousCaptureMs = 0L
    private var previousSessionMs = 0L
    private var currentPlayingPackage: String? = null
    private var liveColor = android.graphics.Color.rgb(170, 91, 255)
    private var defaultColor = liveColor
    private var colorSource = EdgeColorSource.MANUAL
    private var onlyWhilePlaying = false
    private var allowedSources: Set<String> = emptySet()
    private val handler = Handler(Looper.getMainLooper())
    private val frameQueued = AtomicBoolean(false)
    private val pendingFrame = AtomicReference<Pair<FloatArray, Float>?>(null)
    private val mediaManager by lazy { getSystemService(MediaSessionManager::class.java) }
    private var sensitivity = 1.5f
    private var rate = 1f
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    private val audioCapture = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
            if (fft == null || !isRunning) return
            val spectrum = EdgeSpectrum.fromFft(fft, samplingRate)
            pendingFrame.set(spectrum to EdgeSpectrum.bass(spectrum))
            if (!frameQueued.compareAndSet(false, true)) return
            handler.post {
                frameQueued.set(false)
                val (spectrum, bass) = pendingFrame.getAndSet(null) ?: return@post
                if (!isRunning) return@post
                val view = edgeView ?: return@post
                if (!Settings.canDrawOverlays(this@MusicEdgeService)) {
                    stopSelf()
                    return@post
                }
                val now = SystemClock.elapsedRealtime()
                if (now - previousSessionMs > 1600L) {
                    refreshMediaSession()
                    previousSessionMs = now
                }
                val shouldShow = MediaEdgePolicy.shouldShow(
                    power.isInteractive, !keyguard.isKeyguardLocked,
                    currentPlayingPackage, onlyWhilePlaying, allowedSources
                )
                if (!shouldShow) {
                    view.visibility = View.INVISIBLE
                    previousCaptureMs = now
                    return@post
                }
                view.visibility = View.VISIBLE
                view.updateSpectrum(
                    spectrum, bass, (now - previousCaptureMs) / 1000f, sensitivity, rate
                )
                previousCaptureMs = now
            }
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("chainsaw_music", MODE_PRIVATE)
        sensitivity = prefs.getFloat("sensitivity", 1.5f).coerceIn(.5f, 5f)
        rate = prefs.getFloat("rate", 1f).coerceIn(.25f, 3f)
        defaultColor = prefs.getInt("edge_color", android.graphics.Color.rgb(170, 91, 255))
        colorSource = MediaEdgePolicy.colorSource(prefs.getString("color_source", "MANUAL"))
        onlyWhilePlaying = prefs.getBoolean("only_while_playing", false)
        allowedSources = prefs.getStringSet("allowed_sources", emptySet())?.toSet().orEmpty()
        edgeView?.apply {
            mode = EdgeSpectrum.mode(prefs.getString("edge_mode", "WAVE"))
            thickness = prefs.getFloat("thickness", 1f).coerceIn(.6f, 1.6f)
            glow = prefs.getFloat("glow", .6f).coerceIn(0f, 1f)
            edgeColor = defaultColor
        }
        refreshMediaSession()
    }

    private fun refreshMediaSession() {
        var activePackage: String? = null
        var albumColor = defaultColor
        if (NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            try {
                val listener = ComponentName(this, HaloNotificationListener::class.java)
                val controller = mediaManager.getActiveSessions(listener).firstOrNull {
                    it.playbackState?.state == PlaybackState.STATE_PLAYING
                }
                activePackage = controller?.packageName
                if (colorSource == EdgeColorSource.ALBUM_ART) {
                    albumColor = MediaArtworkColor.fromMetadata(controller?.metadata, defaultColor)
                }
            } catch (_: SecurityException) {
                // A revoked notification-listener grant must not crash the overlay.
            } catch (_: RuntimeException) {
                // Media controllers can die while the player is changing tracks.
            }
        }
        currentPlayingPackage = activePackage
        liveColor = albumColor
        edgeView?.edgeColor = liveColor
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH && isRunning) {
            loadPreferences()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) {
            loadPreferences()
            return START_NOT_STICKY
        }
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
            loadPreferences()
            val view = ChainsawView(applicationContext).apply {
                mode = EdgeSpectrum.mode(prefs.getString("edge_mode", "WAVE"))
                edgeColor = prefs.getInt("edge_color", android.graphics.Color.rgb(170, 91, 255))
                thickness = prefs.getFloat("thickness", 1f).coerceIn(.6f, 1.6f)
                glow = prefs.getFloat("glow", .6f).coerceIn(0f, 1f)
                visibility = View.INVISIBLE
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
            previousSessionMs = 0L
            isRunning = true
            loadPreferences()
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
        handler.removeCallbacksAndMessages(null)
        pendingFrame.set(null)
        frameQueued.set(false)
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
