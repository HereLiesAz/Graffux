package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handle-grid maths behind Distort and Warp. The bitmap resampling itself needs a real Canvas
 * and is left to instrumentation; what is worth pinning here is the grid, since every other part of
 * the feature — the overlay's line drawing, the drag hit-test, the bake — indexes into it and would
 * fail in a different confusing way if its order or shape were wrong.
 */
class ImageWarpTest {

    @Test
    fun `a 2x2 identity grid is the four corners in reading order`() {
        // setPolyToPoly is fed straight from this, and it wants top-left, top-right, bottom-left,
        // bottom-right. Reorder it and a Distort mirrors or shears instead of applying perspective.
        assertEquals(
            listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(0f, 50f), Offset(100f, 50f)),
            ImageWarp.identityGrid(2, 100f, 50f),
        )
    }

    @Test
    fun `a 4x4 identity grid is evenly spaced and row-major`() {
        val grid = ImageWarp.identityGrid(4, 90f, 90f)
        assertEquals(16, grid.size)
        // Row-major is what drawBitmapMesh expects; column-major would transpose the artwork.
        assertEquals(Offset(0f, 0f), grid[0])
        assertEquals(Offset(30f, 0f), grid[1])
        assertEquals(Offset(0f, 30f), grid[4])
        assertEquals(Offset(90f, 90f), grid[15])
    }

    @Test
    fun `an identity grid honours its origin`() {
        val grid = ImageWarp.identityGrid(2, 10f, 10f, origin = Offset(5f, 7f))
        assertEquals(Offset(5f, 7f), grid.first())
        assertEquals(Offset(15f, 17f), grid.last())
    }

    @Test
    fun `a grid smaller than two per side has no handles`() {
        assertTrue(ImageWarp.identityGrid(1, 10f, 10f).isEmpty())
        assertTrue(ImageWarp.identityGrid(0, 10f, 10f).isEmpty())
    }

    @Test
    fun `gridSizeOf recovers the side length of a square grid`() {
        assertEquals(2, ImageWarp.gridSizeOf(ImageWarp.identityGrid(2, 1f, 1f)))
        assertEquals(4, ImageWarp.gridSizeOf(ImageWarp.identityGrid(4, 1f, 1f)))
        assertEquals(5, ImageWarp.gridSizeOf(ImageWarp.identityGrid(5, 1f, 1f)))
    }

    @Test
    fun `gridSizeOf rejects a handle list that is not square`() {
        // The bake reads the mesh dimensions back out of the list length, so a non-square list has
        // no meaning — better to refuse it than to resample the artwork through a guess.
        val notSquare = ImageWarp.identityGrid(3, 1f, 1f).drop(1)
        assertEquals(8, notSquare.size)
        assertEquals(0, ImageWarp.gridSizeOf(notSquare))
        assertEquals(0, ImageWarp.gridSizeOf(emptyList()))
        assertEquals(0, ImageWarp.gridSizeOf(listOf(Offset.Zero, Offset(1f, 1f))))
    }

    @Test
    fun `warp refuses a destination that is not a square grid`() {
        // Null rather than a partial render: the caller falls back to leaving the layer alone.
        assertNull(ImageWarp.warp(FakeBitmaps.stub(), listOf(Offset.Zero, Offset(1f, 1f))))
    }
}

/**
 * The one thing these tests need a Bitmap for is proving [ImageWarp.warp] rejects a bad grid before
 * it touches any pixels — so the stub only has to exist, not to hold any.
 */
private object FakeBitmaps {
    fun stub(): android.graphics.Bitmap = io.mockk.mockk(relaxed = true)
}
