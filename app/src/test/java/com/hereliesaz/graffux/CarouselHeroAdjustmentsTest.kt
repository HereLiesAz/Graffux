package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.InkUtensil
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarouselHeroAdjustmentsTest {

    private val state = HeroAdjustmentState(
        brushSize = 42f, brushFlow = 0.8f, brushOpacity = 0.6f, brushFeathering = 0.25f,
        smudgeRate = 0.5f, stabilizerLevel = 30, magicWandTolerance = 64,
    )

    private fun entry(action: CarouselAction) = CarouselEntry(key = "k", label = "l", action = action, selected = true)

    private fun ids(action: CarouselAction) = heroAdjustments(entry(action), state).map { it.id }

    @Test
    fun `stamp brushes get size, flow and softness`() {
        val brushes = listOf(
            CarouselAction.BuiltInBrush(BuiltInBrushes.presets.first().name),
            CarouselAction.CustomBrush("c"),
            CarouselAction.ExtensionBrush("e::0"),
        )
        brushes.forEach { assertEquals(listOf("size", "flow", "softness"), ids(it)) }
        val adj = heroAdjustments(entry(brushes.first()), state)
        assertEquals(listOf(42f, 0.8f, 0.25f), adj.map { it.value })
        val setters = listOf(HeroSetter.BRUSH_SIZE, HeroSetter.BRUSH_FLOW, HeroSetter.BRUSH_SOFTNESS)
        assertEquals(setters, adj.map { it.setter })
    }

    @Test
    fun `Ink utensils get size and opacity only`() {
        InkUtensil.entries.forEach { assertEquals(listOf("size", "opacity"), ids(CarouselAction.InkUtensilPick(it))) }
    }

    @Test
    fun `smudge gets strength first, the other effect tools size and softness`() {
        assertEquals(listOf("strength", "size", "softness"), ids(CarouselAction.PickTool(Tool.SMUDGE)))
        EFFECT_TOOLS.filter { it != Tool.SMUDGE }.forEach {
            assertEquals(listOf("size", "softness"), ids(CarouselAction.PickTool(it)))
        }
    }

    @Test
    fun `options carry their own value, or nothing`() {
        assertEquals(listOf("stabilizer"), ids(CarouselAction.StabilizerLevel(25)))
        assertEquals(listOf("stabilizer"), ids(CarouselAction.Stabilizer(StabilizerAlgorithm.STREAMLINE)))
        assertEquals(listOf("strength"), ids(CarouselAction.SmudgeMode(ColorSmudgeEngine.Mode.SMEAR)))
        assertEquals(listOf("threshold"), ids(CarouselAction.SelectShape(SelectionShape.AUTOMATIC)))
        assertTrue(ids(CarouselAction.SelectShape(SelectionShape.ELLIPSE)).isEmpty())
        assertTrue(ids(CarouselAction.OpenToolOptions).isEmpty())
    }

    @Test
    fun `installed filters, tools and LUTs have no inline sliders and no More`() {
        val ext = listOf(
            CarouselAction.ExtensionContribution("x", "f", ExtensionEffectKind.FILTER),
            CarouselAction.ExtensionContribution("x", "t", ExtensionEffectKind.TOOL),
            CarouselAction.ExtensionLut("x"),
        )
        ext.forEach {
            assertTrue(ids(it).isEmpty())
            assertFalse(heroHasMore(entry(it)))
        }
        assertTrue(heroHasMore(entry(CarouselAction.PickTool(Tool.BLUR))))
        assertFalse(heroHasMore(entry(CarouselAction.OpenToolOptions)))
    }

    @Test
    fun `never more than three sliders, and values clamp and round to the setting`() {
        val all = listOf(
            CarouselAction.BuiltInBrush("Round"), CarouselAction.InkUtensilPick(InkUtensil.PEN),
            CarouselAction.PickTool(Tool.SMUDGE), CarouselAction.StabilizerLevel(0),
        )
        all.forEach { assertTrue(heroAdjustments(entry(it), state).size <= MAX_HERO_ADJUSTMENTS) }
        val size = heroAdjustments(entry(CarouselAction.BuiltInBrush("Round")), state).first()
        assertEquals(MAX_BRUSH_SIZE, heroSetterValue(size, 999f))
        assertEquals("Size 42 px", heroAdjustmentText(size))
        val stab = heroAdjustments(entry(CarouselAction.StabilizerLevel(0)), state).single()
        assertEquals(31f, heroSetterValue(stab, 30.6f))
    }
}
