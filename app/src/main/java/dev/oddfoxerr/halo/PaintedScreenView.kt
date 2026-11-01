package dev.oddfoxerr.halo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.View
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Existing serialized settings continue to work across HALO upgrades. */
enum class PaintedStyle(val label: String) {
    OIL("Oil / Impasto"),
    WATERCOLOR("Watercolour"),
    INK("Ink & paint")
}

data class PaintedScreenPreferences(
    val style: PaintedStyle = PaintedStyle.OIL,
    val strength: Float = .96f,
    val brushSize: Float = 1.12f,
    val relief: Float = 1f
) {
    fun save(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString("style", style.name)
            .putFloat("strength", strength.coerceIn(.35f,1f))
            .putFloat("brush_size", brushSize.coerceIn(.65f,2.3f))
            .putFloat("relief", relief.coerceIn(.15f,1.6f))
            .apply()
    }

    companion object {
        private const val STORE = "halo_painted_screen"
        fun read(context: Context): PaintedScreenPreferences {
            val store = context.getSharedPreferences(STORE,Context.MODE_PRIVATE)
            val style = PaintedStyle.entries.firstOrNull {
                it.name == store.getString("style",PaintedStyle.OIL.name)
            } ?: PaintedStyle.OIL
            fun number(key: String, default: Float, lower: Float, upper: Float): Float {
                val value = store.getFloat(key,default)
                return if (value.isFinite()) value.coerceIn(lower,upper) else default
            }
            return PaintedScreenPreferences(
                style,
                number("strength",.96f,.35f,1f),
                number("brush_size",1.12f,.65f,2.3f),
                number("relief",1f,.15f,1.6f)
            )
        }
    }
}

internal object PaintedFrameDecoder {
    fun decode(
        buffer: ByteBuffer, width: Int, height: Int,
        rowStride: Int, pixelStride: Int
    ): Bitmap? {
        if (width !in 16..1024 || height !in 16..2048 ||
            pixelStride < 4 || rowStride < width * pixelStride) return null
        val end = (height-1L)*rowStride + (width-1L)*pixelStride + 3L
        if (end >= buffer.limit()) return null
        val pixels = IntArray(width * height)
        var nonBlack = 0
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) {
                val i = row + x * pixelStride
                val red = buffer.get(i).toInt() and 255
                val green = buffer.get(i+1).toInt() and 255
                val blue = buffer.get(i+2).toInt() and 255
                pixels[y*width+x] = Color.rgb(red,green,blue)
                if ((x and 7)==0 && (y and 7)==0 && max(red,max(green,blue))>5)
                    nonBlack++
            }
        }
        if (nonBlack == 0) return null
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
    }
}

/**
 * Displays actual bitmap brush stamps drawn by Android's accelerated Canvas,
 * NOT a RuntimeShader that pixelates a source image into coloured blocks.
 * All compute-heavy sampling happens off the main thread in StrokePlanner.
 */
internal class PaintedScreenView(context: Context) : View(context) {
    private val brushes: Array<Bitmap> = Array(4) { BrushTextureFactory.create(it) }
    private var currentFrame: PaintedStrokeFrame? = null
    private var preferences = PaintedScreenPreferences.read(context)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val target = RectF()
    private val filters = object : LinkedHashMap<Int,LightingColorFilter>(256,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int,LightingColorFilter>) =
            size > 384
    }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun configure(settings: PaintedScreenPreferences) {
        preferences = settings
        filters.clear()
        invalidate()
    }

    fun setFrame(frame: PaintedStrokeFrame?) {
        val previous = currentFrame
        currentFrame = frame
        invalidate()
        if (previous != null && previous !== frame) {
            // Android's RenderThread can still be sampling old brush layers.
            postDelayed({ previous.dispose() }, 400L)
        }
    }

    fun clear() {
        setFrame(null)
        filters.clear()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = currentFrame ?: return
        if (data.underpainting.isRecycled) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        try {
            val save = canvas.save()
            canvas.scale(w/data.width,h/data.height)
            val origin = RectF(0f,0f,data.width.toFloat(),data.height.toFloat())
            backgroundPaint.alpha = 255
            canvas.drawBitmap(data.underpainting,null,origin,backgroundPaint)
            val extraLight = (preferences.relief * 12f).toInt().coerceIn(0,22)
            val add = Color.rgb(extraLight,extraLight,extraLight)
            for (dab in data.strokes) {
                val key = dab.rgb xor (extraLight shl 24)
                val tint = filters[key] ?: LightingColorFilter(dab.rgb,add).also {
                    filters[key] = it
                }
                strokePaint.colorFilter = tint
                strokePaint.alpha = dab.opacity
                val snapshot = canvas.save()
                canvas.translate(dab.x,dab.y)
                canvas.rotate(dab.rotation)
                target.set(-dab.length*.5f,-dab.thickness*.5f,
                    dab.length*.5f,dab.thickness*.5f)
                canvas.drawBitmap(brushes[dab.brush],null,target,strokePaint)
                canvas.restoreToCount(snapshot)
            }
            strokePaint.colorFilter = null
            canvas.restoreToCount(save)
        } catch (error: RuntimeException) {
            Log.e("HALO","Native brush stamp renderer failed",error)
            strokePaint.colorFilter = null
        }
    }
}

/** Four original alpha-and-height textured brush nibs; no GPL brush images copied. */
internal object BrushTextureFactory {
    fun create(variant: Int): Bitmap {
        val w = 96
        val h = 64
        val pixels = IntArray(w*h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x+.5f-w*.5f)/(w*.5f)
            val dy = (y+.5f-h*.5f)/(h*.5f)
            val stretch = abs(dx)
            val taper = max(.07f,1f-.51f*stretch.pow(2f))
            val wave = sin(dx*13f + variant*2.1f)*.063f +
                sin(dx*37f + dy*7f + variant*1.7f)*.026f
            val blade = when (variant) {
                0 -> max(stretch/.91f,abs(dy)/(taper*.88f+wave))
                1 -> max(stretch/.83f,abs(dy)/(taper*.99f+wave))
                2 -> max(stretch/.95f,abs(dy)/(taper*.68f+wave))
                else -> max(stretch/.75f,abs(dy)/(taper*.84f+wave))
            }
            val coverage = ((1.03f-blade)/.12f).coerceIn(0f,1f)
            val bristles = sin(dy*46f + dx*4f + variant*7f)
            val threads = sin(dy*91f - dx*13f + variant*11f)
            val grain = random(x,y,variant)
            val split = if (variant==2)
                (bristles*.35f + grain*.24f).coerceIn(-.6f,.6f)
                else bristles*.18f+threads*.08f
            val alpha = (255f*coverage*(.85f+split*.24f)*
                (.91f+grain*.09f)).toInt().coerceIn(0,255)
            val height = (.74f+bristles*.13f+threads*.042f+
                (grain-.5f)*.07f+dy*.055f).coerceIn(.35f,1f)
            val brightness = (height*255).toInt().coerceIn(0,255)
            pixels[y*w+x] = Color.argb(alpha,brightness,brightness,brightness)
        }
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
    }

    private fun random(x: Int,y: Int,seed: Int): Float {
        var hash = x*374761393+y*668265263+seed*21474817
        hash = (hash xor (hash ushr 13))*1274126177
        return (hash and 65535)/65535f
    }
}
