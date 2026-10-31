package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Single edge-constrained non-touch overlay; does not attempt to render over native AOD. */
object HaloOverlay {
    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var view: CharacterView? = null
    private var params: WindowManager.LayoutParams? = null
    private var generation = 0L

    fun isAnimating(): Boolean = view?.isAnimating() == true

    fun showNotification(context: Context, profile: CharacterProfile, persistent: Boolean) {
        if (!Settings.canDrawOverlays(context)) return
        ensureView(context, profile.side)
        generation++
        val thisGeneration = generation
        view?.play(profile, persistent && profile.persistent)
        handler.postDelayed({
            if (thisGeneration == generation && view?.isAnimating() == false && !(persistent && profile.persistent)) dismiss()
        }, profile.durationMs + 160L)
    }

    fun showIndicator(context: Context, profile: CharacterProfile) {
        if (!Settings.canDrawOverlays(context)) return
        ensureView(context, profile.side)
        generation++
        view?.indicate(profile)
    }

    private fun ensureView(context: Context, side: EdgeSide) {
        val manager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val existing = view
        if (existing == null || params?.gravity != (Gravity.TOP or (if (side == EdgeSide.RIGHT) Gravity.RIGHT else Gravity.LEFT))) {
            dismiss()
            wm = manager
            val width = (context.resources.displayMetrics.density * 112).toInt()
            val lp = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or (if (side == EdgeSide.RIGHT) Gravity.RIGHT else Gravity.LEFT)
                // Android 12+ restricts pass-through gestures underneath untrusted overlays.
                // Keep the window narrow and below the platform opacity threshold.
                alpha = 0.40f
            }
            val drawing = CharacterView(context.applicationContext, true)
            try {
                manager.addView(drawing, lp)
                view = drawing
                params = lp
            } catch (_: Exception) {
                drawing.stop()
                view = null
                params = null
                wm = null
            }
        }
    }

    fun dismiss() {
        generation++
        view?.stop()
        view?.let { previous -> try { wm?.removeViewImmediate(previous) } catch (_: Exception) {} }
        view = null
        params = null
        wm = null
    }
}
