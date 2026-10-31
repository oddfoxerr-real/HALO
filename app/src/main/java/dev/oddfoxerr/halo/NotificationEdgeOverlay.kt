package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Notification effects are now rendered by the exact same visualizer as music.
 *
 * No legacy CharacterView, BorderView, or notification-only effect renderer is
 * involved in real notification delivery. A notification provides a short
 * procedural envelope instead of capturing device audio.
 */
object NotificationEdgeOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var visualizer: ChainsawView? = null
    private var generation = 0L
    private var persistent = false
    private var currentColor = 0L

    val isVisible: Boolean get() = visualizer != null
    val isAnimating: Boolean get() = visualizer?.notificationIsPlaying == true

    private fun configure(view: ChainsawView, color: Long, store: ProfileStore) {
        val music = MusicConfiguration.read(view.context)
        view.configure(music.copy(
            color = color.toInt(),
            accent = music.accent,
            thickness = store.notificationBorderThickness.coerceIn(.6f, 3f),
            glow = store.notificationBorderGlow
        ))
        view.configureNotch(store.notchSettings())
    }

    fun show(
        context: Context, color: Long, duration: Long,
        persistent: Boolean, store: ProfileStore
    ) {
        if (!Settings.canDrawOverlays(context)) return
        val view = ensure(context) ?: return
        generation++
        currentColor = color
        this.persistent = false
        configure(view, color, store)
        view.showNotificationSignal(duration)
        val thisGeneration = generation
        main.postDelayed({
            if (generation != thisGeneration || visualizer !== view) return@postDelayed
            if (persistent) {
                this.persistent = true
                view.holdNotificationSignal()
            } else dismiss()
        }, duration.coerceIn(800L, 10000L))
    }

    /** Reuses the running animation; polling active notifications must not restart it. */
    fun showStatic(context: Context, color: Long, store: ProfileStore) {
        if (isAnimating) return
        val view = ensure(context) ?: return
        if (persistent && color == currentColor) return
        generation++
        currentColor = color
        persistent = true
        configure(view, color, store)
        view.showNotificationSignal(3500L, repeating = true)
    }

    private fun ensure(context: Context): ChainsawView? {
        visualizer?.let { return it }
        if (!Settings.canDrawOverlays(context)) return null
        val app = context.applicationContext
        val window = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
            // An independently drawn non-touch overlay, not compounded with character windows.
            alpha = .78f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return try {
            ChainsawView(app).also {
                window.addView(it, params)
                manager = window
                visualizer = it
            }
        } catch (_: Exception) {
            null
        }
    }

    fun dismiss() {
        generation++
        persistent = false
        currentColor = 0L
        visualizer?.let {
            it.stopNotificationSignal()
            try { manager?.removeViewImmediate(it) } catch (_: RuntimeException) { }
        }
        visualizer = null
        manager = null
    }
}
