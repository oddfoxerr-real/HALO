package dev.oddfoxerr.halo

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaArtworkColorTest {
    @Test fun selectsDominantHueInsteadOfSingleBrightOutlier() {
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 32) for (x in 0 until 32) {
                image.setPixel(x, y,
                    if (x < 28) Color.rgb(36, 87, 211) else Color.rgb(255, 24, 28))
            }
            val colors = MediaArtworkColor.fromBitmap(
                image, Color.WHITE, Color.YELLOW)
            val hsv = FloatArray(3)
            Color.colorToHSV(colors.primary, hsv)
            assertTrue("Dominant blue wins over a red stripe", hsv[0] in 180f..260f)
            assertTrue("Output is legible on dark edges", hsv[2] >= .78f)
            assertTrue("Avoid washed-out gray", hsv[1] >= .55f)
            assertNotEquals("Primary and accent differ", colors.primary, colors.accent)
        } finally { image.recycle() }
    }

    @Test fun neutralArtworkUsesTheSavedManualPalette() {
        val image = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.rgb(120, 120, 120))
            val result = MediaArtworkColor.fromBitmap(image, Color.CYAN, Color.MAGENTA)
            assertEquals(Color.CYAN, result.primary)
            assertEquals(Color.MAGENTA, result.accent)
        } finally { image.recycle() }
    }
}
