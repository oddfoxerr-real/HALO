package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * One GPU-only visualizer surface for music and notification events.
 * No Canvas animation legacy path, layered raster effects or hand-drawn fallback.
 * Requires Android 13/AGSL; RuntimeShader errors remain visible in Studio diagnostics.
 */
class EdgeVisualizerView(context: Context) : View(context) {
    private val motion = MusicEnvelope()
    private val engine = ShaderEdgeEngine()
    private val notchRenderer = NotchLightRenderer()
    private val density = resources.displayMetrics.density
    private val bounds = RectF()
    private val activeSides = Path()
    private val notificationBands = FloatArray(EdgeSpectrum.BAND_COUNT)
    private val loop = MusicRenderLoop()
    private val choreographer by lazy { Choreographer.getInstance() }
    private var lastFrame = 0L
    private var geometryKey = ""
    private var speed = 1f
    private var noticeStartAt = 0L
    private var noticeEndAt = 0L
    private var noticePersistent = false
    private var gpuDidRender = false
    private var notchSettings = ProfileStore(context).notchSettings()

    val isNotificationPlaying: Boolean get() = noticeEndAt > SystemClock.uptimeMillis()
    val rendererStatus: String get() = when {
        !isHardwareAccelerated -> "GPU unavailable: hardware acceleration disabled"
        engine.hasFailed -> "GPU shader error — inspect Android logs"
        gpuDidRender -> "AGSL GPU active"
        else -> "GPU preview initializing"
    }

    var mode = EdgeMode.LIQUID
    var edgeColor = 0xFF9A7BFF.toInt()
    var accentColor = 0xFF52E0CB.toInt()
    var thickness = 1f
    var brightness = 1f
    var glow = .75f
    var spread = 1f
    var dotSize = 1f
    var dotDensity = 1f
    var cornerRadius = 32f
    var leftInset = 4f
    var rightInset = 4f
    var topInset = 4f
    var bottomInset = 4f
    var drawTop = true
    var drawBottom = true
    var drawLeft = true
    var drawRight = true

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!canRender()) { stopFrames(); return }
            if (loop.onFrame(frameTimeNanos / 1_000_000L)) invalidate()
            else choreographer.postFrameCallback(this)
        }
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun configure(config: MusicConfiguration) {
        mode = config.mode
        edgeColor = config.color
        accentColor = config.accent
        speed = config.speed
        thickness = config.thickness
        brightness = config.brightness
        glow = config.glow
        spread = config.spread
        dotSize = config.dotSize
        dotDensity = config.dotDensity
        cornerRadius = config.corner
        leftInset = config.leftInset
        rightInset = config.rightInset
        topInset = config.topInset
        bottomInset = config.bottomInset
        drawTop = config.drawTop
        drawBottom = config.drawBottom
        drawLeft = config.drawLeft
        drawRight = config.drawRight
        notchSettings = ProfileStore(context).notchSettings()
        invalidate()
    }

    fun configureNotch(value: NotchSettings) {
        notchSettings = value.normalized()
        invalidate()
    }

    fun playNotification(color: Int, duration: Long, persistent: Boolean) {
        noticeStartAt = SystemClock.uptimeMillis()
        noticeEndAt = noticeStartAt + duration.coerceIn(800L, 10_000L)
        noticePersistent = persistent
        edgeColor = color
        motion.clear()
        requestFrame()
        invalidate()
    }

    fun stopNotification() {
        noticeEndAt = 0L
        noticePersistent = false
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

    private fun canRender(): Boolean = isShown && isAttachedToWindow && windowVisibility == VISIBLE

    private fun requestFrame() {
        if (canRender() && loop.request()) choreographer.postFrameCallback(frame)
    }

    private fun stopFrames() {
        choreographer.removeFrameCallback(frame)
        loop.reset()
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
        clearSignal()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1000f).coerceIn(0f, .1f)
        lastFrame = now
        val noticePlaying = noticeEndAt > now || (noticePersistent && noticeStartAt > 0L)
        if (noticePlaying) {
            val elapsed = (now - noticeStartAt).coerceAtLeast(0L) / 1000f
            val beat = (.36f + .29f * sin(elapsed * 6.5f)).coerceIn(.08f, .9f)
            for (i in notificationBands.indices) {
                notificationBands[i] = (.12f + beat *
                    (.5f + .5f * sin(elapsed * (2.2f + i % 5 * .19f) + i * .77f))).coerceIn(.02f, .9f)
            }
            motion.accept(notificationBands, beat, now, 1f)
        }
        val moving = motion.advance(now, dt, speed)
        val key = "$width:$height:$leftInset:$rightInset:$topInset:$bottomInset:$cornerRadius:$thickness:$spread:$drawTop:$drawBottom:$drawLeft:$drawRight"
        if (key != geometryKey) {
            geometryKey = key
            bounds.set(leftInset * density, topInset * density,
                width - rightInset * density, height - bottomInset * density)
            activeSides.reset()
            if (bounds.width() <= 0f || bounds.height() <= 0f) return
            val reach = (cornerRadius + 145f * thickness * spread) * density
            if (drawTop) activeSides.addRect(0f, 0f, width.toFloat(), bounds.top + reach, Path.Direction.CW)
            if (drawBottom) activeSides.addRect(0f, bounds.bottom - reach,
                width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (drawLeft) activeSides.addRect(0f, 0f, bounds.left + reach, height.toFloat(), Path.Direction.CW)
            if (drawRight) activeSides.addRect(bounds.right - reach, 0f,
                width.toFloat(), height.toFloat(), Path.Direction.CW)
        }
        if (isHardwareAccelerated && (drawTop || drawBottom || drawLeft || drawRight)) {
            canvas.save()
            canvas.clipPath(activeSides)
            val radius = min(cornerRadius * density, min(bounds.width(), bounds.height()) / 2f)
            gpuDidRender = engine.render(canvas, width, height, bounds, radius,
                mode, motion.bands, motion.pulse, motion.phase, now / 1000f,
                density * thickness, spread, glow, brightness, edgeColor, accentColor,
                dotSize, dotDensity)
            canvas.restore()
        } else gpuDidRender = false
        // The notch geometry is a native OS-calibrated outline, not an old effect style.
        notchRenderer.draw(canvas, this, notchSettings, edgeColor, accentColor,
            brightness, glow, (motion.pulse + motion.bands.maxOrNull()!!.coerceIn(0f, 1f) * .35f),
            motion.phase)
        if (moving || noticePlaying) requestFrame()
    }
}
