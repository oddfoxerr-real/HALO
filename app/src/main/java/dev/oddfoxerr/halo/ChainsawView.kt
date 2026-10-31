package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import kotlin.math.*

/** Shared HALO music renderer. Chainsaw is one design, not the music system itself. */
class ChainsawView(context: Context) : View(context) {
    private val motion = MusicEnvelope()
    private var notificationStartedAt = -1L
    private var notificationRepeating = false
    private var notificationDuration = 4200L
    private val notificationBands = FloatArray(EdgeSpectrum.BAND_COUNT)
    val notificationIsPlaying: Boolean get() = notificationStartedAt >= 0L && !notificationRepeating
    private val extraEffects = MusicExtraEffects()
    // API 33+ uses a mutually exclusive GPU pipeline. Legacy Canvas is a fallback only.
    private var gpu: GpuEdgeRenderer? = if (Build.VERSION.SDK_INT >= 33) {
        try { GpuEdgeRenderer() } catch (failure: RuntimeException) {
            Log.e("HALO", "Could not compile GPU visualizer shader", failure)
            null
        }
    } else null
    val rendererStatus: String get() = when {
        Build.VERSION.SDK_INT < 33 -> "Canvas fallback (Android version)"
        gpu == null -> "Canvas fallback (GPU unavailable)"
        !isHardwareAccelerated -> "Canvas fallback (hardware acceleration off)"
        else -> "GPU shader active"
    }
    private val notchRenderer = NotchLightRenderer()
    private var notchSettings = ProfileStore(context).notchSettings()
    var thickness = 1f
    var glow = .75f
    var brightness = 1f
    var spread = 1f
    var mode = EdgeMode.WAVE
    var edgeColor = 0xFF9A7BFF.toInt()
    var accentColor = 0xFF52E0CB.toInt()
    var edgeInset = 8f
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
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val perimeter = Path()
    private val activeSides = Path()
    private val wave = Path()
    private val segment = Path()
    private val tooth = Path()
    private val bounds = RectF()
    private val measure = PathMeasure()
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)
    private val coordinates = FloatArray(226)
    private var geometryKey = ""
    private var shaderKey = ""
    private var gradient: Shader? = null
    private var lastFrame = 0L
    private val renderLoop = MusicRenderLoop()
    private val choreographer by lazy { Choreographer.getInstance() }
    private val redraw = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!canRender()) { stopFrames(); return }
            if (renderLoop.onFrame(frameTimeNanos / 1_000_000L)) invalidate()
            else choreographer.postFrameCallback(this)
        }
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun configure(settings: MusicConfiguration) {
        mode = settings.mode; edgeColor = settings.color; accentColor = settings.accent
        thickness = settings.thickness; glow = settings.glow; brightness = settings.brightness; spread = settings.spread; speed = settings.speed
        notchSettings = ProfileStore(context).notchSettings()
        edgeInset = settings.inset; cornerRadius = settings.corner
        leftInset = settings.leftInset; rightInset = settings.rightInset
        topInset = settings.topInset; bottomInset = settings.bottomInset
        drawTop = settings.drawTop; drawBottom = settings.drawBottom
        drawLeft = settings.drawLeft; drawRight = settings.drawRight
        invalidate()
    }
    fun configureNotch(settings: NotchSettings) { notchSettings = settings.normalized(); invalidate() }
    fun showNotificationSignal(duration: Long, repeating: Boolean = false) {
        notificationStartedAt = SystemClock.uptimeMillis()
        notificationRepeating = repeating
        notificationDuration = duration.coerceIn(800L, 10000L)
        motion.clear()
        requestFrame()
        invalidate()
    }
    fun holdNotificationSignal() {
        if (notificationStartedAt >= 0L) {
            notificationRepeating = true
            requestFrame()
        }
    }
    fun stopNotificationSignal() {
        notificationStartedAt = -1L
        notificationRepeating = false
        motion.clear()
        stopFrames()
        invalidate()
    }
    fun clearSignal() {
        notificationStartedAt = -1L
        motion.clear(); stopFrames(); invalidate()
    }
    fun updateSpectrum(bands: FloatArray, bass: Float, sensitivity: Float, rate: Float) {
        speed = rate
        if (notificationStartedAt < 0L) motion.accept(bands, bass, SystemClock.uptimeMillis(), sensitivity)
        requestFrame()
    }
    private fun requestFrame() {
        if (canRender() && renderLoop.request()) choreographer.postFrameCallback(redraw)
    }
    private fun canRender() = isShown && isAttachedToWindow && windowVisibility == VISIBLE
    private fun stopFrames() {
        choreographer.removeFrameCallback(redraw)
        renderLoop.reset()
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
    override fun onDetachedFromWindow() { clearSignal(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 1f / 60 else (now - lastFrame) / 1000f
        lastFrame = now
        if (notificationStartedAt >= 0L) {
            val seconds = (now - notificationStartedAt).coerceAtLeast(0L) / 1000f
            val fade = if (notificationRepeating) .48f else
                (1f - (now - notificationStartedAt).toFloat() / notificationDuration)
                    .coerceIn(0f, 1f)
            val beat = .20f + .37f * (0.5f + 0.5f * sin(seconds * 7.4f))
            for (i in notificationBands.indices) {
                val waviness = .5f + .5f * sin(seconds * (2.1f + i % 5 * .12f) + i * .63f)
                notificationBands[i] = (fade * (.12f + beat * waviness)).coerceIn(0f, 1f)
            }
            motion.accept(notificationBands, (beat * fade).coerceIn(0f, 1f), now, 1f)
        }
        val moving = motion.advance(now, dt, speed)
        val unit = density * thickness
        val key = "$width:$height:$leftInset:$rightInset:$topInset:$bottomInset:$cornerRadius:$thickness:$drawTop:$drawBottom:$drawLeft:$drawRight"
        if (key != geometryKey) {
            geometryKey = key
            // Four independent edges, not one uniformly shrunken rectangle.
            bounds.set(leftInset * density, topInset * density,
                width - rightInset * density, height - bottomInset * density)
            if (bounds.width() <= 0 || bounds.height() <= 0) return
            perimeter.reset()
            val radius = min(cornerRadius * density, min(bounds.width(), bounds.height()) / 2f)
            perimeter.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            measure.setPath(perimeter, true)
            activeSides.reset()
            // Keep enough space for the longest inward spectrum bar and its glow.
            val reach = (cornerRadius + 34f * thickness) * density
            if (drawTop) activeSides.addRect(0f, 0f, width.toFloat(), bounds.top + reach, Path.Direction.CW)
            if (drawBottom) activeSides.addRect(0f, bounds.bottom - reach, width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (drawLeft) activeSides.addRect(0f, 0f, bounds.left + reach, height.toFloat(), Path.Direction.CW)
            if (drawRight) activeSides.addRect(bounds.right - reach, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        }
        val colors = "$width:$height:$edgeColor:$accentColor"
        if (colors != shaderKey) {
            shaderKey = colors
            gradient = SweepGradient(width / 2f, height / 2f, intArrayOf(edgeColor, accentColor, edgeColor, accentColor, edgeColor), null)
        }
        if (measure.length > 0f && (drawTop || drawBottom || drawLeft || drawRight)) {
            canvas.save()
            canvas.clipPath(activeSides)
            // A completely different pipeline: on supported GPUs, the shader draws the
            // actual moving visual geometry. Do NOT overlay the old strokes or lightfield.
            var gpuDrawn = false
            val renderer = gpu
            if (renderer != null && canvas.isHardwareAccelerated) {
                try {
                    renderer.draw(canvas, width, height, bounds,
                        min(cornerRadius * density, min(bounds.width(), bounds.height()) / 2f),
                        booleanArrayOf(drawTop, drawRight, drawBottom, drawLeft),
                        motion.bands, motion.pulse, motion.phase, now / 1000f * speed,
                        mode, density, thickness, spread, glow, brightness, edgeColor, accentColor)
                    gpuDrawn = true
                } catch (failure: RuntimeException) {
                    Log.e("HALO", "GPU visualizer failed; using compatibility renderer", failure)
                    gpu = null
                }
            }
            if (!gpuDrawn) when (mode) {
            EdgeMode.WAVE -> silk(canvas, unit)
            EdgeMode.BARS -> spectrum(canvas, unit)
            EdgeMode.TRAIL -> orbit(canvas, unit)
            EdgeMode.PULSE -> {
                stroke(canvas, perimeter, (1.7f + motion.pulse * 5f) * unit, (165 + motion.pulse * 90).toInt(), true)
            }
            EdgeMode.CHAIN -> chainsaw(canvas, unit)
            EdgeMode.AURORA, EdgeMode.COMET, EdgeMode.TWIN, EdgeMode.SPARKS,
            EdgeMode.PIXELS, EdgeMode.NEON, EdgeMode.RAIN, EdgeMode.HELIX,
            EdgeMode.BLOOM, EdgeMode.PRISM -> extraEffects.draw(mode, canvas, perimeter, measure,
                motion, width, height, density, thickness, brightness, glow, edgeColor, accentColor)
            }
            canvas.restore()
        }
        notchRenderer.draw(canvas, this, notchSettings, edgeColor, accentColor,
            brightness, glow, motion.pulse + motion.bands.maxOrNull()!!.coerceIn(0f, 1f) * .35f, motion.phase)
        paint.shader = null; paint.alpha = 255
        if (moving || notificationStartedAt >= 0L) requestFrame()
    }
    private fun amplitude(fraction: Float): Float {
        // Mirror the spectrum around the frame; interpolate between bands instead of stair-stepping.
        val value = (((fraction * 2f + motion.phase * .3f) % 1f + 1f) % 1f) * (motion.bands.size - 1)
        val low = value.toInt()
        return motion.bands[low] * (1 - (value - low)) + motion.bands[min(low + 1, motion.bands.lastIndex)] * (value - low)
    }
    private fun silk(canvas: Canvas, unit: Float) {
        val count = 112
        for (i in 0 until count) {
            measure.getPosTan(measure.length * i / count, position, tangent)
            val flutter = sin((i.toFloat() / count * 5f + motion.phase * 2f) * (2f * PI)).toFloat()
            val offset = (2f + amplitude(i.toFloat() / count) * 18f + flutter * (2f + motion.pulse * 6f)) * unit
            coordinates[i * 2] = position[0] - tangent[1] * offset
            coordinates[i * 2 + 1] = position[1] + tangent[0] * offset
        }
        wave.reset()
        wave.moveTo((coordinates[0] + coordinates[(count - 1) * 2]) / 2,
            (coordinates[1] + coordinates[(count - 1) * 2 + 1]) / 2)
        for (i in 0 until count) {
            val next = (i + 1) % count
            wave.quadTo(coordinates[i * 2], coordinates[i * 2 + 1],
                (coordinates[i * 2] + coordinates[next * 2]) / 2,
                (coordinates[i * 2 + 1] + coordinates[next * 2 + 1]) / 2)
        }
        wave.close()
        stroke(canvas, wave, 2.7f * unit, (170 + motion.bands.maxOrNull()!! * 85).toInt(), true)
    }
    private fun spectrum(canvas: Canvas, unit: Float) {
        stroke(canvas, perimeter, 1.8f * unit, 155)
        paint.style = Paint.Style.STROKE; paint.shader = gradient; paint.strokeWidth = 2.6f * unit
        for (i in 0 until 120) {
            val fraction = i / 120f
            measure.getPosTan(measure.length * fraction, position, tangent)
            val strength = amplitude(fraction)
            val length = (2.2f + strength * 25f + motion.pulse * 3.5f) * unit
            paint.alpha = ((130 + strength * 125) * brightness).toInt().coerceIn(0, 255)
            canvas.drawLine(position[0], position[1], position[0] - tangent[1] * length, position[1] + tangent[0] * length, paint)
        }
    }
    private fun orbit(canvas: Canvas, unit: Float) {
        stroke(canvas, perimeter, 1.7f * unit, 145)
        val length = measure.length
        for (head in 0..1) for (part in 0 until 20) {
            val start = ((motion.phase + head * .5f - part * .008f + 1f) % 1f) * length
            val end = start + length * .009f
            segment.reset()
            measure.getSegment(start, min(end, length), segment, true)
            if (end > length) measure.getSegment(0f, end - length, segment, true)
            stroke(canvas, segment, (2.2f + motion.pulse * 3) * unit,
                ((1f - part / 20f) * (175 + motion.pulse * 80)).toInt())
        }
    }
    private fun chainsaw(canvas: Canvas, unit: Float) {
        val motor = motion.motor
        stroke(canvas, perimeter, (3.5f + motor.kick * 1.5f) * unit, 165, true)
        val count = (measure.length / (19f * unit)).toInt().coerceIn(6, 260)
        for (i in 0 until count) {
            measure.getPosTan((i * measure.length / count + motor.travel * density) % measure.length, position, tangent)
            canvas.save(); canvas.translate(position[0], position[1])
            canvas.rotate(Math.toDegrees(atan2(tangent[1], tangent[0]).toDouble()).toFloat())
            canvas.scale(unit, unit)
            paint.shader = null; paint.alpha = 255; paint.style = Paint.Style.FILL
            paint.color = Color.rgb(130, 143, 150)
            canvas.drawRoundRect(-6f, -2f, 6f, 2f, 2f, 2f, paint)
            tooth.reset(); tooth.moveTo(-4f, 2f); tooth.lineTo(-2f, 9f); tooth.lineTo(5f, 6f); tooth.lineTo(2f, 2f); tooth.close()
            paint.color = Color.rgb(210, 219, 221); canvas.drawPath(tooth, paint)
            if (i % 5 == 0) {
                paint.color = Color.argb((motor.jam * 205).toInt(), 117, 32, 28)
                canvas.drawOval(-6f, -3f, 5f, 6f, paint)
            }
            if (motor.kick > .1f && i % 8 == 0) {
                paint.color = Color.argb((motor.kick * 240).toInt(), 255, 181, 73); paint.strokeWidth = 1.2f
                canvas.drawLine(0f, 9f, 5f, 12f + 18f * (1 - motor.kick), paint)
            }
            canvas.restore()
        }
    }
    private fun stroke(canvas: Canvas, path: Path, width: Float, alpha: Int, halo: Boolean = false) {
        paint.style = Paint.Style.STROKE; paint.shader = gradient; paint.color = Color.WHITE
        if (halo && glow > 0f) {
            for (layer in 3 downTo 1) {
                paint.strokeWidth = width + layer * density * 3f
                paint.alpha = (alpha * glow * brightness * .40f / layer).toInt().coerceIn(0, 255)
                canvas.drawPath(path, paint)
            }
        }
        paint.strokeWidth = width
        paint.alpha = (alpha * brightness).toInt().coerceIn(0, 255)
        canvas.drawPath(path, paint)
    }
}
