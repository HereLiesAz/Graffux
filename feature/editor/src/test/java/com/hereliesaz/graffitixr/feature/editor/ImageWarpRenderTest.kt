package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ImageWarp] moving actual pixels.
 *
 * The grid maths was already unit-tested; what could not be was whether `setPolyToPoly` and
 * `drawBitmapMesh` were being *fed* correctly — argument order, row-major layout, quad counts. Those
 * are exactly the mistakes that produce a mirrored or transposed image rather than an exception.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ImageWarpRenderTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    /** A bitmap with one quadrant coloured, so an orientation error is visible rather than subtle. */
    private fun quadrants(size: Int = 32) =
        RenderTestBase.filled(size, size, Color.BLACK).also { b ->
            for (y in 0 until size / 2) for (x in 0 until size / 2) b.setPixel(x, y, Color.RED)
        }

    @Test
    fun `the identity grid leaves the image where it was`() {
        // The property that catches a transposed or mirrored feed: warping to the grid the image
        // already sits on must be a no-op, in both the 2x2 and the mesh path.
        val src = quadrants()
        for (n in listOf(2, 4)) {
            val out = ImageWarp.warp(src, ImageWarp.identityGrid(n, 32f, 32f))!!
            assertEquals("n=$n top-left", Color.RED, out.getPixel(4, 4))
            assertEquals("n=$n top-right", Color.BLACK, out.getPixel(28, 4))
            assertEquals("n=$n bottom-left", Color.BLACK, out.getPixel(4, 28))
        }
    }

    @Test
    fun `a distort actually moves pixels`() {
        val src = quadrants()
        // Squash the whole image into the left half: the right half must end up empty.
        val dst = listOf(
            Offset(0f, 0f), Offset(16f, 0f),
            Offset(0f, 32f), Offset(16f, 32f),
        )
        val out = ImageWarp.warp(src, dst)!!
        assertEquals(Color.RED, out.getPixel(2, 2))
        assertEquals(0, RenderTestBase.alphaAt(out, 28, 16))
    }

    @Test
    fun `a mesh warp actually moves pixels`() {
        val src = quadrants()
        val grid = ImageWarp.identityGrid(4, 32f, 32f).toMutableList()
        // Drag the whole top row down by a third of the image.
        for (i in 0 until 4) grid[i] = grid[i] + Offset(0f, 12f)
        val out = ImageWarp.warp(src, grid)!!
        assertNotEquals("the top strip should have been vacated", Color.RED, out.getPixel(4, 2))
        assertTrue("the red quadrant should still exist lower down", (0 until 32).any {
            out.getPixel(4, it) == Color.RED
        })
    }

    @Test
    fun `a degenerate quad leaves the pixels alone rather than rendering garbage`() {
        // setPolyToPoly refuses a collapsed quad; that refusal must surface as "unchanged", not as
        // an exception and not as an arbitrary transform.
        val src = quadrants()
        val collapsed = List(4) { Offset(10f, 10f) }
        val out = ImageWarp.warp(src, collapsed)!!
        assertEquals(Color.RED, out.getPixel(4, 4))
        assertEquals(Color.BLACK, out.getPixel(28, 28))
    }

    @Test
    fun `a non-square handle list is refused`() {
        assertNull(ImageWarp.warp(quadrants(), listOf(Offset.Zero, Offset(1f, 1f))))
    }

    @Test
    fun `the source is never mutated`() {
        val src = quadrants()
        ImageWarp.warp(src, listOf(Offset(0f, 0f), Offset(8f, 0f), Offset(0f, 8f), Offset(8f, 8f)))
        assertEquals(Color.RED, src.getPixel(4, 4))
        assertEquals(Color.BLACK, src.getPixel(28, 28))
    }
}
