package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.DirtyRegion
import com.hereliesaz.graffitixr.common.azphalt.TileDelta
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    private fun layer(id: String) = Layer(id = id, name = id)
    private fun stroke() = StrokeCommand(
        path = emptyList(),
        canvasSize = IntSize(1, 1),
        tool = Tool.NONE,
        brushSize = 1f,
        brushColor = 0,
        intensity = 0.5f,
    )

    @Test
    fun `starts empty`() {
        val h = EditHistory()
        assertEquals(0, h.undoCount)
        assertEquals(0, h.redoCount)
    }

    @Test
    fun `pushProperty deduplicates an identical consecutive snapshot`() {
        val h = EditHistory()
        val snapshot = listOf(layer("a"))
        assertTrue(h.pushProperty(snapshot))
        assertEquals(false, h.pushProperty(listOf(layer("a")))) // structurally equal -> ignored
        assertEquals(1, h.undoCount)
    }

    @Test
    fun `pushProperty trims to the max stack size`() {
        val h = EditHistory(maxStackSize = 2)
        h.pushProperty(listOf(layer("a")))
        h.pushProperty(listOf(layer("b")))
        h.pushProperty(listOf(layer("c")))
        assertEquals(2, h.undoCount)
    }

    @Test
    fun `pushing clears the redo stack`() {
        val h = EditHistory()
        h.pushProperty(listOf(layer("a")))
        h.popUndo { it }                       // redo now has 1
        assertEquals(1, h.redoCount)
        h.pushDraw("layer-1", stroke())        // any push must clear redo
        assertEquals(0, h.redoCount)
    }

    @Test
    fun `pushProperty clears the redo stack even when the push is deduplicated`() {
        // Regression: a dedup-skipped push used to leave the redo stack untouched, so a stale
        // redoable entry queued before it could survive an edit that never cleared it — Redo would
        // then reapply that stale entry on top of newer, unrelated work instead of doing nothing.
        val h = EditHistory()
        h.pushProperty(listOf(layer("a")))
        h.pushProperty(listOf(layer("b")))
        h.popUndo { it }                          // undo=[a], redo=[b]
        assertEquals(1, h.redoCount)

        val pushed = h.pushProperty(listOf(layer("a"))) // matches undo-stack top -> dedup-skipped
        assertEquals(false, pushed)
        assertEquals(0, h.redoCount)
    }

    @Test
    fun `dropTopRedo discards the entry popUndo speculatively queued`() {
        val h = EditHistory()
        h.pushDraw("layer-1", stroke())
        h.popUndo { it }                          // redo now has 1
        assertEquals(1, h.redoCount)

        val dropped = h.dropTopRedo()
        assertTrue(dropped is EditCommand.Draw)
        assertEquals(0, h.redoCount)
        assertEquals(0, h.undoCount)               // dropped outright, not moved back to undo
    }

    @Test
    fun `popUndo on empty history returns null and records nothing`() {
        val h = EditHistory()
        assertNull(h.popUndo { it })
        assertEquals(0, h.redoCount)
    }

    @Test
    fun `popUndo moves the counterpart entry onto the redo stack`() {
        val h = EditHistory()
        h.pushDraw("layer-1", stroke())
        val popped = h.popUndo { it }
        assertTrue(popped is EditCommand.Draw)
        assertEquals(0, h.undoCount)
        assertEquals(1, h.redoCount)
    }

    @Test
    fun `popRedo moves the counterpart entry back onto the undo stack`() {
        val h = EditHistory()
        h.pushDraw("layer-1", stroke())
        h.popUndo { it }
        val redone = h.popRedo { it }
        assertTrue(redone is EditCommand.Draw)
        assertEquals(1, h.undoCount)
        assertEquals(0, h.redoCount)
    }

    private fun snapshot() = TileDelta.TileSnapshot(
        tx = 0, ty = 0, bounds = DirtyRegion(0, 0, 1, 1),
        before = intArrayOf(0), after = intArrayOf(1),
    )

    @Test
    fun `attachTileDeltas fills in the matching undo-stack entry, matched by command identity`() {
        val h = EditHistory()
        val cmd = stroke()
        h.pushDraw("layer-1", cmd)

        h.attachTileDeltas(cmd, listOf(snapshot()), canvasWidth = 10, canvasHeight = 10)

        val popped = h.popUndo { it } as EditCommand.Draw
        assertEquals(1, popped.tileDeltas?.size)
        assertEquals(10, popped.tileDeltaCanvasWidth)
        assertEquals(10, popped.tileDeltaCanvasHeight)
    }

    @Test
    fun `attachTileDeltas matches by object identity, not structural equality`() {
        val h = EditHistory()
        val cmd = stroke()
        val lookalike = stroke() // structurally equal StrokeCommand, different instance
        h.pushDraw("layer-1", cmd)

        h.attachTileDeltas(lookalike, listOf(snapshot()), canvasWidth = 10, canvasHeight = 10)

        val popped = h.popUndo { it } as EditCommand.Draw
        assertNull("a lookalike command must not attach to the real entry", popped.tileDeltas)
    }

    @Test
    fun `attachTileDeltas is a safe no-op once the entry has been trimmed away`() {
        val h = EditHistory(maxStackSize = 1)
        val cmd = stroke()
        h.pushDraw("layer-1", cmd)
        h.pushDraw("layer-1", stroke()) // trims `cmd` off the bottom of a 1-deep stack

        // Must not throw, and must not resurrect `cmd` or corrupt the stack that remains.
        h.attachTileDeltas(cmd, listOf(snapshot()), canvasWidth = 10, canvasHeight = 10)
        assertEquals(1, h.undoCount)
    }

    @Test
    fun `attachTileDeltas finds an entry that moved to the redo stack`() {
        val h = EditHistory()
        val cmd = stroke()
        h.pushDraw("layer-1", cmd)
        h.popUndo { it } // cmd is now on the redo stack

        h.attachTileDeltas(cmd, listOf(snapshot()), canvasWidth = 10, canvasHeight = 10)

        val redone = h.popRedo { it } as EditCommand.Draw
        assertEquals(1, redone.tileDeltas?.size)
    }

    @Test
    fun `clear empties both stacks`() {
        val h = EditHistory()
        h.pushProperty(listOf(layer("a")))
        h.pushDraw("layer-1", stroke())
        h.popUndo { it }
        h.clear()
        assertEquals(0, h.undoCount)
        assertEquals(0, h.redoCount)
    }
}
