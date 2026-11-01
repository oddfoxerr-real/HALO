package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * One visualizer for music and incoming notifications.
 * Removed the old stroke-based animation renderer completely: only AGSL designs render.
 * Camera lighting is opt-in and uses the same AGSL effect as the screen edges.
 */
class ChainsawView(context: Context) : View(context) {
    private val motion = MusicEnvelope()
    private val shaderEngine = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ShaderEdgeEngine() else null
    private var gpuDidRender = false
    val rendererStatus: String get() = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "GPU effects require Android 13+"
        !isHardwareAccelerated -> "GPU rendering needs hardware acceleration"
        gpuDidRender -> "AGSL GPU active"
        else -> "GPU renderer unavailable / not yet drawn"
    }

    private val notificationBands = FloatArray(EdgeSpectrum.BAND_COUNT)
    private var noticeStartAt = 0L
    private var noticeEndAt = 0L
    private var noticePersistent = false
    val isNotificationPlaying: Boolean get() = noticeEndAt > SystemClock.uptimeMillis()
    private val notchMask = Path()
    private val notchOuter = RectF()
    private val notchInner = RectF()
    private var notchMaskKey = ""
    private var notchSettings = ProfileStore(context).notchSettings()

    var thickness = 1f
    var glow = .75f
    var brightness = 1f
    var spread = 1f
    var mode = EdgeMode.DOTS
    var edgeColor = 0xFF9A7BFF.toInt()
    var accentColor = 0xFF52E0CB.toInt()
    var cornerRadius = 32f
    var leftInset = 4f
    var rightInset = 4f
    var topInset = 4f
    var bottomInset = 4f
    var drawTop = true
    var drawBottom = true
    var drawLeft = true
    var drawRight = true
    private var speed = 1f
    private var beatIntensity = 1.5f
    private var shaderSeconds = 0f
    private var videoColors: VideoEdgeColors? = null

    fun setVideoColors(colors: VideoEdgeColors) {
        // Sampling updates *colors*, not the animation selection. Scene changes
        // provide mild transients while the regular GPU motion clock continues.
        val signal = VideoSceneMotion.from(colors, videoColors)
        videoColors = colors
        motion.accept(signal.bands, signal.impact, SystemClock.uptimeMillis(), 1f)
        requestFrame()
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private val bounds = RectF()
    private val sides = Path()
    private var geometryKey = ""
    private var lastFrame = 0L
    private val loop = MusicRenderLoop()
    private val choreographer by lazy { Choreographer.getInstance() }
    private val redraw = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!canRender()) { stopFrames(); return }
            if (loop.onFrame(frameTimeNanos / 1_000_000L)) invalidate()
            else choreographer.postFrameCallback(this)
        }
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun configure(settings: MusicConfiguration) {
        mode = settings.mode
        edgeColor = settings.color
        accentColor = settings.accent
        thickness = settings.thickness
        glow = settings.glow
        brightness = settings.brightness
        spread = settings.spread
        speed = settings.speed
        beatIntensity = settings.beatIntensity
        cornerRadius = settings.corner
        leftInset = settings.leftInset
        rightInset = settings.rightInset
        topInset = settings.topInset
        bottomInset = settings.bottomInset
        drawTop = settings.drawTop
        drawBottom = settings.drawBottom
        drawLeft = settings.drawLeft
        drawRight = settings.drawRight
        notchSettings = ProfileStore(context).notchSettings()
        invalidate()
    }

    fun configureNotch(settings: NotchSettings) {
        notchSettings = settings.normalized()
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
        noticeStartAt = 0L
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
        if (canRender() && loop.request()) choreographer.postFrameCallback(redraw)
    }

    private fun stopFrames() {
        choreographer.removeFrameCallback(redraw)
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

    /**
     * Render precisely the selected display effect around the camera using the same shader,
     * spectrum, palette, motion clock and intensity. Mask the physical camera opening.
     * A compact particle-density uniform makes each pattern legible around a small cutout.
     */
    private fun drawCameraEffect(canvas: Canvas) {
        val placement = NotchGeometry.find(this, notchSettings) ?: return
        val rect = placement.first
        if (rect.width() <= 2f || rect.height() <= 2f) return
        val radius = min(rect.width(), rect.height()) / 2f
        val key = "${rect.left}:${rect.top}:${rect.right}:${rect.bottom}:${placement.second}"
        if (key != notchMaskKey) {
            notchMaskKey = key
            val padding = 24f * density
            notchOuter.set(rect)
            notchOuter.inset(-padding, -padding)
            notchInner.set(rect)
            notchInner.inset(1.5f * density, 1.5f * density)
            notchMask.reset()
            notchMask.fillType = Path.FillType.EVEN_ODD
            notchMask.addRoundRect(notchOuter, radius + padding, radius + padding, Path.Direction.CW)
            notchMask.addRoundRect(notchInner,
                (radius - 1.5f * density).coerceAtLeast(1f),
                (radius - 1.5f * density).coerceAtLeast(1f), Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(notchMask)
        val rendered = shaderEngine?.render(
            canvas, width, height, rect, radius, mode,
            motion.bands, motion.pulse, motion.phase, shaderSeconds,
            density * thickness, spread, glow, brightness,
            edgeColor, accentColor,
            (motion.motor.kick * beatIntensity).coerceIn(0f, 1.5f),
            compact = true, video = videoColors, videoFrame = bounds
        ) == true
        gpuDidRender = gpuDidRender || rendered
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1000f).coerceIn(0f, .1f)
        lastFrame = now
        val noticePlaying = noticeEndAt > now || (noticePersistent && noticeStartAt > 0L)
        if (noticePlaying) {
            val seconds = (now - noticeStartAt).coerceAtLeast(0L) / 1000f
            val beat = (.43f + .24f * sin(seconds * 7f)).coerceIn(.12f, .95f)
            for (i in notificationBands.indices) {
                notificationBands[i] = (.15f + beat * (.5f + .5f *
                    sin(seconds * (2.7f + i % 4 * .14f) + i * .6f))).coerceIn(.03f, .9f)
            }
            motion.accept(notificationBands, beat, now, 1f)
        }

        val moving = motion.advance(now, dt, speed)
        shaderSeconds = (shaderSeconds + dt * speed).rem(3600f)
        val key = "$width:$height:$leftInset:$rightInset:$topInset:$bottomInset:$cornerRadius:$thickness:$spread:$drawTop:$drawBottom:$drawLeft:$drawRight"
        if (geometryKey != key) {
            geometryKey = key
            bounds.set(leftInset * density, topInset * density,
                width - rightInset * density, height - bottomInset * density)
            sides.reset()
            if (bounds.width() > 0f && bounds.height() > 0f) {
                // A separate clipping mask for each enabled physical side.
                val reach = (90f * thickness * spread + cornerRadius) * density
                if (drawTop) sides.addRect(0f, 0f, width.toFloat(), bounds.top + reach, Path.Direction.CW)
                if (drawBottom) sides.addRect(0f, bounds.bottom - reach,
                    width.toFloat(), height.toFloat(), Path.Direction.CW)
                if (drawLeft) sides.addRect(0f, 0f, bounds.left + reach, height.toFloat(), Path.Direction.CW)
                if (drawRight) sides.addRect(bounds.right - reach, 0f,
                    width.toFloat(), height.toFloat(), Path.Direction.CW)
            }
        }

        if (bounds.width() > 0f && bounds.height() > 0f &&
            (drawTop || drawBottom || drawLeft || drawRight)) {
            canvas.save()
            canvas.clipPath(sides)
            // Use the SAME GPU material for video, music, notifications and the
            // camera cutout. Only the palette source differs in video mode.
            gpuDidRender = shaderEngine?.render(
                canvas, width, height, bounds,
                min(cornerRadius * density, min(bounds.width(), bounds.height()) / 2f),
                mode, motion.bands, motion.pulse, motion.phase, shaderSeconds,
                density * thickness, spread, glow, brightness, edgeColor, accentColor,
                (motion.motor.kick * beatIntensity).coerceIn(0f, 1.5f),
                video = videoColors
            ) == true
            canvas.restore()
        } else {
            gpuDidRender = false
        }

        drawCameraEffect(canvas)
        // Static video frames still animate the user's selected material; color
        // sampling frequency never controls the GPU animation frame rate.
        if (moving || noticePlaying || videoColors != null) requestFrame()
    }
}
