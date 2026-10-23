package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.InkUtensil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkUtensilCatalogTest {

    @Test
    fun `every Ink utensil has exactly one brush-list entry, in catalogue order`() {
        assertEquals(InkUtensil.entries.toList(), INK_UTENSIL_CATALOG.map { it.utensil })
    }

    @Test
    fun `the brush list names the four stock families`() {
        assertEquals(
            listOf("Pressure Pen", "Ink Marker", "Ink Highlighter", "Ink Dashed Line"),
            INK_UTENSIL_CATALOG.map { it.label },
        )
    }

    @Test
    fun `classifiers are unique and derived from the stable id`() {
        val ids = INK_UTENSIL_CATALOG.map { it.classifier }
        assertEquals(ids.size, ids.toSet().size)
        INK_UTENSIL_CATALOG.forEach {
            assertEquals("brush.${it.utensil.id}", it.classifier)
        }
    }

    @Test
    fun `the utensil in hand resolves to its own entry, and none means no entry`() {
        InkUtensil.entries.forEach { u ->
            assertEquals(u, inkUtensilEntryFor(u)?.utensil)
        }
        assertNull(inkUtensilEntryFor(null))
    }
}
