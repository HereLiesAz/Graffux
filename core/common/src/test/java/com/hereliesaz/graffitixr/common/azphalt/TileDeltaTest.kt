package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileDeltaTest {

    private fun canvas(width: Int, height: Int, value: Int) = IntArray(width * height) { value }

    @Test
    fun `capture is empty for an empty touched range`() {
        val grid = TileGrid(canvasWidth = 64, canvasHeight = 64, tileSize = 16)
        val before = canvas(64, 64, 0)
        val after = canvas(64, 64, 1)

        val snapshots = TileDelta.capture(before, after, grid, TileGrid.TileRange(0, 0, -1, -1))

        assertTrue(snapshots.isEmpty())
    }

    @Test
    fun `capture is empty when a buffer doesn't match the canvas size`() {
        val grid = TileGrid(canvasWidth = 64, canvasHeight = 64, tileSize = 16)
        val before = canvas(64, 64, 0)
        val tooSmall = IntArray(10)

        val snapshots = TileDelta.capture(before, tooSmall, grid, grid.tilesTouching(DirtyRegion(0, 0, 16, 16)))

        assertTrue(snapshots.isEmpty())
    }

    @Test
    fun `capture pulls exactly the touched tiles' pixels, in row-major order`() {
        val width = 32
        val height = 32
        val before = IntArray(width * height) { it } // distinct value per pixel
        val after = IntArray(width * height) { it + 1_000_000 }
        val grid = TileGrid(canvasWidth = width, canvasHeight = height, tileSize = 16)

        val touched = grid.tilesTouching(DirtyRegion(0, 0, 16, 16)) // exactly tile (0,0)
        val snapshots = TileDelta.capture(before, after, grid, touched)

        assertEquals(1, snapshots.size)
        val snapshot = snapshots.first()
        assertEquals(0, snapshot.tx)
        assertEquals(0, snapshot.ty)
        assertEquals(DirtyRegion(0, 0, 16, 16), snapshot.bounds)
        assertEquals(16 * 16, snapshot.before.size)
        // Row-major within the tile: first row is pixels [0..15], matching `before`'s own values
        // for that same 16x16 top-left block (canvas width 32, so row stride is 32).
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                assertEquals(before[y * width + x], snapshot.before[y * 16 + x])
                assertEquals(after[y * width + x], snapshot.after[y * 16 + x])
            }
        }
    }

    @Test
    fun `applyBefore restores only the touched tiles, leaving the rest of the canvas untouched`() {
        val width = 32
        val height = 32
        val before = canvas(width, height, 11)
        val after = canvas(width, height, 22)
        val grid = TileGrid(canvasWidth = width, canvasHeight = height, tileSize = 16)
        val touched = grid.tilesTouching(DirtyRegion(0, 0, 16, 16)) // top-left tile only
        val snapshots = TileDelta.capture(before, after, grid, touched)

        // Simulate the canvas as it stands after the edit (all `after`), then undo.
        val canvas = after.copyOf()
        TileDelta.applyBefore(canvas, width, snapshots)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val expected = if (x < 16 && y < 16) 11 else 22
                assertEquals("mismatch at ($x,$y)", expected, canvas[idx])
            }
        }
    }

    @Test
    fun `applyAfter is the inverse of applyBefore -- a redo reproduces the edited state exactly`() {
        val width = 32
        val height = 32
        val before = canvas(width, height, 5)
        val after = IntArray(width * height) { it }
        val grid = TileGrid(canvasWidth = width, canvasHeight = height, tileSize = 16)
        val touched = grid.tilesTouching(DirtyRegion(8, 8, 24, 24)) // spans all 4 tiles
        val snapshots = TileDelta.capture(before, after, grid, touched)

        val canvas = before.copyOf()
        TileDelta.applyBefore(canvas, width, snapshots) // no-op: already `before`
        assertArrayEquals(before, canvas)

        TileDelta.applyAfter(canvas, width, snapshots)
        // Every pixel touched should now match `after`; anything outside the touched tiles was
        // never captured and must remain at its `before` value.
        val fullyTouchedTiles = touched.indices().toSet()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val tx = x / 16
                val ty = y / 16
                val expected = if ((tx to ty) in fullyTouchedTiles) after[idx] else before[idx]
                assertEquals("mismatch at ($x,$y)", expected, canvas[idx])
            }
        }
    }

    @Test
    fun `round trip through capture, applyBefore, applyAfter is lossless for a mixed pattern`() {
        val width = 40
        val height = 24
        val before = IntArray(width * height) { (it * 7) xor 0x1234 }
        val after = IntArray(width * height) { (it * 13) xor 0x5678 }
        val grid = TileGrid(canvasWidth = width, canvasHeight = height, tileSize = 9) // uneven tiling
        val touched = grid.tilesTouching(DirtyRegion(3, 3, 33, 20))
        val snapshots = TileDelta.capture(before, after, grid, touched)
        val touchedTiles = touched.indices().toSet()

        val canvas = after.copyOf()
        TileDelta.applyBefore(canvas, width, snapshots)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val touchedHere = (x / 9 to y / 9) in touchedTiles
                val expected = if (touchedHere) before[idx] else after[idx]
                assertEquals("mismatch after applyBefore at ($x,$y)", expected, canvas[idx])
            }
        }

        TileDelta.applyAfter(canvas, width, snapshots)
        assertArrayEquals(after, canvas)
    }
}
