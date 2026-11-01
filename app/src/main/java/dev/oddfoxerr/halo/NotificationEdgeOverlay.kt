package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

/**
 * Notifications and music share ChainsawView / ShaderEdgeEngine.
 * There is deliberately no separate notification Canvas renderer or character window.
 * A notification generates a synthetic audio-energy envelope without RECORD_AUDIO permission.
 */
object NotificationEdgeOverlay {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var view: ChainsawView? = null
    private var generation = 0L
    private var persistent = false
    val isVisible: Boolean get() = view != null
    val isAnimating: Boolean get() = !persistent && view?.isNotificationPlaying == true

    fun show(context: Context, color: Long, duration: Long, keepUntilDismissed: Boolean, store: ProfileStore) {
        if (!Settings.canDrawOverlays(context) || VideoAmbilightService.isRunning) return
        val drawing = ensure(context) ?: return
        generation++
        val current = generation
        persistent = keepUntilDismissed
        drawing.configure(MusicConfiguration.read(context))
        drawing.configureNotch(store.notchSettings())
        drawing.playNotification(color.toInt(), duration, keepUntilDismissed)
        handler.postDelayed({
            if (current != generation) return@postDelayed
            if (!keepUntilDismissed) dismiss()
        }, duration.coerceIn(800L, 10_000L))
    }

    fun showStatic(context: Context, color: Long, store: ProfileStore) {
        if (VideoAmbilightService.isRunning) return
        if (isVisible) return
        val drawing = ensure(context) ?: return
        generation++
        persistent = true
        drawing.configure(MusicConfiguration.read(context))
        drawing.configureNotch(store.notchSettings())
        drawing.playNotification(color.toInt(), 3500L, true)
    }

    private fun ensure(context: Context): ChainsawView? {
        view?.let { return it }
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
            alpha = .78f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return try {
            ChainsawView(app).also {
                wm.addView(it, params)
                manager = wm
                view = it
            }
        } catch (_: RuntimeException) {
            view = null
            manager = null
            null
        }
    }

    fun dismiss() {
        generation++
        persistent = false
        view?.let { prior ->
            prior.stopNotification()
            try { manager?.removeViewImmediate(prior) } catch (_: RuntimeException) { }
        }
        view = null
        manager = null
    }
}
