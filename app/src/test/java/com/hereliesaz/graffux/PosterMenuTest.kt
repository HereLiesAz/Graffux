package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.photo.DocumentCrop
import com.hereliesaz.graffitixr.feature.editor.export.PosterLayout
import com.hereliesaz.graffitixr.feature.editor.export.PosterPaper
import com.hereliesaz.graffitixr.feature.editor.export.PosterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The logo menu's Poster PDF page: its rows, what a tap cycles, the export, and the crop. */
class PosterMenuTest {

    private var choices = PosterChoices()
    private var exported: PosterSpec? = null

    private fun rows(imperial: Boolean = true, w: Int = 3000, h: Int = 4000) = buildMenu {
        posterPage(choices, imperial, w, h, { choices = it }, {}, { exported = it })
    }

    private fun labels(rows: List<MenuRow>) = rows.mapNotNull {
        when (it) {
            is MenuRow.Page -> it.text
            is MenuRow.Action -> it.text
            is MenuRow.Toggle -> it.label
            else -> null
        }
    }

    @Test
    fun `the page lists paper, size, margins, overlap, marks and the page count`() {
        assertEquals(
            listOf(
                "‹ Back", "Paper · Letter", "Orientation · Portrait", "Size · 24 in × 32 in", "Margins · 0.5 in",
                "Overlap · 0.25 in", "Crop marks", "Registration marks", "Page labels", "Export 16 pages (4 × 4)",
            ),
            labels(rows()),
        )
        assertEquals(
            listOf("Size · 60 cm × 80 cm", "Margins · 1 cm", "Overlap · 0.5 cm"),
            labels(rows(imperial = false)).filter { label ->
                listOf("Size", "Margins", "Overlap").any { label.startsWith(it) }
            },
        )
    }

    @Test
    fun `a value row cycles to the next choice and wraps`() {
        fun tap(prefix: String) =
            (rows().first { it is MenuRow.Page && it.text.startsWith(prefix) } as MenuRow.Page).onClick()
        tap("Paper")
        assertEquals(PosterPaper.A4, choices.paper)
        repeat(3) { tap("Paper") }
        assertEquals(PosterPaper.LETTER, choices.paper)
        tap("Orientation")
        assertTrue(choices.landscape)
        tap("Size")
        assertTrue(labels(rows()).contains("Size · 36 in × 48 in"))
    }

    @Test
    fun `export hands over the spec in points`() {
        (rows().last() as MenuRow.Action).onClick()
        val spec = exported!!
        assertEquals(24 * PosterLayout.POINTS_PER_INCH, spec.posterWidthPt, 0.01f)
        assertEquals(36f, spec.marginPt, 0.01f)
        assertEquals(18f, spec.overlapPt, 0.01f)
    }

    @Test
    fun `the document crop sets the artwork's aspect`() {
        assertEquals(1080 to 1920, posterArtSize(1080, 1920, DocumentCrop()))
        // The middle half across and the top quarter down: 540 × 480.
        assertEquals(540 to 480, posterArtSize(1080, 1920, DocumentCrop(left = 0.25f, right = 0.75f, bottom = 0.25f)))
        // A wide crop makes a short poster: 24 in × 21.3 in, one row fewer.
        val (w, h) = posterArtSize(1080, 1920, DocumentCrop(left = 0.25f, right = 0.75f, bottom = 0.25f))
        assertTrue(labels(rows(w = w, h = h)).contains("Size · 24 in × 21.3 in"))
    }
}
