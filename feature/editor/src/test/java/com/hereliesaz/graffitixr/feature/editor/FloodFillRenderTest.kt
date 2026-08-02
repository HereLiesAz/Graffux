package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The paint bucket, against real pixels.
 *
 * Worth its own file because [ImageProcessor.floodFill] is the one piece of this code that could
 * take the whole app down rather than merely render something wrong, and nothing in the previous
 * suite could run a single scanline of it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FloodFillRenderTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    @Test
    fun `fills the contiguous region and stops at a colour boundary`() {
        val b = RenderTestBase.filled(9, 9, Color.WHITE)
        // A black wall down the middle.
        for (y in 0 until 9) b.setPixel(4, y, Color.BLACK)

        ImageProcessor.floodFill(b, 1, 1, Color.RED, tolerance = 8)

        assertEquals(Color.RED, b.getPixel(1, 1))
        assertEquals(Color.RED, b.getPixel(3, 8))
        assertEquals(Color.BLACK, b.getPixel(4, 4))   // the wall
        assertEquals(Color.WHITE, b.getPixel(7, 4))   // the far side
    }

    /**
     * The hang. With a fill colour within tolerance of the seed, a filled pixel still *matches*, so
     * without a visited set each row re-tests and re-pushes its neighbours forever and the stack
     * grows until the process dies. Reachable by eyedropping a colour and nudging it slightly.
     *
     * The timeout is the assertion: before the fix this does not fail, it never returns.
     */
    @Test(timeout = 10_000)
    fun `terminates when the fill colour is within tolerance of the seed`() {
        val b = RenderTestBase.filled(64, 64, Color.argb(255, 0, 0, 0))
        val nearlyTheSame = Color.argb(255, 5, 5, 5)

        ImageProcessor.floodFill(b, 32, 32, nearlyTheSame, tolerance = 32)

        assertEquals(nearlyTheSame, b.getPixel(32, 32))
        assertEquals(nearlyTheSame, b.getPixel(0, 0))
    }

    @Test(timeout = 10_000)
    fun `terminates on a large uniform canvas`() {
        // The same walk without the tolerance trap, at a size where an O(n^2) re-push would show.
        val b = RenderTestBase.filled(256, 256, Color.WHITE)
        ImageProcessor.floodFill(b, 128, 128, Color.BLUE, tolerance = 8)
        assertEquals(Color.BLUE, b.getPixel(0, 0))
        assertEquals(Color.BLUE, b.getPixel(255, 255))
    }

    @Test
    fun `a seed outside the bitmap does nothing`() {
        val b = RenderTestBase.filled(4, 4, Color.WHITE)
        ImageProcessor.floodFill(b, 99, 99, Color.RED)
        assertEquals(Color.WHITE, b.getPixel(2, 2))
    }

    @Test
    fun `the wand spreads exactly where the bucket fills`() {
        // The two are documented as answering the same question. Assert it rather than trusting the
        // comment — the last time it was asserted only in prose, they had drifted apart on the one
        // guard that decides whether one of them halts.
        val art = RenderTestBase.filled(9, 9, Color.WHITE)
        for (y in 0 until 9) art.setPixel(4, y, Color.BLACK)

        val mask = MagicWand.mask(art, 1, 1, tolerance = 8)!!
        val filled = RenderTestBase.filled(9, 9, Color.WHITE)
        for (y in 0 until 9) filled.setPixel(4, y, Color.BLACK)
        ImageProcessor.floodFill(filled, 1, 1, Color.RED, tolerance = 8)

        for (y in 0 until 9) for (x in 0 until 9) {
            assertEquals(
                "at $x,$y",
                filled.getPixel(x, y) == Color.RED,
                mask[y * 9 + x],
            )
        }
    }

    @Test
    fun `the wand treats alpha as a channel`() {
        val b = RenderTestBase.filled(6, 6, Color.TRANSPARENT)
        for (x in 0 until 6) b.setPixel(x, 3, Color.argb(255, 0, 0, 0))
        val mask = MagicWand.mask(b, 0, 0, tolerance = 8)!!
        // Tapping transparent space selects the transparent space, not everything sharing its RGB.
        assertEquals(true, mask[0])
        assertEquals(false, mask[3 * 6 + 0])
        assertNotEquals(mask[0], mask[3 * 6 + 0])
    }
}
