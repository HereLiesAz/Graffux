package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Palette paint and undo/redo (spec/painterly.md 6.5): a stroke's spending is part of the stroke. */
class PaletteLoadHistoryTest {
    private val set = PlaceholderPigments.set
    private val tubes = listOf("titanium-white", "cadmium-yellow", "phthalo-blue").map { id -> set.pigments.first { it.id == id } }

    private fun loaded(): PaletteBoard = PaletteBoard(48, tubes).also {
        it.squeeze(1, 12f, 12f, 8f, 1f)
        repeat(5) { _ -> it.dip(12f, 12f) }
    }

    /** A committed stroke: the brush spends part of its load. */
    private fun stroke(b: PaletteBoard, h: PaletteLoadHistory, key: Long): Pair<PaletteBoard.BrushState, PaletteBoard.BrushState> {
        val before = b.brushState()
        b.absorbStrokeLoad(0.5f, b.brush.amount[0])
        val after = b.brushState()
        h.record(key, before, after)
        return before to after
    }

    @Test
    fun undoGivesThePaintBackAndRedoSpendsItAgain() {
        val b = loaded()
        val h = PaletteLoadHistory()
        val (before, after) = stroke(b, h, 1)
        assertTrue(after.amount < before.amount)
        assertTrue(h.undo(1, b))
        assertEquals(before.amount, b.brush.amount[0], 1e-6f)
        assertTrue(h.redo(1, b))
        assertEquals(after.amount, b.brush.amount[0], 1e-6f)
    }

    @Test
    fun aBrushThatHasMovedOnIsNotOverwrittenByAnOldLoad() {
        val b = loaded()
        val h = PaletteLoadHistory()
        stroke(b, h, 1)
        b.dip(12f, 12f) // the user dipped again after the stroke
        val now = b.brush.amount[0]
        assertFalse(h.undo(1, b), "undo must not put an old load over a newer dip")
        assertEquals(now, b.brush.amount[0], 0f)
    }

    @Test
    fun twoStrokesUndoNewestFirstAndRedoOldestFirst() {
        val b = loaded()
        val h = PaletteLoadHistory()
        val (b1, a1) = stroke(b, h, 1)
        val (b2, a2) = stroke(b, h, 2)
        assertEquals(a1.amount, b2.amount, 1e-6f)
        assertTrue(h.undo(2, b)); assertEquals(b2.amount, b.brush.amount[0], 1e-6f)
        assertTrue(h.undo(1, b)); assertEquals(b1.amount, b.brush.amount[0], 1e-6f)
        assertTrue(h.redo(1, b)); assertEquals(a1.amount, b.brush.amount[0], 1e-6f)
        assertTrue(h.redo(2, b)); assertEquals(a2.amount, b.brush.amount[0], 1e-6f)
    }

    @Test
    fun aStrokeThatWasNeverRecordedSpentNothingAndHasNothingToUndo() {
        // a cancelled stroke, or a commit that returned early, never reaches record(): the board is untouched
        val b = loaded()
        val h = PaletteLoadHistory()
        val held = b.brush.amount[0]
        assertFalse(h.undo(99, b))
        assertFalse(h.redo(99, b))
        assertEquals(held, b.brush.amount[0], 0f)
    }

    @Test
    fun theHistoryIsBoundedAndForgets() {
        val b = loaded()
        val h = PaletteLoadHistory(capacity = 3)
        val s = b.brushState()
        (1L..10L).forEach { h.record(it, s, s) }
        assertEquals(3, h.size)
        assertFalse(h.undo(1, b), "the oldest were dropped")
        h.forget(10)
        assertEquals(2, h.size)
    }

    @Test
    fun theWholeColourOfTheBrushComesBackNotJustHowMuch() {
        val b = loaded()
        val h = PaletteLoadHistory()
        val colourBefore = b.brushColour()
        val before = b.brushState()
        // the stroke spends paint and the brush picks up some canvas colour (its recipe changes)
        b.brush.conc[2] = 0.3f
        b.brush.conc[1] = 0.7f
        b.absorbStrokeLoad(0.6f, b.brush.amount[0])
        h.record(7, before, b.brushState())
        assertTrue(h.undo(7, b))
        assertEquals(colourBefore, b.brushColour())
    }

    @Test
    fun switchingTheHandAfterTheStrokeDoesNotStopTheRefund() {
        val b = loaded() // foreground (hand 0) holds the paint
        val h = PaletteLoadHistory()
        val (before, _) = stroke(b, h, 1)
        b.hand = 1 // the user selects the background brush in the mixer
        assertTrue(h.undo(1, b), "the foreground brush still holds exactly what the stroke left")
        assertEquals(before.amount, b.brushes[0].amount[0], 1e-6f)
        assertEquals(1, b.hand, "and the selection is left where the user put it")
    }

    @Test
    fun undoSwitchHandRedoSpendsTheRecordedBrushNotTheSelectedOne() {
        val b = loaded()
        val h = PaletteLoadHistory()
        val (before, after) = stroke(b, h, 1)
        assertTrue(h.undo(1, b))
        b.hand = 1
        assertTrue(h.redo(1, b))
        assertEquals(after.amount, b.brushes[0].amount[0], 1e-6f)
        assertEquals(0f, b.brushes[1].amount[0], 0f, "the other brush is untouched")
        assertEquals(before.hand, 0)
    }

    @Test
    fun theOtherBrushBeingToppedUpOrMixedDoesNotMatter() {
        val b = loaded()
        val h = PaletteLoadHistory()
        val (before, _) = stroke(b, h, 1)
        b.hand = 1
        b.squeeze(2, 30f, 30f, 6f, 1f)
        repeat(3) { b.dip(30f, 30f) } // the background brush is loaded meanwhile
        val background = b.brushes[1].amount[0]
        assertTrue(h.undo(1, b))
        assertEquals(before.amount, b.brushes[0].amount[0], 1e-6f)
        assertEquals(background, b.brushes[1].amount[0], 0f, "the background brush keeps what it has")
    }

    @Test
    fun theRecordedBrushChangedByAnotherStrokeIsStillRefused() {
        val b = loaded()
        val h = PaletteLoadHistory()
        stroke(b, h, 1)
        stroke(b, h, 2) // a later stroke spent more from the same brush
        b.hand = 1
        assertFalse(h.undo(1, b), "stroke 1's after-state is no longer what the brush holds")
        assertTrue(h.undo(2, b))
        assertTrue(h.undo(1, b), "in order it works")
    }

    @Test
    fun aMixOnTheRecordedBrushAfterwardsRefusesTheRefund() {
        val b = loaded()
        val h = PaletteLoadHistory()
        stroke(b, h, 1)
        b.hand = 1
        b.hand = 0
        b.drag(floatArrayOf(12f, 12f, 30f, 12f)) // the recorded brush was mixed
        assertFalse(h.undo(1, b))
    }
}
