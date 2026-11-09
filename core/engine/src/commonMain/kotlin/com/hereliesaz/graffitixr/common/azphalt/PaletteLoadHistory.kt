package com.hereliesaz.graffitixr.common.azphalt

/**
 * What undo and redo do to the paint on a palette brush (spec/painterly.md 6.5).
 *
 * The rule: **a stroke's spending is part of the stroke.** Undoing a stroke puts the paint it used back on the brush
 * (the pixels it made are gone, so the paint is not wasted); redoing it takes the paint again. A stroke that was
 * cancelled, or whose commit returned early, never reached [record], so it spent nothing. And the brush is only
 * touched while it still holds exactly what the stroke left (undo) or took (redo): if the user has dipped, mixed or
 * wiped since, they have moved on, and putting an old load back would overwrite the new one. "The brush" is the one
 * the stroke was made with (its recorded hand), not whichever is selected now: switching between the foreground and
 * background brush in the mixer changes nothing about what either holds.
 *
 * Keyed by the stroke's seed (unique per stroke); bounded, oldest forgotten first.
 */
class PaletteLoadHistory(private val capacity: Int = 512) {
    private class Entry(val before: PaletteBoard.BrushState, val after: PaletteBoard.BrushState)

    private val entries = LinkedHashMap<Long, Entry>()

    /** A stroke [key] committed: the brush held [before] when it began and holds [after] now. */
    fun record(key: Long, before: PaletteBoard.BrushState, after: PaletteBoard.BrushState) {
        entries.remove(key)
        entries[key] = Entry(before, after)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    /** The stroke [key] was undone: true if the brush got its paint back. */
    fun undo(key: Long, board: PaletteBoard): Boolean {
        val e = entries[key] ?: return false
        // the brush the stroke used, whichever one is selected now
        if (!board.brushStateAt(e.after.hand).sameAs(e.after)) return false
        board.restoreBrushState(e.before)
        return true
    }

    /** The stroke [key] was redone: true if the brush spent its paint again. */
    fun redo(key: Long, board: PaletteBoard): Boolean {
        val e = entries[key] ?: return false
        if (!board.brushStateAt(e.before.hand).sameAs(e.before)) return false
        board.restoreBrushState(e.after)
        return true
    }

    /** The stroke [key] can never be undone or redone again (its history entry fell off). */
    fun forget(key: Long) {
        entries.remove(key)
    }

    val size: Int get() = entries.size
}
