package com.hereliesaz.graffitixr.feature.editor.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure halves of the Ink SVG export: world -> document mapping and the SVG text. */
class InkSvgExportTest {

    @Test
    fun worldToDocumentMapsTheArtboardCornersOntoTheDocument() {
        // A 1000x2000 world with a square document: the artboard is 1000x1000, centred vertically.
        val t = InkAffine.worldToDocument(1000, 2000, 500, 500)
        assertEquals(0f, t.mapX(0f, 500f), 1e-4f)
        assertEquals(0f, t.mapY(0f, 500f), 1e-4f)
        assertEquals(500f, t.mapX(1000f, 1500f), 1e-4f)
        assertEquals(500f, t.mapY(1000f, 1500f), 1e-4f)
    }

    @Test
    fun worldToDocumentDegradesToIdentity() {
        assertEquals(InkAffine.IDENTITY, InkAffine.worldToDocument(1000, 1000, 0, 0))
    }

    @Test
    fun documentHasOnePathPerStrokeWithColourAndOpacity() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        val svg = InkSvg.document(
            200, 100,
            listOf(
                InkSvg.StrokeOutlines(listOf(square), 0x80FF0000.toInt()),
                InkSvg.StrokeOutlines(listOf(floatArrayOf(1f, 1f)), 0xFF00FF00.toInt()), // degenerate
            ),
        )
        assertTrue(svg, svg.contains("""width="200" height="100""""))
        assertTrue(svg, svg.contains("""d="M 0 0 L 10 0 L 10 10 L 0 10 Z""""))
        assertTrue(svg, svg.contains("""fill="#ff0000""""))
        assertTrue(svg, svg.contains("""fill-opacity="0.5""""))
        assertFalse("a stroke with no enclosing outline is skipped", svg.contains("#00ff00"))
    }
}
