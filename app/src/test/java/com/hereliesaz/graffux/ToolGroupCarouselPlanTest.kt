package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGroupCarouselPlanTest {
    @Test
    fun `the same parent rail buttons remain the entry points for nested choices`() {
        assertEquals("grp.transform", carouselGroupRailId(CarouselCategory.TRANSFORM))
        assertEquals("grp.select", carouselGroupRailId(CarouselCategory.SELECTION))
        assertEquals("grp.vector", carouselGroupRailId(CarouselCategory.VECTOR))
        assertEquals(null, carouselGroupRailId(CarouselCategory.BRUSHES))
        assertTrue(CAROUSEL_TABS.none { it is CarouselTab.Page && it.category.isToolGroup })
    }

    @Test
    fun `selecting the selection rail parent exposes its former nested choices`() {
        val state = EditorUiState(activeTool = Tool.SELECT, selectionShape = SelectionShape.ELLIPSE)
        val entries = toolGroupCarouselEntries(CarouselCategory.SELECTION, state)
        assertTrue(entries.any { it.key == "tool.select" })
        assertTrue(entries.any { it.key == "selectShape.RECTANGLE" })
        assertTrue(entries.any { it.key == "selectShape.ELLIPSE" && it.selected })
        assertTrue(entries.any { it.key == "selectOp.NEW" })
        assertTrue(entries.any { it.key == "tool.quick" })
        assertTrue(entries.any { it.key == "tool.deselect" })
        assertEquals(entries.map { it.key }.size, entries.map { it.key }.distinct().size)
    }

    @Test
    fun `vector rail parent reveals the current tool and descriptions`() {
        val entries = toolGroupCarouselEntries(CarouselCategory.VECTOR, EditorUiState(activeTool = Tool.BLUR))
        assertTrue(entries.any { it.key == "tool.blur" && it.selected })
        assertTrue(entries.any { it.key == "tool.pen" })
        assertTrue(entries.any { it.key == "tool.smudge" })
        assertFalse(entries.any { it.description.isNullOrBlank() })
        assertEquals(entries.map { it.key }.size, entries.map { it.key }.distinct().size)
    }

    @Test
    fun `transform parent reveals tools and apply stays conditional`() {
        val entries = toolGroupCarouselEntries(CarouselCategory.TRANSFORM, EditorUiState())
        assertTrue(entries.any { it.key == "adj.transform" })
        assertTrue(entries.any { it.key == "transform.FREEFORM" })
        assertFalse(entries.any { it.key == "transform.apply" })
    }
}
