package com.hereliesaz.graffux

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rail's colour item: large while its sheet is open, normal otherwise, and always inside the pill. */
class ColourRailItemTest {
    @Test
    fun `open is large and closed is the normal rail button`() {
        assertEquals(ColourItemOpenFace, colourItemFace(sheetOpen = true))
        assertEquals(RailButtonSize, colourItemFace(sheetOpen = false))
        assertTrue(colourItemFace(true) > colourItemFace(false))
    }

    @Test
    fun `the touch target is never under 48dp`() {
        assertTrue(railTouchSize(colourItemFace(false)) >= 48.dp)
        assertTrue(railTouchSize(colourItemFace(true)) >= 48.dp)
        assertEquals(RailTouchSize, railTouchSize(RailButtonSize))
    }

    @Test
    fun `the enlarged item fits inside the pill's border`() {
        val inside = RailWidth - 2.dp
        assertTrue(railTouchSize(colourItemFace(true)) <= inside)
    }
}
