package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.GraffuxIcons

/**
 * The one description of a tool: its rail item id, its label, and its glyph.
 *
 * There used to be two. `TOOL_IDS` held the ids, and the label and glyph were written a second time
 * at each `toolItem(...)` call site — which is fine while the rail is the only thing that draws
 * tools, and stops being fine the moment a second surface does. The shortcuts sheet is that second
 * surface, and rather than let it invent its own names for the same fourteen tools, both read this.
 *
 * Two of the labels do not match their enum ([Tool.COLOR] is "Colorize", [Tool.SELECT] is "Select"
 * rather than "Selection"), which is exactly the sort of pair that drifts when it is written twice.
 */
internal data class ToolEntry(val id: String, val label: String, val icon: Int)

internal val TOOL_CATALOG: Map<Tool, ToolEntry> = mapOf(
    Tool.BRUSH to ToolEntry("tool.brush", "Brush", GraffuxIcons.Brush),
    Tool.ERASER to ToolEntry("tool.eraser", "Eraser", GraffuxIcons.Eraser),
    Tool.FILL to ToolEntry("tool.fill", "Fill", GraffuxIcons.Colordrop),
    Tool.SELECT to ToolEntry("tool.select", "Select", GraffuxIcons.SelectSubject),
    Tool.PEN to ToolEntry("tool.pen", "Pen", GraffuxIcons.PenInk),
    Tool.HEAL to ToolEntry("tool.heal", "Heal", GraffuxIcons.Heal),
    Tool.CLONE to ToolEntry("tool.clone", "Clone", GraffuxIcons.Stamp),
    Tool.BLUR to ToolEntry("tool.blur", "Blur", GraffuxIcons.Blur),
    Tool.SHARPEN to ToolEntry("tool.sharpen", "Sharpen", GraffuxIcons.Sharpen),
    Tool.SMUDGE to ToolEntry("tool.smudge", "Smudge", GraffuxIcons.Smudge),
    Tool.LIQUIFY to ToolEntry("tool.liquify", "Liquify", GraffuxIcons.Liquify),
    Tool.DODGE to ToolEntry("tool.dodge", "Dodge", GraffuxIcons.Dodge),
    Tool.BURN to ToolEntry("tool.burn", "Burn", GraffuxIcons.Burn),
    Tool.COLOR to ToolEntry("tool.colorize", "Colorize", GraffuxIcons.ColorDisc),
)

/**
 * The rail item id for each [Tool] — the mapping `activeRailClassifiers` reads to work out which
 * item to light, derived from the catalogue so the two cannot disagree.
 *
 * [Tool.NONE] is absent on purpose: it is the absence of a tool, so nothing lights up.
 */
internal val TOOL_IDS: Map<Tool, String> = TOOL_CATALOG.mapValues { it.value.id }
