package com.hereliesaz.graffitixr.feature.editor.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Poster tiling against hand-worked numbers. A 24 in wide 3:4 poster is 1728 × 2304 pt. Letter with
 * 0.5 in (36 pt) margins prints 540 × 720 pt per page; with 0.25 in (18 pt) overlap each further
 * page advances 522 pt across and 702 pt down. Columns: 3 pages cover 3·540 − 2·18 = 1584 < 1728,
 * 4 cover 2106, so 4. Rows: 3 cover 2124 < 2304, 4 cover 2826, so 4. 16 pages.
 */
class PosterLayoutTest {

    private val inch = PosterLayout.POINTS_PER_INCH
    private val spec = PosterSpec(posterWidthPt = 24 * inch, marginPt = 0.5f * inch, overlapPt = 0.25f * inch)

    private fun near(expected: Float, actual: Float) =
        assertTrue("expected $expected, was $actual", kotlin.math.abs(expected - actual) < 0.01f)

    @Test
    fun `a 24 inch 3 by 4 poster on Letter is 4 by 4 pages`() {
        val plan = PosterLayout.plan(spec, 3000, 4000)
        assertEquals(4, plan.columns)
        assertEquals(4, plan.rows)
        assertEquals(16, plan.pageCount)
        near(612f, plan.pageWidthPt)
        near(792f, plan.pageHeightPt)
        near(1728f, plan.posterWidthPt)
        near(2304f, plan.posterHeightPt)
    }

    @Test
    fun `tiles step by printable minus overlap and the last ones carry the rest`() {
        val plan = PosterLayout.plan(spec, 3000, 4000)
        val first = plan.tiles.first()
        assertEquals(PtRect(0f, 0f, 540f, 720f), first.source)
        assertEquals(PtRect(36f, 36f, 540f, 720f), first.target)
        val second = plan.tiles[1]
        assertEquals(0, second.row)
        assertEquals(1, second.column)
        near(522f, second.source.x)
        // Neighbours repeat exactly the overlap.
        near(18f, first.source.right - second.source.x)
        val last = plan.tiles.last()
        assertEquals(3, last.row)
        assertEquals(3, last.column)
        near(1566f, last.source.x)
        near(162f, last.source.width)
        near(2106f, last.source.y)
        near(198f, last.source.height)
        // The last tile ends on the poster's edge: everything is printed.
        near(plan.posterWidthPt, last.source.right)
        near(plan.posterHeightPt, last.source.bottom)
        assertEquals("Row 4 · Column 4", last.label)
    }

    @Test
    fun `every paper size counts its own pages`() {
        // A4: printable 523.28 × 769.89, steps 505.28 and 751.89: ceil(1710/505.28) = 4,
        // ceil(2286/751.89) = 4.
        assertEquals(16, PosterLayout.plan(spec.copy(paper = PosterPaper.A4), 3000, 4000).pageCount)
        // A3: printable 769.89 × 1118.55: ceil(1710/751.89) = 3, ceil(2286/1100.55) = 3.
        assertEquals(9, PosterLayout.plan(spec.copy(paper = PosterPaper.A3), 3000, 4000).pageCount)
        // Legal: printable 540 × 936: 4 columns, ceil(2286/918) = 3 rows.
        PosterLayout.plan(spec.copy(paper = PosterPaper.LEGAL), 3000, 4000).let {
            assertEquals(4, it.columns)
            assertEquals(3, it.rows)
        }
    }

    @Test
    fun `landscape swaps the paper and the grid follows`() {
        val plan = PosterLayout.plan(spec.copy(landscape = true), 3000, 4000)
        near(792f, plan.pageWidthPt)
        near(612f, plan.pageHeightPt)
        // Printable 720 × 540: ceil(1710/702) = 3 columns, ceil(2286/522) = 5 rows.
        assertEquals(3, plan.columns)
        assertEquals(5, plan.rows)
    }

    @Test
    fun `a poster that fits is one page, and no overlap tiles edge to edge`() {
        assertEquals(1, PosterLayout.plan(spec.copy(posterWidthPt = 6 * inch), 600, 800).pageCount)
        val flush = PosterLayout.plan(spec.copy(overlapPt = 0f), 3000, 4000)
        near(flush.tiles[0].source.right, flush.tiles[1].source.x)
        // 1728 / 540 = 3.2 → 4 columns; 2304 / 720 = 3.2 → 4 rows.
        assertEquals(16, flush.pageCount)
    }

    @Test
    fun `absurd margins and overlaps are clamped, never a divide by zero`() {
        val plan = PosterLayout.plan(spec.copy(marginPt = 1000f, overlapPt = 1000f), 3000, 4000)
        assertTrue(plan.pageCount in 1..10_000)
        assertTrue(plan.tiles.all { it.source.width > 0f && it.source.height > 0f })
    }
}
