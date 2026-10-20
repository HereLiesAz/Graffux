package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.DirtyRegion
import com.hereliesaz.graffitixr.common.azphalt.TileDelta
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `dropTopUndo discards the entry popRedo speculatively queued`() {
        val h = EditHistory()
        h.pushDraw("layer-1", stroke())
        h.popUndo { it }                          // redo has 1, undo has 0
        h.popRedo { it }                          // undo has 1 again, redo has 0
        assertEquals(1, h.undoCount)

        val dropped = h.dropTopUndo()
        assertTrue(dropped is EditCommand.Draw)
        assertEquals(0, h.undoCount)
        assertEquals(0, h.redoCount)
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
    fun `layer growth reframes property snapshots in undo and redo`() {
        val h = EditHistory()
        h.pushProperty(listOf(layer("a"), layer("b").copy(frameWidth = 25, frameHeight = 15)))
        h.pushProperty(listOf(layer("a").copy(opacity = 0.5f)))
        h.popUndo { it } // a snapshot in both stacks
        h.migrateLayerGrowth("a", 100, 200, -32, -64)
        val fromUndo = h.popUndo { it } as EditCommand.PropertyChange
        val a = fromUndo.oldLayers.first { it.id == "a" }
        assertEquals(100, a.frameWidth)
        assertEquals(200, a.frameHeight)
        assertEquals(-32, a.originX)
        assertEquals(-64, a.originY)
        val untouched = fromUndo.oldLayers.first { it.id == "b" }
        assertEquals(25, untouched.frameWidth)
        val fromRedo = h.popRedo { it } as EditCommand.PropertyChange
        assertEquals(-32, fromRedo.oldLayers.first { it.id == "a" }.originX)
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

    private fun ref() = io.mockk.mockk<com.hereliesaz.graffitixr.nativebridge.GpuUndoSnapshots.Ref>()

    @Test
    fun `a second tile-delta attach frees the GPU snapshot it replaces`() {
        val freed = mutableListOf<Any>()
        val h = EditHistory(onGpuUndoDiscarded = { freed += it })
        val command = stroke()
        h.pushDraw("layer-1", command)
        val first = ref()
        val second = ref()
        h.attachTileDeltas(command, listOf(snapshot()), 4, 4, first)
        h.attachTileDeltas(command, listOf(snapshot()), 4, 4, second)
        assertEquals(listOf<Any>(first), freed)
        h.clear()
        assertEquals(listOf<Any>(first, second), freed)
    }

    @Test
    fun `forgetting a snapshot no entry holds any more does not free it twice`() {
        val freed = mutableListOf<Any>()
        val h = EditHistory(maxStackSize = 1, onGpuUndoDiscarded = { freed += it })
        val command = stroke()
        h.pushDraw("layer-1", command)
        val snapshot = ref()
        h.attachTileDeltas(command, listOf(snapshot()), 4, 4, snapshot)
        h.pushDraw("layer-1", stroke()) // trims the entry, freeing its snapshot
        assertEquals(listOf<Any>(snapshot), freed)
        h.forgetGpuUndo(snapshot)
        assertEquals(listOf<Any>(snapshot), freed)
    }

    @Test
    fun `dropDraw removes the failed stroke from either stack and frees its snapshot`() {
        val freed = mutableListOf<Any>()
        val h = EditHistory(onGpuUndoDiscarded = { freed += it })
        val kept = stroke()
        val failed = stroke()
        h.pushDraw("layer-1", kept)
        h.pushDraw("layer-1", failed)
        val snapshot = ref()
        h.attachTileDeltas(failed, listOf(snapshot()), 4, 4, snapshot)
        assertTrue(h.dropDraw(failed))
        assertEquals(1, h.undoCount)
        assertTrue((h.undoStackTopForTest() as EditCommand.Draw).command === kept)
        assertEquals(listOf<Any>(snapshot), freed)

        h.popUndo { it } // kept is now redoable
        assertTrue(h.dropDraw(kept))
        assertEquals(0, h.redoCount)
        assertFalse(h.dropDraw(kept))
    }

    // ── Undo chunks ──────────────────────────────────────────────────────────────────────────────

    private fun strokeWith(tool: Tool) = StrokeCommand(
        path = emptyList(),
        canvasSize = IntSize(1, 1),
        tool = tool,
        brushSize = 1f,
        brushColor = 0,
        intensity = 0.5f,
    )

    private fun tile(tx: Int, before: Int, after: Int) = TileDelta.TileSnapshot(
        tx = tx, ty = 0, bounds = DirtyRegion(tx, 0, tx + 1, 1),
        before = intArrayOf(before), after = intArrayOf(after),
    )

    private fun chunking() = EditHistory(chunkMaxStrokes = 3, chunkPauseMs = 100L)

    @Test
    fun `chunkable strokes in quick succession share one undo entry`() {
        val h = chunking()
        val a = strokeWith(Tool.BRUSH)
        val b = strokeWith(Tool.BRUSH)
        h.pushDraw("l", a, chunkable = true, nowMs = 0L)
        h.pushDraw("l", b, chunkable = true, nowMs = 50L)

        assertEquals(1, h.undoCount)
        val entry = h.popUndo { it } as EditCommand.Draw
        assertEquals(2, entry.strokes.size)
        assertTrue(entry.strokes[0] === a && entry.strokes[1] === b)
        assertTrue(entry.command === b)
    }

    @Test
    fun `pushDraw reports whether the stroke joined an existing chunk`() {
        val h = chunking()

        assertFalse(h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = true, nowMs = 0L))
        assertTrue(h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = true, nowMs = 10L))
        assertFalse(h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = false, nowMs = 20L))
        assertFalse(h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = true, nowMs = 500L))
    }

    @Test
    fun `a pause longer than the limit starts a new chunk`() {
        val h = chunking()
        h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = true, nowMs = 0L)
        h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = true, nowMs = 101L)

        assertEquals(2, h.undoCount)
    }

    @Test
    fun `a chunk closes at the stroke limit`() {
        val h = chunking()
        repeat(4) { h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = true, nowMs = it * 10L) }

        assertEquals(2, h.undoCount)
        assertEquals(1, (h.popUndo { it } as EditCommand.Draw).strokes.size)
        assertEquals(3, (h.popUndo { it } as EditCommand.Draw).strokes.size)
    }

    @Test
    fun `a different tool or layer starts a new chunk`() {
        val h = chunking()
        h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = true, nowMs = 0L)
        h.pushDraw("l", strokeWith(Tool.ERASER), chunkable = true, nowMs = 10L)
        h.pushDraw("other", strokeWith(Tool.ERASER), chunkable = true, nowMs = 20L)

        assertEquals(3, h.undoCount)
    }

    @Test
    fun `a stroke that is not chunkable never joins or is joined`() {
        val h = chunking()
        h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = false, nowMs = 0L)
        h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = true, nowMs = 10L)
        h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = false, nowMs = 20L)

        assertEquals(3, h.undoCount)
    }

    @Test
    fun `a property change between strokes closes the chunk`() {
        val h = chunking()
        h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = true, nowMs = 0L)
        h.pushProperty(listOf(layer("l")))
        h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = true, nowMs = 10L)

        assertEquals(3, h.undoCount)
    }

    @Test
    fun `undoableStrokeCount counts every stroke of every chunk on the layer`() {
        val h = chunking()
        repeat(2) { h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = true, nowMs = it * 10L) }
        h.pushDraw("l", strokeWith(Tool.ERASER), chunkable = false, nowMs = 30L)
        h.pushDraw("other", strokeWith(Tool.BRUSH), chunkable = false, nowMs = 40L)

        assertEquals(3, h.undoableStrokeCount("l"))
        assertEquals(1, h.undoableStrokeCount("other"))
        h.popUndo { it } // the "other" stroke
        assertEquals(0, h.undoableStrokeCount("other"))
    }

    @Test
    fun `a chunk's tile deltas merge in stroke order and cover the whole chunk`() {
        val h = chunking()
        val a = strokeWith(Tool.BRUSH)
        val b = strokeWith(Tool.BRUSH)
        h.pushDraw("l", a, chunkable = true, nowMs = 0L)
        h.pushDraw("l", b, chunkable = true, nowMs = 10L)

        h.attachTileDeltas(a, listOf(tile(0, before = 1, after = 2)), canvasWidth = 4, canvasHeight = 1)
        val partial = h.undoStackTopForTest() as EditCommand.Draw
        assertFalse("one stroke of two attached", partial.deltasComplete)
        h.attachTileDeltas(
            b, listOf(tile(0, before = 2, after = 3), tile(1, before = 7, after = 8)),
            canvasWidth = 4, canvasHeight = 1,
        )

        val entry = h.popUndo { it } as EditCommand.Draw
        assertTrue(entry.deltasComplete)
        val deltas = entry.tileDeltas!!
        assertEquals(1, deltas.first { it.tx == 0 }.before[0])
        assertEquals(3, deltas.first { it.tx == 0 }.after[0])
        assertEquals(7, deltas.first { it.tx == 1 }.before[0])
    }

    @Test
    fun `a chunk's deltas that arrive out of stroke order are dropped`() {
        val h = chunking()
        val a = strokeWith(Tool.BRUSH)
        val b = strokeWith(Tool.BRUSH)
        h.pushDraw("l", a, chunkable = true, nowMs = 0L)
        h.pushDraw("l", b, chunkable = true, nowMs = 10L)

        h.attachTileDeltas(b, listOf(tile(0, before = 2, after = 3)), canvasWidth = 4, canvasHeight = 1)
        h.attachTileDeltas(a, listOf(tile(0, before = 1, after = 2)), canvasWidth = 4, canvasHeight = 1)

        val entry = h.popUndo { it } as EditCommand.Draw
        assertFalse(entry.deltasComplete)
    }

    @Test
    fun `dropping one stroke of a chunk keeps the rest and falls back to a rebuild`() {
        val h = chunking()
        val a = strokeWith(Tool.BRUSH)
        val b = strokeWith(Tool.BRUSH)
        val c = strokeWith(Tool.BRUSH)
        listOf(a, b, c).forEachIndexed { i, s -> h.pushDraw("l", s, chunkable = true, nowMs = i * 10L) }
        h.attachTileDeltas(a, listOf(tile(0, 1, 2)), canvasWidth = 4, canvasHeight = 1)

        assertTrue(h.dropDraw(b))

        val entry = h.popUndo { it } as EditCommand.Draw
        assertEquals(2, entry.strokes.size)
        assertTrue(entry.strokes[0] === a && entry.strokes[1] === c)
        assertFalse(entry.deltasComplete)
    }

    @Test
    fun `dropping the only stroke of an entry removes it`() {
        val h = chunking()
        val a = strokeWith(Tool.BRUSH)
        h.pushDraw("l", a, chunkable = true, nowMs = 0L)

        assertTrue(h.dropDraw(a))
        assertEquals(0, h.undoCount)
    }

    @Test
    fun `a stroke pushed after an undo starts fresh and clears redo`() {
        val h = chunking()
        h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = true, nowMs = 0L)
        h.popUndo { it }
        h.pushDraw("l", strokeWith(Tool.BRUSH), chunkable = true, nowMs = 10L)

        assertEquals(1, h.undoCount)
        assertEquals(0, h.redoCount)
    }
}
