package dev.oddfoxerr.halo

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Android-native adaptation of the image-to-brush-stamps technique used by
 * offline paint filters. It does NOT execute Blender/Python or redistribute
 * GPL assets: the brush textures and sampler here are original.
 *
 * No time-based random seeds: a static screenshot keeps the same stroke
 * positions, preventing the previous "shimmering pixels" effect.
 */
internal data class PaintDab(
    val x: Float,
    val y: Float,
    val length: Float,
    val thickness: Float,
    val rotation: Float,
    val rgb: Int,
    val opacity: Int,
    val brush: Int
)

internal class PaintedStrokeFrame(
    val original: Bitmap,
    val underpainting: Bitmap,
    val strokes: List<PaintDab>,
    val width: Int,
    val height: Int
) {
    fun dispose() {
        if (underpainting !== original && !underpainting.isRecycled)
            underpainting.recycle()
        if (!original.isRecycled) original.recycle()
    }
}

internal object PaintedStrokePlanner {
    // Prevent a backlog of thousands of GPU Canvas draw calls on a mid-range phone.
    private const val MAX_STROKES = 1600

    fun plan(frame: Bitmap, settings: PaintedScreenPreferences): PaintedStrokeFrame {
        require(!frame.isRecycled)
        val w = frame.width
        val h = frame.height
        require(w >= 16 && h >= 16)
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)

        // The old 1/7-resolution underpainting made the entire display look
        // like a blurry fallback filter. Preserve the real captured image as
        // the backing; physical-looking brush stamps remain the effect.
        // The frame owns one bitmap, not two allocations per capture.
        val underpainting = frame
        val marks = ArrayList<PaintDab>(2000)
        val zoom = settings.brushSize.coerceIn(.65f, 2.3f)
        val layers = floatArrayOf(31f * zoom, 19f * zoom, 12f * zoom)
        val passes = settings.style

        for (layer in layers.indices) {
            val spacing = layers[layer]
            val xCount = (w / spacing).toInt() + 2
            val yCount = (h / spacing).toInt() + 2
            val phaseX = if (layer == 1) .44f else if (layer == 2) .19f else 0f
            val phaseY = if (layer == 1) .26f else if (layer == 2) .61f else 0f

            for (row in 0..yCount) for (col in 0..xCount) {
                if (marks.size >= MAX_STROKES) break
                val seed = hash(col, row, layer + 9)
                val x = ((col + phaseX + .5f) * spacing +
                    (hashUnit(seed + 13) - .5f) * spacing * .61f)
                    .coerceIn(0f, (w - 1).toFloat())
                val y = ((row + phaseY + .5f) * spacing +
                    (hashUnit(seed + 47) - .5f) * spacing * .61f)
                    .coerceIn(0f, (h - 1).toFloat())
                val sx = x.toInt()
                val sy = y.toInt()
                val gradientRadius = max(1, (spacing * .20f).toInt())
                val horizontal = luma(pixels, w, h, sx + gradientRadius, sy) -
                    luma(pixels, w, h, sx - gradientRadius, sy)
                val vertical = luma(pixels, w, h, sx, sy + gradientRadius) -
                    luma(pixels, w, h, sx, sy - gradientRadius)
                val magnitude = sqrt(horizontal * horizontal + vertical * vertical)
                // Near strong boundaries, short detail marks preserve forms.
                // Otherwise longer brush marks can be grouped into big swaths.
                if (layer == 2 && magnitude < .055f &&
                    hashUnit(seed + 91) < .51f) continue

                val rotation = if (magnitude > .018f)
                    atan2(-horizontal, vertical) * (180.0 / PI).toFloat()
                else hashUnit(seed + 29) * 150f - 75f

                val directionX = kotlin.math.cos(rotation * PI / 180.0).toFloat()
                val directionY = kotlin.math.sin(rotation * PI / 180.0).toFloat()
                val rawColor = sampleColor(pixels, w, h, sx, sy,
                    directionX, directionY, max(1, (spacing * .28f).toInt()))
                val jittered = mixPigment(rawColor, hashUnit(seed + 123),
                    layer, settings.style)

                val widthScale = when (passes) {
                    PaintedStyle.OIL -> 1f
                    PaintedStyle.WATERCOLOR -> 1.16f
                    PaintedStyle.INK -> .86f
                }
                val edgeReduction = (1f - magnitude * .85f).coerceIn(.52f, 1f)
                val length = spacing * (1.55f + hashUnit(seed + 5) * .65f) *
                    edgeReduction * widthScale
                val thickness = spacing * (.77f + hashUnit(seed + 12) * .30f) * widthScale
                val opacity = when (passes) {
                    PaintedStyle.OIL -> when (layer) { 0 -> 239; 1 -> 228; else -> 210 }
                    PaintedStyle.WATERCOLOR -> when (layer) { 0 -> 135; 1 -> 111; else -> 82 }
                    PaintedStyle.INK -> when (layer) { 0 -> 220; 1 -> 241; else -> 220 }
                }
                marks.add(PaintDab(
                    x = x,
                    y = y,
                    length = length,
                    thickness = thickness,
                    rotation = rotation + (hashUnit(seed + 73) - .5f) * 25f,
                    rgb = jittered,
                    opacity = (opacity * settings.strength).toInt().coerceIn(0,255),
                    brush = (seed ushr 8) and 3
                ))
            }
        }

        return PaintedStrokeFrame(frame, underpainting, marks, w, h)
    }

    private fun luma(pixels: IntArray, w: Int, h: Int, x: Int, y: Int): Float {
        val px = pixels[y.coerceIn(0,h-1) * w + x.coerceIn(0,w-1)]
        return ((px ushr 16 and 255) * .2126f +
            (px ushr 8 and 255) * .7152f +
            (px and 255) * .0722f) / 255f
    }

    // Smear pigment in the actual stroke direction, but reject samples on
    // other sides of strong colour boundaries. No block-colour quantization.
    private fun sampleColor(pixels: IntArray, w: Int, h: Int,
                            x: Int, y: Int, dx: Float, dy: Float, radius: Int): Int {
        val original = pixels[y * w + x]
        var red = (original ushr 16 and 255) * 1.5f
        var green = (original ushr 8 and 255) * 1.5f
        var blue = (original and 255) * 1.5f
        var total = 1.5f
        for (i in intArrayOf(-2,-1,1,2)) {
            val px = (x + dx * radius * i * .5f).toInt().coerceIn(0,w-1)
            val py = (y + dy * radius * i * .5f).toInt().coerceIn(0,h-1)
            val other = pixels[py * w + px]
            val dr = abs((other ushr 16 and 255) - (original ushr 16 and 255))
            val dg = abs((other ushr 8 and 255) - (original ushr 8 and 255))
            val db = abs((other and 255) - (original and 255))
            val difference = (dr + dg + db).toFloat()
            val weight = 1f / (1f + difference * difference * .00012f)
            red += (other ushr 16 and 255) * weight
            green += (other ushr 8 and 255) * weight
            blue += (other and 255) * weight
            total += weight
        }
        return Color.rgb((red/total).toInt().coerceIn(0,255),
            (green/total).toInt().coerceIn(0,255),
            (blue/total).toInt().coerceIn(0,255))
    }

    private fun mixPigment(color: Int, jitter: Float, layer: Int, style: PaintedStyle): Int {
        val variance = (jitter - .5f) * (if (style == PaintedStyle.OIL) 22f else 10f)
        val warm = if (style == PaintedStyle.WATERCOLOR) 4 else 0
        val r = ((color ushr 16 and 255) + variance + warm).toInt().coerceIn(0,255)
        val g = ((color ushr 8 and 255) + variance * .67f + warm).toInt().coerceIn(0,255)
        val b = ((color and 255) + variance * .43f).toInt().coerceIn(0,255)
        return Color.rgb(r,g,b)
    }

    private fun hash(x: Int, y: Int, layer: Int): Int {
        var v = x * 73856093 xor (y * 19349663) xor (layer * 83492791)
        v = (v xor (v ushr 16)) * -2048144789
        v = (v xor (v ushr 13)) * -1028477387
        return v xor (v ushr 16)
    }

    private fun hashUnit(seed: Int): Float = (seed and 65535) / 65535f
}
