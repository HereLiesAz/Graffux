package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.CarouselItemSettings
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The carousel plan functions (#529, #533) across the old-project -> new-project switch: the new
 * project's LoadedProject drops the tool to NONE (and clears layers/selection) while item settings
 * from project A are still loaded. None of it may throw, whatever order the frames arrive in.
 */
class ProjectSwitchPlanTest {
    private fun inputs(tool: Tool, brush: String?) = CarouselInputs(
        activeTool = tool,
        activeBrushName = brush,
        builtInBrushes = BuiltInBrushes.presets,
        customBrushes = emptyList(),
        extensionBrushes = emptyList(),
        stabilizerLevel = 0,
        stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
        smudgeMode = ColorSmudgeEngine.Mode.DULLING,
        selectionShape = SelectionShape.ELLIPSE,
    )

    @Test
    fun `A open, new project B loads with no tool, then a brush again`() {
        val brush = BuiltInBrushes.presets[1].name
        val states = listOf(
            inputs(Tool.BRUSH, brush), // project A
            inputs(Tool.NONE, brush), // B's LoadedProject
            inputs(Tool.NONE, null),
            inputs(Tool.SMUDGE, null),
            inputs(Tool.BRUSH, brush),
        )
        val sync = CarouselItemSettingsSync()
        val stored = mutableMapOf("builtin.$brush" to CarouselItemSettings(size = 20f))
        var live = CarouselItemSettings(size = 50f)
        for (s in states) {
            for (category in CarouselCategory.entries) carouselEntries(category, s)
            toolOptionsTarget(s)
            when (val d = sync.observe(activeCarouselSettingsKey(s), live, stored)) {
                is ItemSettingsDecision.Apply -> live = d.settings
                is ItemSettingsDecision.Save -> stored[d.key] = d.settings
                ItemSettingsDecision.None -> Unit
            }
            sync.observe(activeCarouselSettingsKey(s), live, null)
        }
        assertEquals(20f, live.size)
    }
}
