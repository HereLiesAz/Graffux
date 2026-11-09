package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaletteBoard
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteBoardPersistenceTest {
    private fun painted(): PaletteBoard = newMixerBoard().also {
        it.squeeze(2, 20f, 20f, 8f, 0.9f)
        it.squeeze(4, 60f, 20f, 8f, 0.9f)
        repeat(3) { _ -> it.dip(20f, 20f) }
        it.drag(floatArrayOf(20f, 20f, 40f, 20f, 60f, 20f))
        it.hand = 1
        it.dip(60f, 20f)
    }

    @Test
    fun theBoardSurvivesTheSidecarRoundTripWithItsRecipes() {
        val board = painted()
        val bytes = MaterialStateCodec.encode(PaletteBoardPersistence.snapshot(board))
        val decoded = requireNotNull(MaterialStateCodec.decode(bytes))
        val fresh = newMixerBoard()
        assertTrue(PaletteBoardPersistence.restore(fresh, decoded))
        for (i in board.surface.amount.indices) assertEquals(board.surface.amount[i], fresh.surface.amount[i], 1e-6f)
        // recipes are byte-exact (a recipe is stored as 255ths and was already quantised that way)
        for (i in board.surface.conc.indices) assertEquals(board.surface.conc[i], fresh.surface.conc[i], 1e-6f)
        assertEquals(board.recipeAt(20, 20).tubes.map { it.first.id }, fresh.recipeAt(20, 20).tubes.map { it.first.id })
    }

    @Test
    fun aBoardForADifferentTubeSetIsNotRestored() {
        val snap = PaletteBoardPersistence.snapshot(painted())
        val other = com.hereliesaz.graffitixr.common.azphalt.PaletteBoard(
            MIXER_BOARD_SIZE,
            MIXER_TUBES.reversed(),
        )
        assertFalse(PaletteBoardPersistence.restore(other, snap))
        assertEquals(0f, other.surface.amount.sum(), 0f)
        val small = PaletteBoard(32, MIXER_TUBES)
        assertFalse(PaletteBoardPersistence.restore(small, snap))
    }

    @Test
    fun theTwoBrushLoadsAndTheHandSurvive() {
        val board = painted()
        val bytes = PaletteBoardPersistence.encodeBrushes(board)
        val fresh = newMixerBoard()
        assertTrue(PaletteBoardPersistence.decodeBrushes(fresh, bytes))
        assertEquals(1, fresh.hand)
        for (i in 0..1) {
            assertEquals(board.brushes[i].amount[0], fresh.brushes[i].amount[0], 0f)
            assertArrayEquals(board.brushes[i].conc, fresh.brushes[i].conc, 0f)
        }
        assertNotNull(fresh.brushLoad())
    }

    @Test
    fun corruptBrushDataIsRefusedAndLeavesTheBoardAlone() {
        val board = painted()
        val before = board.brushes[0].amount[0]
        assertFalse(PaletteBoardPersistence.decodeBrushes(board, byteArrayOf(1, 2, 3)))
        val bad = PaletteBoardPersistence.encodeBrushes(board)
        bad[bad.size - 1] = 0x7F // a NaN/huge float tail
        bad[bad.size - 2] = 0x7F
        bad[bad.size - 3] = 0xFF.toByte()
        bad[bad.size - 4] = 0xFF.toByte()
        PaletteBoardPersistence.decodeBrushes(board, bad)
        assertEquals(before, board.brushes[0].amount[0], 1e-3f)
    }
}
