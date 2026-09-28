package com.hereliesaz.graffitixr.feature.editor.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ledger as EditorViewModel drives it: add on commit, [InkStrokeLedger.onUndo] after an undo
 * that went through, [InkStrokeLedger.onRedo] with the stroke (or null for a non-Ink entry).
 */
class InkStrokeLedgerTest {
    /** Stands in for a StrokeCommand: compared by identity, like the real key. */
    private class Key

    @Test
    fun followsUndoAndRedoInOrder() {
        val ledger = InkStrokeLedger<String>()
        val a = Key()
        val b = Key()
        ledger.add("L", a, "a")
        ledger.add("L", b, "b")

        ledger.onUndo("L", b)
        assertEquals(listOf("a"), ledger.strokes("L"))
        ledger.onUndo("L", a)
        assertEquals(emptyList<String>(), ledger.strokes("L"))
        assertFalse("an emptied layer is dropped", "L" in ledger.layerIds())

        ledger.onRedo("L", a, "a")
        ledger.onRedo("L", b, "b")
        assertEquals(listOf("a", "b"), ledger.strokes("L"))
    }

    @Test
    fun nonInkHistoryEntriesLeaveItAlone() {
        val ledger = InkStrokeLedger<String>()
        val ink = Key()
        ledger.add("L", ink, "ink")
        val roundStroke = Key()
        ledger.onUndo("L", roundStroke) // a round-brush stroke undone: not in the ledger
        ledger.onRedo("L", roundStroke, null)
        assertEquals(listOf("ink"), ledger.strokes("L"))
    }

    @Test
    fun keysAreIdentityNotEquality() {
        val ledger = InkStrokeLedger<String>()
        data class Equal(val n: Int)
        val first = Equal(1)
        val second = Equal(1)
        ledger.add("L", first, "first")
        ledger.add("L", second, "second")
        ledger.onUndo("L", first)
        assertEquals(listOf("second"), ledger.strokes("L"))
    }

    @Test
    fun layersAreIndependentAndClearForgetsEverything() {
        val ledger = InkStrokeLedger<String>()
        ledger.add("A", Key(), "a")
        ledger.add("B", Key(), "b")
        assertEquals(setOf("A", "B"), ledger.layerIds())
        assertTrue(ledger.strokes("C").isEmpty())
        ledger.clear()
        assertTrue(ledger.layerIds().isEmpty())
    }
}
