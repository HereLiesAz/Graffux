@file:Suppress("MagicNumber") // ISO and US paper sizes in points, and the default spec's inches.

package com.hereliesaz.graffitixr.feature.editor.export

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A printer paper, portrait, in PDF points (1/72 in). */
enum class PosterPaper(val label: String, val widthPt: Float, val heightPt: Float) {
    LETTER("Letter", 612f, 792f),
    A4("A4", 595.28f, 841.89f),
    A3("A3", 841.89f, 1190.55f),
    LEGAL("Legal", 612f, 1008f),
}

/** What the user chose for a tiled poster. Lengths are in points (72 per inch). */
data class PosterSpec(
    val paper: PosterPaper = PosterPaper.LETTER,
    val landscape: Boolean = false,
    /** The printed artwork's width; its height follows the artwork's aspect. */
    val posterWidthPt: Float = 24f * PosterLayout.POINTS_PER_INCH,
    val marginPt: Float = 0.5f * PosterLayout.POINTS_PER_INCH,
    /** How much each tile repeats of its neighbour, so pages can be trimmed and glued. */
    val overlapPt: Float = 0.25f * PosterLayout.POINTS_PER_INCH,
    val cropMarks: Boolean = true,
    val registrationMarks: Boolean = true,
    val pageLabels: Boolean = true,
)

/** A rectangle in points: left, top, width, height. */
data class PtRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

/**
 * One printed page: [row] and [column] (0-based) in the grid, the part of the poster it carries
 * ([source], in poster points from its top-left), and where that lands on the paper ([target],
 * inside the margins, from the paper's top-left).
 */
data class PosterTile(val row: Int, val column: Int, val source: PtRect, val target: PtRect) {
    /** "Row 2 · Column 3", 1-based, as printed in the margin. */
    val label: String get() = "Row ${row + 1} · Column ${column + 1}"
}

/** The whole plan: the paper as it prints (orientation applied), the poster size, and the pages. */
data class PosterPlan(
    val pageWidthPt: Float,
    val pageHeightPt: Float,
    val posterWidthPt: Float,
    val posterHeightPt: Float,
    val rows: Int,
    val columns: Int,
    val tiles: List<PosterTile>,
) {
    val pageCount: Int get() = tiles.size
}

/**
 * Tiles a poster of a given aspect over pages. Each page prints a printable area (the paper minus
 * its margins on all four sides). Neighbouring pages repeat [PosterSpec.overlapPt] of each other,
 * so a grid of `columns` pages covers `columns * printable - (columns - 1) * overlap`. The last
 * row and column carry only what is left. Pages run left to right, then top to bottom.
 */
object PosterLayout {
    const val POINTS_PER_INCH = 72f
    const val POINTS_PER_CM = 72f / 2.54f

    /** The smallest printable side kept, so huge margins on a small paper never divide by ~0. */
    private const val MIN_PRINTABLE = 36f

    fun plan(spec: PosterSpec, artWidth: Int, artHeight: Int): PosterPlan {
        require(artWidth > 0 && artHeight > 0) { "empty artwork" }
        val pw = if (spec.landscape) spec.paper.heightPt else spec.paper.widthPt
        val ph = if (spec.landscape) spec.paper.widthPt else spec.paper.heightPt
        val margin = spec.marginPt.coerceIn(0f, (min(pw, ph) - MIN_PRINTABLE) / 2f)
        val printW = pw - 2f * margin
        val printH = ph - 2f * margin
        // Overlap below the printable side, so every page still advances.
        val overlap = spec.overlapPt.coerceIn(0f, min(printW, printH) / 2f)
        val posterW = max(spec.posterWidthPt, 1f)
        val posterH = posterW * artHeight / artWidth
        val columns = count(posterW, printW, overlap)
        val rows = count(posterH, printH, overlap)
        val tiles = buildList {
            for (r in 0 until rows) for (c in 0 until columns) {
                val sx = c * (printW - overlap)
                val sy = r * (printH - overlap)
                val w = min(printW, posterW - sx)
                val h = min(printH, posterH - sy)
                add(PosterTile(r, c, PtRect(sx, sy, w, h), PtRect(margin, margin, w, h)))
            }
        }
        return PosterPlan(pw, ph, posterW, posterH, rows, columns, tiles)
    }

    /** Pages along one side: 1 when it fits, else enough steps of (printable − overlap). */
    private fun count(length: Float, printable: Float, overlap: Float): Int {
        if (length <= printable + EPSILON) return 1
        return ceil((length - overlap - EPSILON) / (printable - overlap)).toInt().coerceAtLeast(1)
    }

    private const val EPSILON = 1e-3f
}
