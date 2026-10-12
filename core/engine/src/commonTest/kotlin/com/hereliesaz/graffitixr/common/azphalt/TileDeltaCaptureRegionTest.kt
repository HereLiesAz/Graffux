package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileDeltaCaptureRegionTest {

    private val width = 150
    private val height = 97
    private val grid = TileGrid(width, height, 32)

    private fun canvas(seed: Int) = IntArray(width * height) { (it * 2654435761L.toInt()) xor seed }

    /** The tile-aligned region a stroke rectangle covers, and its pixels cut out of [full]. */
    private fun regionOf(painted: DirtyRegion): DirtyRegion {
        val tiles = grid.tilesTouching(painted)
        val first = grid.tileBounds(tiles.txMin, tiles.tyMin)
        val last = grid.tileBounds(tiles.txMax, tiles.tyMax)
        return DirtyRegion(first.left, first.top, last.right, last.bottom)
    }

    private fun cut(full: IntArray, r: DirtyRegion) = IntArray(r.width * r.height) { i ->
        full[(r.top + i / r.width) * width + r.left + i % r.width]
    }

    @Test
    fun regionCaptureMatchesWholeCanvasCapture() {
        val before = canvas(7)
        val after = canvas(91)
        // Includes the ragged last column and row of tiles.
        for (painted in listOf(DirtyRegion(40, 10, 70, 50), DirtyRegion(120, 80, 150, 97), DirtyRegion(0, 0, 1, 1))) {
            val region = regionOf(painted)
            val expected = TileDelta.capture(before, after, grid, grid.tilesTouching(painted))
            val actual = TileDelta.captureRegion(cut(before, region), cut(after, region), region, grid)
            assertTrue(expected.isNotEmpty())
            assertEquals(expected, actual, "painted=$painted")
        }
    }

    @Test
    fun emptyOrShortRegionCapturesNothing() {
        val region = DirtyRegion(0, 0, 32, 32)
        assertTrue(TileDelta.captureRegion(IntArray(3), IntArray(3), region, grid).isEmpty())
        assertTrue(TileDelta.captureRegion(IntArray(0), IntArray(0), DirtyRegion(0, 0, 0, 0), grid).isEmpty())
    }
}
