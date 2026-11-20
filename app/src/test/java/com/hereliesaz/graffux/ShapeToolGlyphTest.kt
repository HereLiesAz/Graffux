package com.hereliesaz.graffux

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import com.hereliesaz.graffitixr.common.model.ShapeToolMode
import com.hereliesaz.graffitixr.design.RailIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Shape tool's per-mode glyphs (spec/shape-tool.md, section 11). */
class ShapeToolGlyphTest {

    private fun paths(icon: ImageVector) = icon.root.toList().filterIsInstance<VectorPath>()

    private fun outline(icon: ImageVector) = paths(icon).map { it.pathData }

    @Test
    fun `every mode has a glyph, and the seven are pairwise different`() {
        val glyphs = ShapeToolMode.entries.map { outline(RailIcons.shapeGlyph(it)) }
        assertEquals(7, glyphs.size)
        assertEquals(glyphs.size, glyphs.toSet().size)
    }

    @Test
    fun `no mode glyph is the grid toggle's hash`() {
        val hash = outline(RailIcons.Frame)
        ShapeToolMode.entries.forEach { assertNotEquals(it.name, hash, outline(RailIcons.shapeGlyph(it))) }
    }

    @Test
    fun `each glyph is drawn in the rail's 24 unit viewBox at stroke 1_8, unfilled`() {
        ShapeToolMode.entries.forEach { mode ->
            val icon = RailIcons.shapeGlyph(mode)
            assertEquals(mode.name, 24f, icon.viewportWidth, 0f)
            assertEquals(mode.name, 24f, icon.viewportHeight, 0f)
            assertTrue(mode.name, paths(icon).isNotEmpty())
            paths(icon).forEach {
                assertEquals(mode.name, 1.8f, it.strokeLineWidth, 0f)
                assertEquals(mode.name, null, it.fill)
            }
        }
    }

    @Test
    fun `the Frame glyph is a box with a name tab, not the hash`() {
        assertEquals(2, paths(RailIcons.ShapeFrame).size)
    }

    @Test
    fun `the shape modes are named by distinct string resources`() {
        val names = ShapeToolMode.entries.map { shapeModeName(it) }
        assertEquals(names.size, names.toSet().size)
    }
}
