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
 * The Smudge brush, against real pixels.
 *
 * The whole point of this tool is the thing that separates it from the Blur it was mislabelled as
 * for so long: smudge **carries** colour, so it has a direction and leaves a tail, while blur
 * averages a neighbourhood in place and moves nothing. Every test here is built to fail if the
 * implementation quietly degenerates into a blur, a translucent smear of one fixed colour, or a
 * no-op — the three ways this is usually got wrong.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SmudgeRenderTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    /** White, with a solid red block down the left quarter to drag rightwards. */
    private fun redBlock(size: Int = 64): Bitmap =
        RenderTestBase.filled(size, size, Color.WHITE).also { b ->
            for (y in 0 until size) for (x in 0 until size / 4) b.setPixel(x, y, Color.RED)
        }

    /** How far right of the block's edge red is still detectable on row [y]. */
    private fun redReach(b: Bitmap, y: Int): Int {
        var reach = b.width / 4 - 1
        for (x in b.width / 4 until b.width) {
            val p = b.getPixel(x, y)
            // Any meaningful shift away from pure white towards red.
            if (Color.red(p) - Color.green(p) > 20) reach = x
        }
        return reach
    }

    /**
     * The defining behaviour. Red must end up to the *right* of where it started, which is the one
     * thing a blur cannot do at this distance: a blur of radius r cannot move colour more than r,
     * and it would pull white leftwards into the red by exactly as much as it pushed red rightwards.
     */
    @Test
    fun `drags colour along the stroke and not backwards`() = runBlocking {
        val b = redBlock()
        val edge = 16

        val after = ImageProcessor.applyToolToBitmap(
            b, List(40) { Offset((edge - 8 + it).toFloat(), 32f) }, Tool.SMUDGE,
            brushSize = 12f, intensity = 1f,
        )

        assertTrue(
            "red must be carried well past the block edge at x=$edge, reached ${redReach(after, 32)}",
            redReach(after, 32) > edge + 8,
        )
        // Behind the stroke's start the artwork is untouched: nothing was dragged leftwards.
        assertEquals("far left is untouched", Color.RED, after.getPixel(1, 32))
    }

    /**
     * Direction matters, which is the property that makes this a smudge rather than any symmetric
     * neighbourhood filter. The same stroke run the other way must not carry red to the right.
     */
    @Test
    fun `dragging the other way does not carry colour rightwards`() = runBlocking {
        val b = redBlock()

        val rightwards = ImageProcessor.applyToolToBitmap(
            b, List(40) { Offset((8 + it).toFloat(), 32f) }, Tool.SMUDGE,
            brushSize = 12f, intensity = 1f,
        )
        val leftwards = ImageProcessor.applyToolToBitmap(
            b, List(40) { Offset((47 - it).toFloat(), 32f) }, Tool.SMUDGE,
            brushSize = 12f, intensity = 1f,
        )

        assertTrue(
            "a rightward drag must reach further right than a leftward one " +
                "(${redReach(rightwards, 32)} vs ${redReach(leftwards, 32)})",
            redReach(rightwards, 32) > redReach(leftwards, 32),
        )
    }

    /**
     * A blur would produce a *symmetric* exchange across the boundary: as much white pulled left as
     * red pushed right. A smudge is one-directional, so the white side changes far more than the red
     * side does. Asserting the asymmetry catches an implementation that averages instead of carries,
     * even one whose result happens to spread red a little.
     */
    @Test
    fun `the effect is one-sided, unlike a blur`() = runBlocking {
        val b = redBlock()
        val stroke = List(40) { Offset((8 + it).toFloat(), 32f) }

        val smudged = ImageProcessor.applyToolToBitmap(b, stroke, Tool.SMUDGE, brushSize = 12f, intensity = 1f)
        val blurred = ImageProcessor.applyToolToBitmap(b, stroke, Tool.BLUR, brushSize = 12f, intensity = 1f)

        fun changed(after: Bitmap, from: Int, until: Int): Int =
            (from until until).count { b.getPixel(it, 32) != after.getPixel(it, 32) }

        // Inside the red block (left of x=16) versus out on the white (right of it).
        val smudgeRatio = changed(smudged, 16, 56).toFloat() / changed(smudged, 2, 16).coerceAtLeast(1)
        val blurRatio = changed(blurred, 16, 56).toFloat() / changed(blurred, 2, 16).coerceAtLeast(1)

        assertTrue(
            "smudge must disturb the white side far more than the red side, relative to blur " +
                "($smudgeRatio vs $blurRatio)",
            smudgeRatio > blurRatio,
        )
    }

    /** Intensity is how much colour survives each dab, so it is how far the tail runs. */
    @Test
    fun `intensity controls how far the colour is carried`() = runBlocking {
        val b = redBlock()
        val stroke = List(40) { Offset((8 + it).toFloat(), 32f) }

        val weak = ImageProcessor.applyToolToBitmap(b, stroke, Tool.SMUDGE, brushSize = 12f, intensity = 0f)
        val strong = ImageProcessor.applyToolToBitmap(b, stroke, Tool.SMUDGE, brushSize = 12f, intensity = 1f)

        assertTrue(
            "intensity 1 must carry further than intensity 0 " +
                "(${redReach(weak, 32)} vs ${redReach(strong, 32)})",
            redReach(strong, 32) > redReach(weak, 32),
        )
    }

    /** Nowhere near the stroke, nothing moves. */
    @Test
    fun `only the pixels under the stroke change`() = runBlocking {
        val b = redBlock()

        val after = ImageProcessor.applyToolToBitmap(
            b, List(40) { Offset((8 + it).toFloat(), 6f) }, Tool.SMUDGE,
            brushSize = 8f, intensity = 1f,
        )

        for (y in intArrayOf(32, 50, 63)) {
            for (x in intArrayOf(10, 20, 40)) {
                assertEquals(
                    "($x, $y) is nowhere near the stroke",
                    b.getPixel(x, y), after.getPixel(x, y),
                )
            }
        }
    }

    /**
     * Alpha is carried like any other channel — the opposite of Sharpen, on purpose. Dragging the
     * soft edge of a shape has to pull the shape's own edge with it; a smudge that moved only colour
     * would slide the hue off a shape and leave its silhouette sitting there.
     */
    @Test
    fun `alpha is dragged too, so a shape's edge moves with it`() = runBlocking {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 64) {
            b.setPixel(x, y, if (x < 16) Color.argb(255, 255, 0, 0) else Color.TRANSPARENT)
        }

        val after = ImageProcessor.applyToolToBitmap(
            b, List(40) { Offset((8 + it).toFloat(), 32f) }, Tool.SMUDGE,
            brushSize = 12f, intensity = 1f,
        )

        var pulled = 0
        for (x in 16 until 64) if (Color.alpha(after.getPixel(x, 32)) > 8) pulled++
        assertTrue("the transparent side must gain alpha, gained $pulled columns", pulled > 4)
    }

    /** Smudging a flat colour has nothing to carry, so it changes nothing visible. */
    @Test
    fun `flat colour is left alone`() = runBlocking {
        val flat = RenderTestBase.filled(48, 48, Color.argb(255, 90, 140, 200))

        val after = ImageProcessor.applyToolToBitmap(
            flat, List(40) { Offset((4 + it).toFloat(), 24f) }, Tool.SMUDGE,
            brushSize = 12f, intensity = 1f,
        )

        for (x in intArrayOf(10, 24, 38)) {
            assertEquals("($x, 24)", flat.getPixel(x, 24), after.getPixel(x, 24))
        }
    }
}
