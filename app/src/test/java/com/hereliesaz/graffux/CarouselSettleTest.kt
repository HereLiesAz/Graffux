package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarouselSettleTest {

    private fun entry(action: CarouselAction, selected: Boolean = false) =
        CarouselEntry(key = "k", label = "l", action = action, selected = selected)

    private val picks = listOf(
        CarouselAction.BuiltInBrush("Round"),
        CarouselAction.CustomBrush("c"),
        CarouselAction.ExtensionBrush("e"),
        CarouselAction.InkUtensilPick(INK_UTENSIL_CATALOG.first().utensil),
        CarouselAction.PickTool(Tool.BLUR),
    )

    private val tapOnly = listOf(
        CarouselAction.StabilizerLevel(2),
        CarouselAction.Stabilizer(StabilizerAlgorithm.STREAMLINE),
        CarouselAction.SmudgeMode(ColorSmudgeEngine.Mode.DULLING),
        CarouselAction.SelectShape(SelectionShape.ELLIPSE),
    )

    @Test
    fun `brushes, Ink utensils and effect tools auto-activate on settle`() {
        picks.forEach { assertTrue("$it", carouselAutoActivates(it)) }
        picks.forEach { assertTrue("$it", carouselSettleSelects(entry(it), byTap = false)) }
    }

    @Test
    fun `settings stops and Tool Options never fire from a scroll, only from a tap`() {
        tapOnly.forEach { assertFalse("$it", carouselAutoActivates(it)) }
        tapOnly.forEach { assertFalse("$it", carouselSettleSelects(entry(it), byTap = false)) }
        tapOnly.forEach { assertTrue("$it", carouselSettleSelects(entry(it), byTap = true)) }
    }

    @Test
    fun `settling on the already-selected hero is a no-op`() {
        (picks + tapOnly).forEach {
            assertFalse("$it", carouselSettleSelects(entry(it, selected = true), byTap = false))
            assertFalse("$it", carouselSettleSelects(entry(it, selected = true), byTap = true))
        }
    }
}
