package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomCarouselPlanTest {

    private val builtIn = BuiltInBrushes.presets

    private val base = CarouselInputs(
        activeTool = Tool.BRUSH,
        activeBrushName = null,
        builtInBrushes = builtIn,
        customBrushes = emptyList(),
        extensionBrushes = emptyList(),
        stabilizerLevel = 0,
        stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
        smudgeMode = ColorSmudgeEngine.Mode.DULLING,
        selectionShape = SelectionShape.ELLIPSE,
        toolOptionsOpen = false,
    )

    private fun inputs(tool: Tool = Tool.BRUSH, brushName: String? = null, stabilizer: Int = 0) =
        base.copy(activeTool = tool, activeBrushName = brushName, stabilizerLevel = stabilizer)

    @Test
    fun `brushes page lists built-in then custom then extension brushes`() {
        val custom = listOf("c1" to builtIn.first().copy(name = "Mine"))
        val entries = carouselEntries(
            CarouselCategory.BRUSHES,
            base.copy(customBrushes = custom, extensionBrushes = listOf("e:1" to "Ext")),
        )
        assertEquals(builtIn.size + 2 + INK_UTENSIL_CATALOG.size, entries.size)
        assertEquals(CarouselAction.BuiltInBrush(builtIn.first().name), entries.first().action)
        assertEquals(CarouselAction.CustomBrush("c1"), entries[builtIn.size].action)
        assertEquals(CarouselAction.ExtensionBrush("e:1"), entries[builtIn.size + 1].action)
    }

    @Test
    fun `active brush is the selected and centred item`() {
        val target = builtIn.last().name
        val entries = carouselEntries(CarouselCategory.BRUSHES, inputs(brushName = target))
        assertEquals(1, entries.count { it.selected })
        assertEquals(builtIn.size - 1, selectedCarouselIndex(entries))
    }

    @Test
    fun `a brush name shared across sources lights only the first match`() {
        val dup = builtIn.first()
        val entries = carouselEntries(
            CarouselCategory.BRUSHES,
            base.copy(activeBrushName = dup.name, customBrushes = listOf("c" to dup)),
        )
        assertEquals(1, entries.count { it.selected })
        assertEquals(0, selectedCarouselIndex(entries))
    }

    @Test
    fun `brushes page ends with the Ink utensils`() {
        val entries = carouselEntries(CarouselCategory.BRUSHES, base)
        assertEquals(
            INK_UTENSIL_CATALOG.map { CarouselAction.InkUtensilPick(it.utensil) },
            entries.takeLast(INK_UTENSIL_CATALOG.size).map { it.action },
        )
    }

    @Test
    fun `an Ink utensil in hand lights only its entry, not the same-named stamp brush`() {
        val pen = com.hereliesaz.graffitixr.common.model.InkUtensil.PEN
        // Selecting a utensil sets activeBrushName to its display name, which the built-in shares.
        assertTrue(builtIn.any { it.name == pen.displayName })
        val entries = carouselEntries(
            CarouselCategory.BRUSHES,
            base.copy(activeBrushName = pen.displayName, activeInkUtensil = pen),
        )
        assertEquals(1, entries.count { it.selected })
        val index = selectedCarouselIndex(entries)!!
        assertEquals(CarouselAction.InkUtensilPick(pen), entries[index].action)
    }

    @Test
    fun `no active brush leaves nothing to centre on`() {
        assertNull(selectedCarouselIndex(carouselEntries(CarouselCategory.BRUSHES, inputs(brushName = null))))
    }

    @Test
    fun `effects page is the effect tools and reflects the armed one`() {
        val entries = carouselEntries(CarouselCategory.EFFECTS, inputs(tool = Tool.BURN))
        assertEquals(EFFECT_TOOLS.map { CarouselAction.PickTool(it) }, entries.map { it.action })
        assertEquals(EFFECT_TOOLS.indexOf(Tool.BURN), selectedCarouselIndex(entries))
        assertNull(selectedCarouselIndex(carouselEntries(CarouselCategory.EFFECTS, inputs(tool = Tool.BRUSH))))
    }

    @Test
    fun `options always end with the full Tool Options window`() {
        Tool.entries.forEach { tool ->
            val entries = carouselEntries(CarouselCategory.OPTIONS, inputs(tool = tool))
            assertEquals(CarouselAction.OpenToolOptions, entries.last().action)
        }
        val open = carouselEntries(CarouselCategory.OPTIONS, base.copy(activeTool = Tool.NONE, toolOptionsOpen = true))
        assertEquals(listOf(true), open.map { it.selected })
    }

    @Test
    fun `smudge options lead with its modes`() {
        val entries = carouselEntries(CarouselCategory.OPTIONS, inputs(tool = Tool.SMUDGE))
        assertEquals(CarouselAction.SmudgeMode(ColorSmudgeEngine.Mode.SMEAR), entries[0].action)
        assertEquals(1, selectedCarouselIndex(entries))
    }

    @Test
    fun `select options are the selection shapes`() {
        val entries = carouselEntries(CarouselCategory.OPTIONS, inputs(tool = Tool.SELECT))
        assertEquals(SelectionShape.entries.size + 1, entries.size)
        val selected = entries[selectedCarouselIndex(entries)!!]
        assertEquals(CarouselAction.SelectShape(SelectionShape.ELLIPSE), selected.action)
    }

    @Test
    fun `stabilizer algorithms appear only while the stabilizer is on`() {
        val off = carouselEntries(CarouselCategory.OPTIONS, inputs(tool = Tool.BRUSH, stabilizer = 0))
        assertFalse(off.any { it.action is CarouselAction.Stabilizer })
        assertEquals(CarouselAction.StabilizerLevel(0), off[selectedCarouselIndex(off)!!].action)

        val on = carouselEntries(CarouselCategory.OPTIONS, inputs(tool = Tool.BRUSH, stabilizer = 50))
        assertTrue(on.any { it.action == CarouselAction.Stabilizer(StabilizerAlgorithm.STREAMLINE) && it.selected })
        assertEquals(CarouselAction.StabilizerLevel(50), on[selectedCarouselIndex(on)!!].action)
    }

    @Test
    fun `entry keys are unique on every page`() {
        CarouselCategory.entries.forEach { category ->
            Tool.entries.forEach { tool ->
                val keys = carouselEntries(category, inputs(tool = tool, stabilizer = 25)).map { it.key }
                assertEquals(keys.size, keys.toSet().size)
            }
        }
    }
}
