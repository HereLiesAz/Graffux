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

    @Test
    fun aHandOutOfRangeIsRefusedAndTheBoardIsLeftAlone() {
        val board = painted()
        val bytes = PaletteBoardPersistence.encodeBrushes(board)
        for (bad in listOf(2, -1, Int.MAX_VALUE)) {
            val b = bytes.copyOf()
            java.nio.ByteBuffer.wrap(b).putInt(8, bad) // magic, version, then hand
            val target = newMixerBoard()
            assertFalse("hand=$bad", PaletteBoardPersistence.decodeBrushes(target, b))
            assertEquals(0, target.hand)
            assertEquals(0f, target.brushes[0].amount[0], 0f)
        }
    }

    @Test
    fun brushConcentrationsAndAmountsAreRangeChecked() {
        fun withFloat(offset: Int, v: Float): ByteArray =
            PaletteBoardPersistence.encodeBrushes(painted()).also { java.nio.ByteBuffer.wrap(it).putFloat(offset, v) }
        val amountOffset = 12 // magic, version, hand, then brush 0's amount
        val concOffset = amountOffset + 4
        for ((what, bytes) in listOf(
            "amount above capacity" to withFloat(amountOffset, 5f),
            "amount NaN" to withFloat(amountOffset, Float.NaN),
            "negative amount" to withFloat(amountOffset, -0.5f),
            "concentration above 1" to withFloat(concOffset, 3f),
            "negative concentration" to withFloat(concOffset, -0.2f),
            "NaN concentration" to withFloat(concOffset, Float.NaN),
        )) {
            val target = newMixerBoard()
            assertFalse(what, PaletteBoardPersistence.decodeBrushes(target, bytes))
            assertEquals(what, 0f, target.brushes[0].amount[0], 0f)
        }
        // a loaded brush whose recipe does not sum to 1 is not a recipe
        val bytes = PaletteBoardPersistence.encodeBrushes(painted())
        for (c in 0 until 16) java.nio.ByteBuffer.wrap(bytes).putFloat(16 + c * 4, 0.01f)
        assertFalse(PaletteBoardPersistence.decodeBrushes(newMixerBoard(), bytes))
    }

    @Test
    fun amountsAboveOneRoundTripLosslesslyUpToTheDocumentedCap() {
        val board = newMixerBoard()
        board.squeeze(2, 20f, 20f, 8f, 0.9f)
        repeat(4) { board.squeeze(2, 20f, 20f, 8f, 0.9f) } // piles paint above 1
        val peak = board.surface.amount.max()
        assertTrue("a texel holds more than 1 ($peak)", peak > 1f)
        assertTrue(peak <= PaletteBoardPersistence.MAX_AMOUNT)
        val back = newMixerBoard()
        val bytes = MaterialStateCodec.encode(PaletteBoardPersistence.snapshot(board))
        assertTrue(PaletteBoardPersistence.restore(back, requireNotNull(MaterialStateCodec.decode(bytes))))
        for (i in board.surface.amount.indices) assertEquals(board.surface.amount[i], back.surface.amount[i], 0f)
    }

    @Test
    fun amountsAboveTheCapAreCappedNotWrapped() {
        val board = newMixerBoard()
        board.squeeze(2, 20f, 20f, 8f, 0.9f)
        board.surface.amount[20 * board.size + 20] = 100f
        val back = newMixerBoard()
        val bytes = MaterialStateCodec.encode(PaletteBoardPersistence.snapshot(board))
        assertTrue(PaletteBoardPersistence.restore(back, requireNotNull(MaterialStateCodec.decode(bytes))))
        assertEquals(PaletteBoardPersistence.MAX_AMOUNT, back.surface.amount[20 * board.size + 20], 0f)
    }

    @Test
    fun restoreRejectsNanNegativeAndHugeAmountsLeavingTheBoardUntouched() {
        val snap = PaletteBoardPersistence.snapshot(painted())
        for (bad in listOf(Float.NaN, -0.5f, Float.POSITIVE_INFINITY, 2f)) { // 2 x scale 8 = 16 > cap
            val heights = snap.heightMap!!.copyOf().also { it[5] = bad }
            val target = newMixerBoard().also { it.squeeze(4, 10f, 10f, 5f, 0.9f) }
            val before = target.surface.amount.copyOf()
            assertFalse("amount=$bad", PaletteBoardPersistence.restore(target, snap.copy(heightMap = heights)))
            assertTrue(before.contentEquals(target.surface.amount))
        }
    }

    @Test
    fun aBrushFilledByTheTransactionToCapacitySurvivesTheRoundTripEvenIfItRoundsPastOne() {
        val board = newMixerBoard()
        board.squeeze(2, 20.008f, 20f, 7f, 0.9f)
        board.dip(20.008f, 20f)
        val held = board.brush.amount[0]
        assertTrue("a full brush ($held)", held > 0.99f)
        val bytes = PaletteBoardPersistence.encodeBrushes(board)
        val fresh = newMixerBoard()
        assertTrue("the file is accepted", PaletteBoardPersistence.decodeBrushes(fresh, bytes))
        assertTrue(fresh.brush.amount[0] <= PaletteBoard.BRUSH_CAPACITY)
        assertEquals(held, fresh.brush.amount[0], 1e-3f)
        assertNotNull(fresh.brushLoad())
    }

    @Test
    fun aRoundingStepOverCapacityIsAcceptedAndClampedButAnExcessiveAmountIsNot() {
        fun amountBytes(v: Float): ByteArray =
            PaletteBoardPersistence.encodeBrushes(painted()).also { java.nio.ByteBuffer.wrap(it).putFloat(12, v) }
        val over = newMixerBoard()
        assertTrue(PaletteBoardPersistence.decodeBrushes(over, amountBytes(1.00005f)))
        assertEquals(PaletteBoard.BRUSH_CAPACITY, over.brushes[0].amount[0], 0f)
        for (bad in listOf(1.01f, 5f, Float.NaN, -0.1f, Float.POSITIVE_INFINITY)) {
            assertFalse("amount=$bad", PaletteBoardPersistence.decodeBrushes(newMixerBoard(), amountBytes(bad)))
        }
    }

    @Test
    fun aNanOrInfiniteConcentrationIsSanitisedNotFatal() {
        val board = painted()
        val i = 20 * board.size + 20
        board.surface.conc[i * 16 + 2] = Float.NaN
        board.surface.conc[i * 16 + 3] = Float.POSITIVE_INFINITY
        val snap = PaletteBoardPersistence.snapshot(board) // used to throw from roundToInt()
        val bytes = MaterialStateCodec.encode(snap)
        assertTrue(PaletteBoardPersistence.restore(newMixerBoard(), requireNotNull(MaterialStateCodec.decode(bytes))))
    }

    @Test
    fun aTexelWithPaintButNoRecipeIsSavedAsEmpty() {
        val board = newMixerBoard()
        val i = 5 * board.size + 5
        board.surface.amount[i] = 0.5f // paint, but every channel is zero
        val snap = PaletteBoardPersistence.snapshot(board)
        assertEquals(0f, snap.heightMap!![i], 0f)
        MaterialStateCodec.encode(snap)
    }

    @Test
    fun theCombinedFileRoundTripsAndRefusesDamage() {
        val board = painted()
        val b = MaterialStateCodec.encode(PaletteBoardPersistence.snapshot(board))
        val k = PaletteBoardPersistence.encodeBrushes(board)
        val packed = PaletteBoardPersistence.pack(b, k)
        val parts = requireNotNull(PaletteBoardPersistence.unpack(packed))
        assertArrayEquals(b, parts.first)
        assertArrayEquals(k, parts.second)
        assertTrue("truncated", PaletteBoardPersistence.unpack(packed.copyOf(packed.size - 3)) == null)
        val flipped = packed.copyOf().also { it[20] = (it[20] + 1).toByte() }
        assertTrue("one flipped byte", PaletteBoardPersistence.unpack(flipped) == null)
        assertTrue("not a palette", PaletteBoardPersistence.unpack(byteArrayOf(1, 2, 3)) == null)
    }
}
