package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Shared GPU-only visualizer. Notifications and music use the same shader and geometry. */
class ChainsawView(context: Context) : View(context) {
    private val motion = MusicEnvelope()
    private val engine = ShaderEdgeEngine()
    private val notchRenderer = NotchLightRenderer()
    private val notificationBands = FloatArray(EdgeSpectrum.BAND_COUNT)
    private val activeSides = Path()
    private val bounds = RectF()
    private val densityPx = resources.displayMetrics.density
    private val frames = MusicRenderLoop()
    private val choreographer by lazy { Choreographer.getInstance() }
    private var geometryKey = ""
    private var lastFrame = 0L
    private var speed = 1f
    private var notificationStartedAt = 0L
    private var notificationEndsAt = 0L
    private var notificationPersistent = false
    private var notchSettings = ProfileStore(context).notchSettings()

    var mode = EdgeMode.LIQUID
    var gradient = EdgeGradient.SMOOTH
    var dotDensity = 1f
    var dotSize = 1f
    var edgeColor = 0xFF9A7BFF.toInt()
    var accentColor = 0xFF52E0CB.toInt()
    var thickness = 1f
    var brightness = 1f
    var glow = .75f
    var spread = 1f
    var leftInset = 4f
    var rightInset = 4f
    var topInset = 4f
    var bottomInset = 4f
    var cornerRadius = 32f
    var drawTop = true
    var drawBottom = true
    var drawLeft = true
    var drawRight = true

    val isNotificationPlaying: Boolean get() =
        notificationEndsAt > SystemClock.uptimeMillis() || (notificationPersistent && notificationStartedAt != 0L)
    val rendererStatus: String get() = when {
        !isHardwareAccelerated -> "GPU unavailable (hardware acceleration disabled)"
        else -> engine.status
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!canRender()) { stopFrames(); return }
            if (frames.onFrame(frameTimeNanos / 1_000_000L)) invalidate()
            else choreographer.postFrameCallback(this)
        }
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun configure(settings: MusicConfiguration) {
        mode = settings.mode
        gradient = settings.gradient
        dotDensity = settings.dotDensity
        dotSize = settings.dotSize
        edgeColor = settings.color
        accentColor = settings.accent
        thickness = settings.thickness
        brightness = settings.brightness
        glow = settings.glow
        spread = settings.spread
        speed = settings.speed
        leftInset = settings.leftInset
        rightInset = settings.rightInset
        topInset = settings.topInset
        bottomInset = settings.bottomInset
        cornerRadius = settings.corner
        drawTop = settings.drawTop
        drawBottom = settings.drawBottom
        drawLeft = settings.drawLeft
        drawRight = settings.drawRight
        notchSettings = ProfileStore(context).notchSettings()
        geometryKey = ""
        invalidate()
    }

    fun configureNotch(settings: NotchSettings) {
        notchSettings = settings.normalized()
        invalidate()
    }

    fun playNotification(color: Int, duration: Long, persistent: Boolean) {
        notificationStartedAt = SystemClock.uptimeMillis()
        notificationEndsAt = notificationStartedAt + duration.coerceIn(800L, 10_000L)
        notificationPersistent = persistent
        edgeColor = color
        motion.clear()
        requestFrame()
        invalidate()
    }

    fun stopNotification() {
        notificationStartedAt = 0L
        notificationEndsAt = 0L
        notificationPersistent = false
        clearSignal()
    }

    fun clearSignal() {
        motion.clear()
        stopFrames()
        invalidate()
    }

    fun updateSpectrum(bands: FloatArray, bass: Float, sensitivity: Float, rate: Float) {
        speed = rate
        motion.accept(bands, bass, SystemClock.uptimeMillis(), sensitivity)
        requestFrame()
    }

    private fun canRender() = isShown && isAttachedToWindow && windowVisibility == VISIBLE
    private fun requestFrame() {
        if (canRender() && frames.request()) choreographer.postFrameCallback(frameCallback)
    }
    private fun stopFrames() {
        choreographer.removeFrameCallback(frameCallback)
        frames.reset()
        lastFrame = 0L
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        stopFrames()
        if (visibility == VISIBLE) requestFrame()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        stopFrames()
        if (visibility == VISIBLE) requestFrame()
    }

    override fun onDetachedFromWindow() {
        stopNotification()
        super.onDetachedFromWindow()
    }

    private fun prepareGeometry() {
        val key = "$width:$height:$leftInset:$rightInset:$topInset:$bottomInset:$cornerRadius:$thickness:$spread:$drawTop:$drawBottom:$drawLeft:$drawRight"
        if (key == geometryKey) return
        geometryKey = key
        bounds.set(leftInset * densityPx, topInset * densityPx,
            width - rightInset * densityPx, height - bottomInset * densityPx)
        activeSides.reset()
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        val reach = (65f + 42f * thickness * spread) * densityPx
        if (drawTop) activeSides.addRect(0f, 0f, width.toFloat(),
            min(height.toFloat(), bounds.top + reach), Path.Direction.CW)
        if (drawBottom) activeSides.addRect(0f, max(0f, bounds.bottom - reach),
            width.toFloat(), height.toFloat(), Path.Direction.CW)
        if (drawLeft) activeSides.addRect(0f, 0f,
            min(width.toFloat(), bounds.left + reach), height.toFloat(), Path.Direction.CW)
        if (drawRight) activeSides.addRect(max(0f, bounds.right - reach), 0f,
            width.toFloat(), height.toFloat(), Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 1f / 60f else (now - lastFrame).coerceIn(0L, 50L) / 1000f
        lastFrame = now
        val notificationActive = isNotificationPlaying
        if (notificationActive) {
            val seconds = (now - notificationStartedAt).coerceAtLeast(0L) / 1000f
            val pulse = (.39f + .21f * sin(seconds * 6.7f)).coerceIn(.08f, .95f)
            for (index in notificationBands.indices) {
                notificationBands[index] = (.10f + pulse * (.55f +
                    .45f * sin(seconds * (2.4f + index % 4 * .16f) + index * .55f))).coerceIn(.03f, .90f)
            }
            motion.accept(notificationBands, pulse, now, 1f)
        }
        val moving = motion.advance(now, dt, speed)
        prepareGeometry()
        if (bounds.width() > 0f && bounds.height() > 0f &&
            (drawTop || drawBottom || drawLeft || drawRight)) {
            canvas.save()
            canvas.clipPath(activeSides)
            engine.render(canvas, width, height, bounds,
                min(cornerRadius * densityPx, min(bounds.width(), bounds.height()) / 2f),
                mode, gradient, motion.bands, motion.pulse, motion.phase, now / 1000f,
                densityPx * thickness, spread, glow, brightness, dotDensity, dotSize,
                edgeColor, accentColor)
            canvas.restore()
        }
        notchRenderer.draw(canvas, this, notchSettings, edgeColor, accentColor,
            brightness, glow, (motion.pulse + motion.bands.maxOrNull()!!.coerceIn(0f, 1f) * .35f),
            motion.phase)
        if (moving || notificationActive) requestFrame()
    }
}
