package dev.oddfoxerr.halo

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.audiofx.Visualizer
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference

/** One capture owner on a worker looper; one latest frame, and an independent visibility clock. */
class MusicEdgeService : Service() {
    companion object {
        const val ACTION_START = "dev.oddfoxerr.halo.MUSIC_START"
        const val ACTION_STOP = "dev.oddfoxerr.halo.MUSIC_STOP"
        const val ACTION_REFRESH = "dev.oddfoxerr.halo.MUSIC_REFRESH"
        private const val CHANNEL = "halo_music_edge_v2"
        private const val NOTICE_ID = 709
        @Volatile var isRunning = false; private set
        private var current: MusicEdgeService? = null
        fun yieldForNotification(duration: Long) {
            current?.let {
                it.yieldUntil = SystemClock.elapsedRealtime() + duration.coerceIn(800L, 10000L) + 200L
                it.yielding = true
                it.view?.visibility = View.INVISIBLE
            }
        }
    }
    private data class AudioFrame(val bands: FloatArray, val bass: Float, val at: Long)
    private data class MediaInfo(val pkg: String? = null, val title: String = "", val color: Int? = null, val permission: Boolean = false)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private val pending = AtomicReference<AudioFrame?>(null)
    private val media = AtomicReference(MediaInfo())
    @Volatile private var closing = false
    @Volatile private var enabledCapture = false
    @Volatile private var config = MusicConfiguration()
    private var visualizer: Visualizer? = null // worker-owned
    private var view: ChainsawView? = null
    private var manager: WindowManager? = null
    private var lastCapture = 0L
    private var lastAudible = 0L
    private var yieldUntil = 0L
    private var yielding = false
    private var receiverRegistered = false
    private var failed = false
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val store by lazy { ProfileStore(this) }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) view?.visibility = View.INVISIBLE
            main.removeCallbacks(tick); main.post(tick)
        }
    }
    private val captureListener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(v: Visualizer?, bytes: ByteArray?, rate: Int) = Unit
        override fun onFftDataCapture(v: Visualizer?, bytes: ByteArray?, rate: Int) {
            if (closing || !enabledCapture || bytes == null) return
            pending.set(AudioFrame(EdgeSpectrum.fromFft(bytes, rate), ChainDynamics.bassEnergy(bytes, rate), SystemClock.elapsedRealtime()))
        }
    }
    private val mediaPoll = object : Runnable {
        override fun run() {
            if (closing) return
            val settings = config
            val permitted = NotificationManagerCompat.getEnabledListenerPackages(this@MusicEdgeService).contains(packageName)
            var info = MediaInfo(permission = permitted)
            if (permitted) try {
                val controller = getSystemService(MediaSessionManager::class.java)
                    .getActiveSessions(ComponentName(this@MusicEdgeService, HaloNotificationListener::class.java))
                    .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING &&
                        (settings.allowedSources.isEmpty() || it.packageName in settings.allowedSources) }
                if (controller != null) {
                    val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(controller.packageName, 0)).toString() }
                        catch (_: Exception) { controller.packageName }
                    info = MediaInfo(controller.packageName, label,
                        if (settings.colorSource == EdgeColorSource.ALBUM_ART) MediaArtworkColor.fromMetadata(controller.metadata, settings.color) else null, true)
                }
            } catch (_: RuntimeException) { }
            media.set(info)
            worker.postDelayed(this, 1000L)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (closing || !isRunning) return
            if (!Settings.canDrawOverlays(this@MusicEdgeService) ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                fail("Permission removed · enable audio and overlay access to restart")
                return
            }
            val now = SystemClock.elapsedRealtime()
            val settings = config
            val info = media.get()
            val sessionAllowed = MediaEdgePolicy.shouldShow(true, true, info.pkg, settings.onlyPlaying, settings.allowedSources)
            val quiet = settings.respectQuiet && store.isQuietNow()
            val awake = power.isInteractive && !keyguard.isKeyguardLocked
            val shouldCapture = awake && !quiet && sessionAllowed
            setCapture(shouldCapture)
            pending.getAndSet(null)?.let { sample ->
                lastCapture = sample.at
                if (sample.bands.any { it > .008f }) lastAudible = sample.at
                view?.updateSpectrum(sample.bands, sample.bass, settings.sensitivity, settings.speed)
            }
            val notification = now < yieldUntil || NotificationEdgeOverlay.isAnimating
            if (yielding && !notification) {
                NotificationEdgeOverlay.dismiss(); yielding = false
            }
            val reason = MusicVisibility.reason(awake, !keyguard.isKeyguardLocked, quiet, notification,
                sessionAllowed, lastCapture != 0L && now - lastCapture < 700L,
                lastAudible != 0L && now - lastAudible < 900L)
            view?.apply {
                edgeColor = info.color ?: settings.color
                visibility = if (reason == null) View.VISIBLE else View.INVISIBLE
            }
            val permissionNeeded = !info.permission && (settings.onlyPlaying || settings.allowedSources.isNotEmpty())
            MusicRuntime.publish(true, if (permissionNeeded) "Enable notification access to detect your music player"
                else reason ?: "Live music lighting", info.title)
            main.postDelayed(this, if (awake) 40L else 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("HALO-music-analysis").apply { start() }
        worker = Handler(thread.looper)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH && isRunning) { refresh(); return START_NOT_STICKY }
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        if (isRunning) { refresh(); return START_NOT_STICKY }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || !Settings.canDrawOverlays(this)) {
            fail("Audio and overlay permission are required"); return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "HALO Music", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTICE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTICE_ID, notification())
            current = this; isRunning = true
            MusicRuntime.publish(true, "Connecting to device audio…")
            val drawing = ChainsawView(applicationContext).apply { visibility = View.INVISIBLE }
            val wm = getSystemService(WindowManager::class.java)
            val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                // A non-touchable overlay must remain at or below Android's obscuring-opacity threshold.
                // Full-strength pixels are rendered by the view, not by stacking nearly invisible alpha.
                alpha = .78f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            wm.addView(drawing, params)
            view = drawing; manager = wm
            refresh()
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            worker.post {
                if (!closing) try {
                    val source = Visualizer(0)
                    visualizer = source
                    val range = Visualizer.getCaptureSizeRange()
                    check(source.setCaptureSize(1024.coerceIn(range.first(), range.last())) == Visualizer.SUCCESS)
                    check(source.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED) == Visualizer.SUCCESS)
                    check(source.setDataCaptureListener(captureListener, Visualizer.getMaxCaptureRate().coerceAtMost(25000), false, true) == Visualizer.SUCCESS)
                    // Capture starts only when visibility/media rules permit it.
                    if (enabledCapture) check(source.setEnabled(true) == Visualizer.SUCCESS)
                } catch (_: RuntimeException) { main.post { fail("Android could not open device audio · try another player and restart") } }
            }
            worker.post(mediaPoll)
            NotificationEdgeOverlay.dismiss(); PillHaloOverlay.dismiss()
            main.post(tick)
        } catch (_: Exception) { fail("Android could not start music lighting · check permissions") }
        return START_NOT_STICKY
    }
    private fun refresh() {
        config = MusicConfiguration.read(this)
        view?.configure(config)
    }
    private fun setCapture(enabled: Boolean) {
        if (enabled == enabledCapture) return
        enabledCapture = enabled
        if (!enabled) { pending.set(null); lastCapture = 0L; lastAudible = 0L; view?.clearSignal() }
        worker.post {
            if (!closing) try {
                visualizer?.let { check(it.setEnabled(enabledCapture) == Visualizer.SUCCESS) }
            } catch (_: RuntimeException) { main.post { fail("Audio analysis disconnected · restart music lighting") } }
        }
    }
    private fun fail(message: String) {
        if (closing) return
        failed = true
        view?.visibility = View.INVISIBLE
        MusicRuntime.publish(false, message)
        stopSelf()
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MusicEdgeActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MusicEdgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("HALO Music").setContentText("Screen-edge music lighting · tap to customize")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build()).build()
    }
    override fun onDestroy() {
        closing = true; enabledCapture = false; isRunning = false; current = null
        main.removeCallbacksAndMessages(null)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        view?.let { try { manager?.removeViewImmediate(it) } catch (_: RuntimeException) { } }; view = null
        pending.set(null)
        worker.removeCallbacksAndMessages(null)
        worker.post {
            try {
                visualizer?.let { source ->
                    // A disconnected engine may reject disable; release must still run.
                    try { source.enabled = false } catch (_: RuntimeException) { }
                    try { source.release() } catch (_: RuntimeException) { }
                }
            } finally {
                visualizer = null
                thread.quitSafely()
            }
        }
        if (!failed) MusicRuntime.publish(false, "Music lighting is off")
        stopForeground(STOP_FOREGROUND_REMOVE)
        HaloNotificationListener.requestPillRefresh()
        super.onDestroy()
    }
}
