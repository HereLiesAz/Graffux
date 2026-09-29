package com.hereliesaz.graffux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingRailFoldTest {

    @Test
    fun `main rail folds during a stroke and unfolds after`() {
        assertTrue(DrawingRailFold.mainRailFolded(userFolded = false, strokeActive = true))
        assertFalse(DrawingRailFold.mainRailFolded(userFolded = false, strokeActive = false))
    }

    @Test
    fun `a rail the user folded stays folded when the stroke ends`() {
        assertTrue(DrawingRailFold.mainRailFolded(userFolded = true, strokeActive = false))
        assertTrue(DrawingRailFold.mainRailFolded(userFolded = true, strokeActive = true))
    }

    @Test
    fun `an expanded host collapses during a stroke and comes back after`() {
        assertFalse(DrawingRailFold.hostExpandWhen(userExpanded = true, strokeActive = true))
        assertTrue(DrawingRailFold.hostExpandWhen(userExpanded = true, strokeActive = false))
    }

    @Test
    fun `a host the user collapsed is never force-expanded`() {
        assertFalse(DrawingRailFold.hostExpandWhen(userExpanded = false, strokeActive = true))
        assertFalse(DrawingRailFold.hostExpandWhen(userExpanded = false, strokeActive = false))
    }

    @Test
    fun `the stroke's own collapse is not saved as the user's choice`() {
        assertFalse(DrawingRailFold.persistExpansionChange(expanded = false, strokeActive = true))
        assertTrue(DrawingRailFold.persistExpansionChange(expanded = true, strokeActive = true))
        assertTrue(DrawingRailFold.persistExpansionChange(expanded = false, strokeActive = false))
        assertTrue(DrawingRailFold.persistExpansionChange(expanded = true, strokeActive = false))
    }
}
