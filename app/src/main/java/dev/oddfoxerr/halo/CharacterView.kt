package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Native Canvas animation: no WebView, animated GIFs, bitmap sprites or notification body access. */
class CharacterView(context: Context, private val overlay: Boolean = false) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var profile = CharacterProfile(CharacterApp.DISCORD)
    private var startMs = 0L
    private var animated = false
    private var indicator = false
    private var completed = true
    private var looping = false
    private var previewFrame: Float? = null
    private val path = Path()
    private val bounds = RectF()

    fun play(next: CharacterProfile, keepIndicator: Boolean = true) {
        profile = next
        looping = false
        previewFrame = null
        animated = true
        indicator = keepIndicator
        completed = false
        startMs = SystemClock.uptimeMillis()
        invalidate()
    }
    /** Continuous motion editor: adjusts values without resetting an in-flight animation. */
    fun configureLoop(next: CharacterProfile) {
        val restart = !looping || !animated || profile.app != next.app
        profile = next
        looping = true
        previewFrame = null
        indicator = false
        completed = false
        animated = true
        if (restart) startMs = SystemClock.uptimeMillis()
        invalidate()
    }
    /** Scrub the animation to an exact frame without any clock-dependent movement. */
    fun previewAt(next: CharacterProfile, progress: Float) {
        profile = next
        looping = false
        previewFrame = progress.coerceIn(0f, .999f)
        animated = true
        indicator = false
        completed = false
        invalidate()
    }
    fun indicate(next: CharacterProfile) {
        profile = next
        looping = false
        previewFrame = null
        animated = false
        indicator = next.persistent
        completed = true
        invalidate()
    }
    fun stop() {
        animated = false
        looping = false
        previewFrame = null
        indicator = false
        completed = true
        invalidate()
    }
    fun isAnimating() = animated

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val current = SystemClock.uptimeMillis()
        val t = when {
            previewFrame != null -> previewFrame!!
            looping -> ((current - startMs) % (profile.durationMs + 650L)).toFloat() / profile.durationMs
            animated -> ((current - startMs) / profile.durationMs.toFloat()).coerceIn(0f, 1f)
            else -> 1f
        }
        if (animated && t >= 1f && !looping && previewFrame == null) { animated = false; completed = true }
        if (!animated && !indicator && completed) return
        val base = profile.color.toInt()
        val density = resources.displayMetrics.density
        val inset = 4f * density
        canvas.save()
        if (profile.side == EdgeSide.LEFT) {
            canvas.scale(-1f, 1f, w / 2f, h / 2f)
        }
        val visualEdge = (if (overlay) w else w - inset) - profile.edgeInsetDp * density
        val anchorY = h * profile.anchorY.coerceIn(.20f, .85f)
        if (animated) {
            val fade = ease(0f, .12f, t) * (1f - ease(.86f, 1f, t))
            val progress = ease(.10f, .35f, t) * (1f - ease(.57f, .84f, t))
            drawWave(canvas, visualEdge, anchorY, w, h, base, fade, progress, t, density)
            drawCharacter(canvas, visualEdge, anchorY, w, h, base, fade, progress, t)
            if (t > .70f) drawExit(canvas, visualEdge, anchorY, base, ease(.72f, .99f, t), fade, density)
        }
        if (indicator) {
            val alpha = if (animated) .38f * ease(.75f, 1f, t) else .92f
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(base, alpha)
            paint.setShadowLayer(7f * density * profile.glow, 0f, 0f, base)
            canvas.drawCircle(visualEdge - 8f * density, h * .13f, 4.2f * density, paint)
            paint.clearShadowLayer()
        }
        canvas.restore()
        if (animated && previewFrame == null && isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun drawWave(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int, alpha: Float, progress: Float, t: Float, dp: Float) {
        if (alpha <= 0f) return
        val r = min(w * .33f, 60f * dp) * profile.scale
        val waveY = h * (.72f - .23f * ease(0f, .26f, t))
        path.reset()
        path.moveTo(x, 0f)
        var py = 0f
        while (py < h) {
            val bulge = r * .85f * gaussian((py - waveY) / max(1f, r * 1.7f)) * (1f - ease(.15f, .43f, t))
            val tether = r * .47f * progress * gaussian((py - y) / max(1f, r))
            path.lineTo(x - bulge - tether, py)
            py += max(dp * 3f, 3f)
        }
        path.lineTo(x, h)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(color, alpha * .84f)
        paint.setShadowLayer(20f * dp * profile.glow, 0f, 0f, color)
        canvas.drawPath(path, paint)
        paint.clearShadowLayer()
    }

    private fun drawCharacter(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int, alpha: Float, progress: Float, t: Float) {
        if (progress <= .01f || alpha <= 0f) return
        val d = resources.displayMetrics.density
        val r = min(w * .34f, 48f * d) * profile.scale
        val e = ease(.13f, .34f, t) * (1f - ease(.60f, .82f, t))
        val bounce = when (profile.app) {
            CharacterApp.WHATSAPP -> sin(ease(.29f, .62f, t) * PI.toFloat() * 5f) * 12f * d * e
            CharacterApp.YOUTUBE -> -sin(ease(.25f, .48f, t) * PI.toFloat()) * 19f * d * e
            CharacterApp.GMAIL -> sin(ease(.27f, .67f, t) * PI.toFloat() * 2f) * 8f * d * e
            CharacterApp.OTHER -> cos(ease(.26f, .69f, t) * PI.toFloat() * 4f) * 10f * d * e
            CharacterApp.DISCORD -> sin(ease(.30f, .57f, t) * PI.toFloat() * 3f) * 6f * d * e
        }
        val fall = ease(.68f, .87f, t)
        val entering = 1f - ease(.12f, .35f, t)
        val extraX = when (profile.app) {
            CharacterApp.YOUTUBE -> r * .75f * entering
            CharacterApp.WHATSAPP -> -r * .38f * entering
            CharacterApp.GMAIL -> r * .25f * entering
            CharacterApp.OTHER -> r * .25f * sin(t * 11f)
            CharacterApp.DISCORD -> 0f
        }
        val cx = x - r * (.24f + 1.07f * e) + fall * r * .45f + extraX
        val cy = y + bounce + fall * r * .6f
        canvas.save()
        canvas.translate(cx, cy)
        val shrink = (.18f + .82f * e) * (1f - fall * .63f)
        val squashX = shrink * (1f + .08f * sin(t * 35f))
        val squashY = shrink * (1f - .08f * sin(t * 35f))
        canvas.scale(squashX, squashY)
        when (profile.app) {
            CharacterApp.YOUTUBE -> canvas.rotate((1f - ease(.16f, .43f, t)) * 23f)
            CharacterApp.GMAIL -> canvas.rotate(sin(t * 21f) * 8f * e)
            CharacterApp.WHATSAPP -> canvas.rotate(sin(t * 17f) * 7f * e)
            CharacterApp.OTHER -> canvas.rotate(t * 140f * e)
            CharacterApp.DISCORD -> Unit
        }
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(color, alpha)
        paint.setShadowLayer(16f * d * profile.glow, 0f, 0f, color)
        when (profile.app) {
            CharacterApp.DISCORD -> drawWumpusInspired(canvas, r, color, alpha)
            CharacterApp.WHATSAPP -> drawBubble(canvas, r, color, alpha)
            CharacterApp.YOUTUBE -> drawPlay(canvas, r, color, alpha)
            CharacterApp.GMAIL -> drawEnvelope(canvas, r, color, alpha, t)
            CharacterApp.OTHER -> drawStar(canvas, r, color, alpha, t)
        }
        paint.clearShadowLayer()
        canvas.restore()
    }

    private fun drawWumpusInspired(canvas: Canvas, r: Float, color: Int, alpha: Float) {
        // Original liquid creature stand-in, not official Discord Wumpus artwork.
        path.reset()
        for (i in 0..100) {
            val a = i / 100.0 * PI * 2.0
            val ear = .30 * gaussian(((a - 4.0) / .23).toFloat()) + .28 * gaussian(((a - 5.15) / .23).toFloat())
            val rr = r * (1.0 + ear).toFloat()
            val xx = cos(a).toFloat() * rr
            val yy = sin(a).toFloat() * rr
            if (i == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
        }
        path.close()
        paint.color = withAlpha(color, alpha)
        canvas.drawPath(path, paint)
        face(canvas, r, alpha)
    }

    private fun drawBubble(canvas: Canvas, r: Float, color: Int, alpha: Float) {
        bounds.set(-r, -r * .75f, r, r * .82f)
        paint.color = withAlpha(color, alpha)
        canvas.drawRoundRect(bounds, r * .45f, r * .45f, paint)
        path.reset(); path.moveTo(-r * .4f, r * .5f); path.lineTo(-r * .8f, r * 1.05f); path.lineTo(r * .07f, r * .72f); path.close()
        canvas.drawPath(path, paint)
        face(canvas, r, alpha)
    }

    private fun drawPlay(canvas: Canvas, r: Float, color: Int, alpha: Float) {
        paint.color = withAlpha(color, alpha)
        bounds.set(-r, -r * .68f, r, r * .68f)
        canvas.drawRoundRect(bounds, r * .36f, r * .36f, paint)
        path.reset(); path.moveTo(-r * .15f, -r * .39f); path.lineTo(r * .52f, 0f); path.lineTo(-r * .15f, r * .39f); path.close()
        paint.color = withAlpha(Color.WHITE, alpha)
        canvas.drawPath(path, paint)
        paint.color = withAlpha(Color.rgb(76, 14, 25), alpha)
        canvas.drawCircle(-r * .64f, -r * .40f, r * .065f, paint)
        canvas.drawCircle(r * .64f, -r * .40f, r * .065f, paint)
    }

    private fun drawEnvelope(canvas: Canvas, r: Float, color: Int, alpha: Float, t: Float) {
        bounds.set(-r, -r * .75f, r, r * .75f)
        paint.color = withAlpha(Color.rgb(242, 236, 226), alpha)
        canvas.drawRoundRect(bounds, r * .16f, r * .16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * .12f
        paint.color = withAlpha(color, alpha)
        path.reset(); path.moveTo(-r * .94f, -r * .68f); path.lineTo(0f, r * .12f); path.lineTo(r * .94f, -r * .68f)
        canvas.drawPath(path, paint)
        val open = sin(ease(.31f, .53f, t) * PI.toFloat()).coerceAtLeast(0f)
        if (open > .05f) {
            path.reset(); path.moveTo(-r * .85f, -r * .64f); path.lineTo(0f, -r * (.68f + open * .46f)); path.lineTo(r * .85f, -r * .64f)
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawStar(canvas: Canvas, r: Float, color: Int, alpha: Float, t: Float) {
        canvas.rotate(t * 75f)
        path.reset()
        for (i in 0 until 10) {
            val a = i * PI / 5 - PI / 2
            val rad = if (i % 2 == 0) r else r * .48f
            val xx = (cos(a) * rad).toFloat()
            val yy = (sin(a) * rad).toFloat()
            if (i == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
        }
        path.close()
        paint.color = withAlpha(color, alpha)
        canvas.drawPath(path, paint)
        face(canvas, r, alpha)
    }

    private fun face(canvas: Canvas, r: Float, alpha: Float) {
        paint.color = withAlpha(Color.rgb(25, 22, 48), alpha)
        canvas.drawOval(-r * .36f, -r * .23f, -r * .24f, -r * .01f, paint)
        canvas.drawOval(r * .19f, -r * .23f, r * .31f, -r * .01f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(2f, r * .067f)
        val rect = RectF(-r * .18f, -r * .08f, r * .15f, r * .28f)
        canvas.drawArc(rect, 0f, 180f, false, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawExit(canvas: Canvas, x: Float, y: Float, color: Int, progress: Float, alpha: Float, dp: Float) {
        when (profile.app) {
            CharacterApp.DISCORD -> drawDroplet(canvas, x, y, width.toFloat(), height.toFloat(), color, progress, alpha)
            CharacterApp.WHATSAPP -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f * dp
                paint.color = withAlpha(color, alpha * (1f - progress))
                val radius = (6f + 35f * progress) * dp
                canvas.drawCircle(x - 16f * dp, y, radius, paint)
                paint.style = Paint.Style.FILL
            }
            CharacterApp.YOUTUBE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5f * dp * (1f - progress * .8f)
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = withAlpha(color, alpha * (1f - progress))
                canvas.drawLine(x - 55f * (1f - progress) * dp, y + 24f * progress * dp, x, y + 42f * progress * dp, paint)
                paint.style = Paint.Style.FILL
            }
            CharacterApp.GMAIL -> {
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(color, alpha * (1f - progress))
                val size = 17f * dp * (1f - progress)
                canvas.drawRoundRect(x - size * 2f, y + progress * 35f * dp, x, y + progress * 35f * dp + size, 3f * dp, 3f * dp, paint)
            }
            CharacterApp.OTHER -> {
                paint.style = Paint.Style.FILL
                for (i in 0 until 5) {
                    val angle = i * (2f * PI.toFloat() / 5f)
                    paint.color = withAlpha(color, alpha * (1f - progress))
                    canvas.drawCircle(x - 15f * dp + cos(angle) * progress * 24f * dp,
                        y + sin(angle) * progress * 24f * dp, (3.5f - 2.7f * progress) * dp, paint)
                }
            }
        }
    }

    private fun drawDroplet(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int, fall: Float, alpha: Float) {
        if (alpha <= 0f) return
        val d = resources.displayMetrics.density
        val r = 8f * d * profile.scale * (1f - .6f * fall)
        val cx = x - 13f * d
        val cy = y + fall * h * .16f
        path.reset()
        path.moveTo(cx, cy - 2.2f * r)
        path.cubicTo(cx + 2f * r, cy, cx + r, cy + r, cx, cy + r)
        path.cubicTo(cx - r, cy + r, cx - 2f * r, cy, cx, cy - 2.2f * r)
        paint.color = withAlpha(color, alpha * (1f - fall))
        paint.setShadowLayer(12f * d * profile.glow, 0f, 0f, color)
        canvas.drawPath(path, paint)
        paint.clearShadowLayer()
    }

    private fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or
        (((alpha.coerceIn(0f, 1f) * 255).toInt()) shl 24)
    private fun ease(a: Float, b: Float, t: Float): Float {
        val x = ((t - a) / (b - a)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
    private fun gaussian(x: Float) = exp((-x * x).toDouble()).toFloat()
}
