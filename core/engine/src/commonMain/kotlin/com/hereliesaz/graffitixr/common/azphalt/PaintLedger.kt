package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Wet paint on a layer: film thickness in micrometres on a coarse grid, drying over time.
 *
 * Aerosol paint stays wet for a while, so a second pass over a spot a few seconds later lands on the
 * first one's film and the two can add up past what a wall holds. A stroke program can't see the
 * canvas, but it can see this: [StrokeLedger.wetAt] reads the film under the nozzle (earlier strokes'
 * deposits, dried by the time since), and [StrokeLedger.spray] / [StrokeLedger.drain] write it.
 *
 * The ledger is *history*, not canvas state. A stroke reads the film as it stood when the stroke
 * began (a [PaintSnapshot], kept on the stroke) and leaves the film it touched ([PaintSnapshot]
 * again), so replaying a stroke with its own snapshot reproduces it exactly and the next stroke
 * starts from the one before it. It is kept in memory only; after a project is reopened the paint
 * has long since dried, which is also what is true.
 *
 * Film is stored as thickness, never as volume, so it is independent of the brush size that laid it
 * down: the film on a patch of wall is whatever it is, whichever cap sprayed it.
 */
class PaintLedger(val dryMs: Float = DEFAULT_DRY_MS) {
    internal class Cell(var h: Float, var t: Long)

    internal val cells = HashMap<Long, Cell>()

    /** Sets every cell [film] names to the state it records; the rest are left as they are. */
    fun overlay(film: PaintSnapshot) {
        for (i in film.keys.indices) cells[film.keys[i]] = Cell(film.h[i], film.t[i])
    }

    /** The film still wet at [nowMs], as an immutable record; dry cells are left out. */
    fun snapshot(nowMs: Long): PaintSnapshot = snapshotOf(cells, nowMs, dryMs)

    /** A new stroke's view of this ledger as it stands; the ledger itself is never written by a stroke. */
    fun beginStroke(): StrokeLedger = StrokeLedger(this, dryMs)

    /** Film at (x, y) in micrometres at [nowMs]; for tests and tooling. */
    fun filmAt(x: Float, y: Float, nowMs: Long): Float {
        val cell = cells[cellKey(cellIndex(x), cellIndex(y))] ?: return 0f
        return decayed(cell, nowMs, dryMs)
    }

    companion object {
        /** Grid cell edge in layer px. Coarse on purpose: a spray footprint is tens to hundreds of px. */
        const val CELL_PX = 8f

        /** Time for film to fall to 1/e of its thickness (touch-dry is a few such periods). */
        const val DEFAULT_DRY_MS = 45_000f

        /** Below this a cell counts as dry and is dropped. */
        const val NEGLIGIBLE_UM = 0.05f

        internal fun cellIndex(v: Float): Int = floor(v / CELL_PX).toInt()

        internal fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

        internal fun decayed(cell: Cell, nowMs: Long, dryMs: Float): Float {
            val dt = (nowMs - cell.t).coerceAtLeast(0L).toFloat()
            return if (dt <= 0f || dryMs <= 0f) cell.h else cell.h * exp(-dt / dryMs)
        }
    }
}

/**
 * Wet film at an instant: cell key, thickness in micrometres and the time it was last touched, per
 * cell. A stroke keeps the one it started from and the one it left, which is all replay needs.
 */
class PaintSnapshot(val keys: LongArray, val h: FloatArray, val t: LongArray) {
    val isEmpty: Boolean get() = keys.isEmpty()

    /** A ledger holding this snapshot. */
    fun toLedger(dryMs: Float = PaintLedger.DEFAULT_DRY_MS): PaintLedger = PaintLedger(dryMs).also { it.overlay(this) }

    override fun equals(other: Any?): Boolean = other is PaintSnapshot &&
        keys.contentEquals(other.keys) && h.contentEquals(other.h) && t.contentEquals(other.t)

    override fun hashCode(): Int = keys.contentHashCode() * 31 + h.contentHashCode()
}

private fun snapshotOf(cells: Map<Long, PaintLedger.Cell>, nowMs: Long, dryMs: Float): PaintSnapshot {
    val wet = cells.entries.filter { PaintLedger.decayed(it.value, nowMs, dryMs) >= PaintLedger.NEGLIGIBLE_UM }
        .sortedBy { it.key }
    return PaintSnapshot(
        LongArray(wet.size) { wet[it].key },
        FloatArray(wet.size) { wet[it].value.h },
        LongArray(wet.size) { wet[it].value.t },
    )
}

/**
 * One stroke's working view of a [PaintLedger]: the film earlier strokes left, plus whatever this
 * stroke has sprayed or drained so far. Writes never reach the shared ledger; they accumulate in
 * [film], which the host keeps for the stroke that follows.
 */
class StrokeLedger internal constructor(private val prior: PaintLedger?, private val dryMs: Float) {
    private val overlay = HashMap<Long, PaintLedger.Cell>()

    /** Cells a single call may touch; a footprint larger than this is clamped, deterministically. */
    private val maxCells = MAX_CELLS_PER_CALL

    /** Adds up to [dh] micrometres at the centre, falling off to nothing at radius [r] px. */
    fun spray(x: Float, y: Float, r: Float, dh: Float, nowMs: Long) {
        if (!(r > 0f) || !dh.isFinite() || dh == 0f) return
        forEachCell(x, y, r) { key, w ->
            val cell = writable(key, nowMs)
            val before = PaintLedger.decayed(cell, nowMs, dryMs)
            cell.h = (before + dh * w).coerceAtLeast(0f)
            cell.t = nowMs
        }
    }

    /** Removes up to [dh] micrometres at the centre (never below dry), falling off to nothing at [r]. */
    fun drain(x: Float, y: Float, r: Float, dh: Float, nowMs: Long) = spray(x, y, r, -dh, nowMs)

    /** Mean film under a footprint of radius [r] at (x, y), centre-weighted, in micrometres. */
    fun wetAt(x: Float, y: Float, r: Float, nowMs: Long): Float {
        if (!(r > 0f)) return 0f
        var sum = 0f
        var weights = 0f
        forEachCell(x, y, r) { key, w ->
            val cell = overlay[key] ?: prior?.cells?.get(key)
            if (cell != null) sum += w * PaintLedger.decayed(cell, nowMs, dryMs)
            weights += w
        }
        return if (weights > 0f) sum / weights else 0f
    }

    /** Every cell this stroke touched, as it stands at [nowMs], for the stroke that follows. */
    fun film(nowMs: Long): PaintSnapshot = snapshotOf(overlay, nowMs, dryMs)

    private fun writable(key: Long, nowMs: Long): PaintLedger.Cell = overlay.getOrPut(key) {
        val base = prior?.cells?.get(key)
        PaintLedger.Cell(base?.h ?: 0f, base?.t ?: nowMs)
    }

    private inline fun forEachCell(x: Float, y: Float, r: Float, visit: (Long, Float) -> Unit) {
        if (!x.isFinite() || !y.isFinite()) return
        // A radius covering more cells than the cap is shrunk to the cap, the same way on every run.
        val reach = r.coerceAtMost(MAX_REACH_CELLS * PaintLedger.CELL_PX)
        val x0 = PaintLedger.cellIndex(x - reach)
        val x1 = PaintLedger.cellIndex(x + reach)
        val y0 = PaintLedger.cellIndex(y - reach)
        val y1 = PaintLedger.cellIndex(y + reach)
        var budget = maxCells
        for (cy in y0..y1) {
            for (cx in x0..x1) {
                if (budget-- <= 0) return
                val dx = (cx + 0.5f) * PaintLedger.CELL_PX - x
                val dy = (cy + 0.5f) * PaintLedger.CELL_PX - y
                val d = hypot(dx, dy) / reach
                if (d >= 1f) continue
                val falloff = 1f - d * d
                visit(PaintLedger.cellKey(cx, cy), falloff * falloff)
            }
        }
    }

    private companion object {
        const val MAX_REACH_CELLS = 40f
        const val MAX_CELLS_PER_CALL = 8_000
    }
}
