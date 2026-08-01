package com.hereliesaz.graffitixr.common.util

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tracing a flood-filled region back out to the polygons a Selection is made of. */
class ContourTraceTest {

    /** Builds a mask from an ASCII picture; '#' is inside. */
    private fun mask(vararg rows: String): Triple<BooleanArray, Int, Int> {
        val h = rows.size
        val w = rows[0].length
        val m = BooleanArray(w * h)
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, c -> m[y * w + x] = c == '#' } }
        return Triple(m, w, h)
    }

    private fun contoursOf(vararg rows: String) =
        mask(*rows).let { (m, w, h) -> ContourTrace.contours(m, w, h) }

    @Test
    fun `an empty mask has no contours`() {
        assertTrue(contoursOf("...", "...").isEmpty())
    }

    @Test
    fun `a single pixel traces its four corners`() {
        val rings = contoursOf(
            "...",
            ".#.",
            "...",
        )
        assertEquals(1, rings.size)
        assertTrue(rings[0].additive)
        // Pixel (1,1) occupies the square from corner (1,1) to (2,2) — the boundary runs along the
        // pixel's edges, not through its centre.
        assertEquals(4, rings[0].path.size)
        assertEquals(setOf(Offset(1f, 1f), Offset(2f, 1f), Offset(2f, 2f), Offset(1f, 2f)), rings[0].path.toSet())
    }

    @Test
    fun `a rectangle traces to four corners, not to every pixel edge`() {
        // The whole point of dropping collinear vertices: a 4x3 block's boundary is 14 unit steps
        // and only 4 of them are turns.
        val rings = contoursOf(
            "......",
            ".####.",
            ".####.",
            ".####.",
            "......",
        )
        assertEquals(1, rings.size)
        assertEquals(4, rings[0].path.size)
        assertEquals(setOf(Offset(1f, 1f), Offset(5f, 1f), Offset(5f, 4f), Offset(1f, 4f)), rings[0].path.toSet())
    }

    @Test
    fun `a hole comes back as a subtractive ring`() {
        val rings = contoursOf(
            "#####",
            "#...#",
            "#.#.#",
            "#...#",
            "#####",
        )
        // The outer square, plus the ring of background around the centre pixel, plus that centre
        // pixel itself sitting inside the hole.
        val additive = rings.filter { it.additive }
        val subtractive = rings.filter { !it.additive }
        assertEquals(2, additive.size)
        assertEquals(1, subtractive.size)

        // The counts alone prove nothing — they held while the emission order was wrong and the
        // island was being erased by the lake around it. What matters is that composing the rings
        // in the order given reproduces the mask, so assert that directly.
        val sel = com.hereliesaz.graffitixr.common.model.Selection(
            rings = rings,
            canvasSize = androidx.compose.ui.unit.IntSize(5, 5),
        )
        assertTrue(SelectionGeometry.contains(sel, Offset(0.5f, 0.5f)))   // the frame
        assertFalse(SelectionGeometry.contains(sel, Offset(1.5f, 1.5f)))  // the moat
        assertTrue(SelectionGeometry.contains(sel, Offset(2.5f, 2.5f)))   // the island inside it
    }

    @Test
    fun `every ring order reproduces its mask at nesting depth two`() {
        // Guards the ordering rule itself. "Outers first, then holes" is correct only one level
        // deep; at depth two it cuts the lake after the island has been added and erases it.
        val rings = contoursOf(
            "#######",
            "#######",
            "##...##",
            "##.#.##",
            "##...##",
            "#######",
            "#######",
        )
        val sel = com.hereliesaz.graffitixr.common.model.Selection(
            rings = rings,
            canvasSize = androidx.compose.ui.unit.IntSize(7, 7),
        )
        assertTrue(SelectionGeometry.contains(sel, Offset(0.5f, 0.5f)))   // outer body
        assertFalse(SelectionGeometry.contains(sel, Offset(2.5f, 2.5f)))  // the moat
        assertTrue(SelectionGeometry.contains(sel, Offset(3.5f, 3.5f)))   // the island in the moat
    }

    @Test
    fun `the traced region contains what the mask did`() {
        val rings = contoursOf(
            "#####",
            "#...#",
            "#...#",
            "#...#",
            "#####",
        )
        val sel = com.hereliesaz.graffitixr.common.model.Selection(
            rings = rings,
            canvasSize = androidx.compose.ui.unit.IntSize(5, 5),
        )
        // Pixel centres: the frame is selected, the middle is not.
        assertTrue(SelectionGeometry.contains(sel, Offset(0.5f, 0.5f)))
        assertTrue(SelectionGeometry.contains(sel, Offset(2.5f, 0.5f)))
        assertFalse(SelectionGeometry.contains(sel, Offset(2.5f, 2.5f)))
    }

    @Test
    fun `two disjoint blobs each get their own additive ring`() {
        val rings = contoursOf(
            "##..##",
            "##..##",
        )
        assertEquals(2, rings.size)
        assertTrue(rings.all { it.additive })
    }

    @Test
    fun `a region touching the mask edge still closes`() {
        val rings = contoursOf(
            "###",
            "###",
        )
        assertEquals(1, rings.size)
        assertEquals(setOf(Offset(0f, 0f), Offset(3f, 0f), Offset(3f, 2f), Offset(0f, 2f)), rings[0].path.toSet())
    }

    @Test
    fun `an outer boundary winds positive and a hole winds negative`() {
        // The classification is by winding, not by testing containment of one loop in another, so
        // this is the property the whole approach rests on.
        val solid = contoursOf("...", ".#.", "...")
        assertTrue(ContourTrace.signedArea(solid[0].path) > 0f)

        val holed = contoursOf(
            "#####",
            "#####",
            "##.##",
            "#####",
            "#####",
        )
        val hole = holed.single { !it.additive }
        assertTrue(ContourTrace.signedArea(hole.path) < 0f)
    }

    @Test
    fun `dropCollinear is lossless at the turns`() {
        val square = listOf(
            Offset(0f, 0f), Offset(1f, 0f), Offset(2f, 0f),
            Offset(2f, 1f), Offset(2f, 2f),
            Offset(1f, 2f), Offset(0f, 2f),
            Offset(0f, 1f),
        )
        assertEquals(
            listOf(Offset(0f, 0f), Offset(2f, 0f), Offset(2f, 2f), Offset(0f, 2f)),
            ContourTrace.dropCollinear(square),
        )
    }

    @Test
    fun `a diagonal staircase keeps every turn`() {
        // Nothing here is collinear, so nothing may be dropped — the wand's output must keep the
        // exact pixel boundary where the region actually steps.
        val rings = contoursOf(
            "#..",
            "##.",
            "###",
        )
        assertEquals(1, rings.size)
        assertTrue(rings[0].path.size > 4)
    }
}
