package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGridTest {

    @Test
    fun `columns and rows round up to cover a canvas not evenly divisible by tile size`() {
        val grid = TileGrid(canvasWidth = 100, canvasHeight = 50, tileSize = 32)

        assertEquals(4, grid.columns) // ceil(100/32)
        assertEquals(2, grid.rows) // ceil(50/32)
    }

    @Test
    fun `tileBounds clamps the last row and column to the canvas edge`() {
        val grid = TileGrid(canvasWidth = 100, canvasHeight = 50, tileSize = 32)

        assertEquals(DirtyRegion(96, 32, 100, 50), grid.tileBounds(tx = 3, ty = 1))
        assertEquals(DirtyRegion(0, 0, 32, 32), grid.tileBounds(tx = 0, ty = 0))
    }

    @Test
    fun `tileBounds is empty for an out-of-range index`() {
        val grid = TileGrid(canvasWidth = 100, canvasHeight = 50, tileSize = 32)

        assertTrue(grid.tileBounds(tx = -1, ty = 0).isEmpty)
        assertTrue(grid.tileBounds(tx = 4, ty = 0).isEmpty)
    }

    @Test
    fun `tilesTouching a region fully inside one tile returns that single tile`() {
        val grid = TileGrid(canvasWidth = 256, canvasHeight = 256, tileSize = 64)

        val range = grid.tilesTouching(DirtyRegion(10, 10, 20, 20))

        assertEquals(TileGrid.TileRange(0, 0, 0, 0), range)
        assertEquals(1, range.tileCount)
    }

    @Test
    fun `tilesTouching a region spanning a tile boundary returns both tiles`() {
        val grid = TileGrid(canvasWidth = 256, canvasHeight = 256, tileSize = 64)

        val range = grid.tilesTouching(DirtyRegion(60, 60, 70, 70))

        assertEquals(TileGrid.TileRange(0, 0, 1, 1), range)
        assertEquals(4, range.tileCount)
        assertEquals(listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), range.indices())
    }

    @Test
    fun `tilesTouching an exclusive right-bottom edge does not spill into the next tile`() {
        val grid = TileGrid(canvasWidth = 256, canvasHeight = 256, tileSize = 64)

        // right/bottom are exclusive, so a region ending exactly at a tile boundary must not
        // touch the tile beyond it.
        val range = grid.tilesTouching(DirtyRegion(0, 0, 64, 64))

        assertEquals(TileGrid.TileRange(0, 0, 0, 0), range)
    }

    @Test
    fun `tilesTouching clamps to the canvas before computing tile indices`() {
        val grid = TileGrid(canvasWidth = 100, canvasHeight = 100, tileSize = 32)

        val range = grid.tilesTouching(DirtyRegion(-50, -50, 40, 40))

        assertEquals(TileGrid.TileRange(0, 0, 1, 1), range)
    }

    @Test
    fun `tilesTouching is empty for a region entirely outside the canvas`() {
        val grid = TileGrid(canvasWidth = 100, canvasHeight = 100, tileSize = 32)

        val range = grid.tilesTouching(DirtyRegion(200, 200, 250, 250))

        assertTrue(range.isEmpty)
        assertEquals(0, range.tileCount)
        assertTrue(range.indices().isEmpty())
    }

    @Test
    fun `tileSize must be positive`() {
        try {
            TileGrid(canvasWidth = 10, canvasHeight = 10, tileSize = 0)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
