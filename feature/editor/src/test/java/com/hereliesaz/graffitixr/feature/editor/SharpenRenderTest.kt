package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Sharpen brush, against real pixels.
 *
 * Sharpening is easy to write in a way that *looks* right and is measurably wrong — an unsharp mask
 * with the sign flipped blurs, one applied to alpha eats the layer's silhouette, and one that
 * ignores the stroke mask sharpens the whole layer at once. None of those is visible in a diff, and
 * all three are visible in the numbers below.
 *
 * The measure throughout is **local contrast across a hard edge**: the difference between the pixel
 * on the light side and the pixel on the dark side. That is the one quantity sharpening is defined
 * to increase and blurring is defined to decrease, so it tells the two apart no matter what else the
 * pass does to the image.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SharpenRenderTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    /** A left-half-dark / right-half-light image with a soft ramp across the middle to sharpen. */
    private fun rampedEdge(size: Int = 64): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val mid = size / 2
        for (y in 0 until size) {
            for (x in 0 until size) {
                // A four-pixel linear ramp centred on the middle: 60 on the left, 200 on the right.
                val t = ((x - (mid - 2)).toFloat() / 4f).coerceIn(0f, 1f)
                val v = (60 + t * 140).toInt()
                b.setPixel(x, y, Color.argb(255, v, v, v))
            }
        }
        return b
    }

    /** Red channel contrast across the ramp on row [y]: right sample minus left sample. */
    private fun edgeContrast(b: Bitmap, y: Int): Int {
        val mid = b.width / 2
        return Color.red(b.getPixel(mid + 2, y)) - Color.red(b.getPixel(mid - 2, y))
    }

    private fun stroke(size: Int) = List(size) { Offset(it.toFloat(), (size / 2).toFloat()) }

    @Test
    fun `sharpen increases contrast across an edge and blur decreases it`() = runBlocking {
        val before = rampedEdge()
        val baseline = edgeContrast(before, 32)

        val sharpened = ImageProcessor.applyToolToBitmap(
            before, stroke(64), Tool.SHARPEN, brushSize = 24f, intensity = 0.5f,
        )
        val blurred = ImageProcessor.applyToolToBitmap(
            before, stroke(64), Tool.BLUR, brushSize = 24f, intensity = 0.5f,
        )

        // Both are compared to the same untouched baseline, and to each other. Asserting only
        // "sharpen differs from the original" would pass just as happily if it blurred.
        assertTrue(
            "sharpen must raise edge contrast (was $baseline, now ${edgeContrast(sharpened, 32)})",
            edgeContrast(sharpened, 32) > baseline,
        )
        assertTrue(
            "blur must lower edge contrast (was $baseline, now ${edgeContrast(blurred, 32)})",
            edgeContrast(blurred, 32) < baseline,
        )
    }

    @Test
    fun `intensity scales how hard it sharpens`() = runBlocking {
        val before = rampedEdge()
        val gentle = ImageProcessor.applyToolToBitmap(
            before, stroke(64), Tool.SHARPEN, brushSize = 24f, intensity = 0f,
        )
        val hard = ImageProcessor.applyToolToBitmap(
            before, stroke(64), Tool.SHARPEN, brushSize = 24f, intensity = 1f,
        )

        assertTrue(
            "intensity 1 must sharpen harder than intensity 0 " +
                "(${edgeContrast(gentle, 32)} vs ${edgeContrast(hard, 32)})",
            edgeContrast(hard, 32) > edgeContrast(gentle, 32),
        )
    }

    /**
     * The stroke is a mask, not a switch. A sharpen applied to the whole layer instead of only where
     * the brush went is the single most likely way to get this branch wrong, because on a test image
     * with an edge running through it the result still *looks* sharpened.
     */
    @Test
    fun `only the pixels under the stroke change`() = runBlocking {
        val before = rampedEdge()
        // A short stroke across the top of the image only.
        val short = List(64) { Offset(it.toFloat(), 6f) }

        val after = ImageProcessor.applyToolToBitmap(
            before, short, Tool.SHARPEN, brushSize = 8f, intensity = 1f,
        )

        assertTrue("row 6 is under the stroke and must change", edgeContrast(after, 6) > edgeContrast(before, 6))
        for (y in intArrayOf(32, 50, 63)) {
            assertEquals(
                "row $y is nowhere near the stroke and must be untouched",
                before.getPixel(30, y), after.getPixel(30, y),
            )
        }
    }

    /**
     * Alpha is carried through untouched. Sharpening the alpha channel would ratchet a soft edge
     * towards fully-opaque/fully-transparent, so painting along the boundary of a shape would leave
     * the shape's own outline ragged — damage to the artwork rather than to the pixels being worked
     * on, and invisible on an opaque test image.
     */
    @Test
    fun `a soft alpha edge keeps its alpha`() = runBlocking {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                // A gradient in alpha, with the colour channels carrying an edge to sharpen so the
                // branch has something to do.
                val a = (x * 4).coerceIn(0, 255)
                val v = if (x < 32) 60 else 200
                b.setPixel(x, y, Color.argb(a, v, v, v))
            }
        }
        val alphaBefore = IntArray(64) { Color.alpha(b.getPixel(it, 32)) }

        val after = ImageProcessor.applyToolToBitmap(
            b, stroke(64), Tool.SHARPEN, brushSize = 32f, intensity = 1f,
        )

        for (x in 0 until 64) {
            assertEquals("alpha at x=$x", alphaBefore[x], Color.alpha(after.getPixel(x, 32)))
        }
    }

    /** A flat region has no local contrast to recover, so there is nothing for sharpen to do to it. */
    @Test
    fun `flat colour is left alone`() = runBlocking {
        val flat = RenderTestBase.filled(48, 48, Color.argb(255, 128, 128, 128))

        val after = ImageProcessor.applyToolToBitmap(
            flat, List(48) { Offset(it.toFloat(), 24f) }, Tool.SHARPEN,
            brushSize = 20f, intensity = 1f,
        )

        // Not exactly equal: cheapBlur downscales and back, so a flat region can drift by a unit
        // near the bitmap's edges. Within a hair is the honest assertion.
        for (x in intArrayOf(10, 24, 38)) {
            assertEquals("red at x=$x", 128.0, Color.red(after.getPixel(x, 24)).toDouble(), 2.0)
        }
    }
}
