package com.hereliesaz.graffux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingRailFoldTest {

    @Test
    fun `main rail folds during a stroke and unfolds after`() {
        assertTrue(DrawingRailFold.mainRailFolded(userFolded = false, drawingHidden = true))
        assertFalse(DrawingRailFold.mainRailFolded(userFolded = false, drawingHidden = false))
    }

    @Test
    fun `a rail the user folded stays folded when the stroke ends`() {
        assertTrue(DrawingRailFold.mainRailFolded(userFolded = true, drawingHidden = false))
        assertTrue(DrawingRailFold.mainRailFolded(userFolded = true, drawingHidden = true))
    }

    @Test
    fun `an expanded host collapses during a stroke and comes back after`() {
        assertFalse(DrawingRailFold.hostExpandWhen(userExpanded = true, drawingHidden = true))
        assertTrue(DrawingRailFold.hostExpandWhen(userExpanded = true, drawingHidden = false))
    }

    @Test
    fun `a host the user collapsed is never force-expanded`() {
        assertFalse(DrawingRailFold.hostExpandWhen(userExpanded = false, drawingHidden = true))
        assertFalse(DrawingRailFold.hostExpandWhen(userExpanded = false, drawingHidden = false))
    }

    @Test
    fun `the stroke's own collapse is not saved as the user's choice`() {
        assertFalse(DrawingRailFold.persistExpansionChange(expanded = false, drawingHidden = true))
        assertTrue(DrawingRailFold.persistExpansionChange(expanded = true, drawingHidden = true))
        assertTrue(DrawingRailFold.persistExpansionChange(expanded = false, drawingHidden = false))
        assertTrue(DrawingRailFold.persistExpansionChange(expanded = true, drawingHidden = false))
    }
}
