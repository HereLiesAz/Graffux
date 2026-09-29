package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.model.InkUtensil
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine

/**
 * What the bottom carousel is showing. Chosen by the segmented switch above it, never inferred from
 * the tool in hand: Smudge, Blur and friends all paint *with* the current brush, so "an effect tool
 * is armed" is exactly when someone may want the Brushes page, and a carousel that flipped itself
 * away from the page they picked would fight them.
 */
internal enum class CarouselCategory(val label: String) {
    BRUSHES("Brushes"),
    EFFECTS("Effects"),
    OPTIONS("Options"),
}

/** What tapping a carousel item does. Every case maps onto an existing `EditorViewModel` call. */
internal sealed interface CarouselAction {
    data class BuiltInBrush(val name: String) : CarouselAction
    data class CustomBrush(val id: String) : CarouselAction
    data class ExtensionBrush(val id: String) : CarouselAction

    /** A Jetpack Ink utensil (`vm.selectInkUtensil`) — not a stamp brush, whatever its name. */
    data class InkUtensilPick(val utensil: InkUtensil) : CarouselAction

    /** Arms [tool], or puts it down when it is already in hand — the rail's own second-tap rule. */
    data class PickTool(val tool: Tool) : CarouselAction
    data class StabilizerLevel(val level: Int) : CarouselAction
    data class Stabilizer(val algorithm: StabilizerAlgorithm) : CarouselAction
    data class SmudgeMode(val mode: ColorSmudgeEngine.Mode) : CarouselAction
    data class SelectShape(val shape: SelectionShape) : CarouselAction

    /** The full Tool Options window — every slider the carousel's discrete stops don't cover. */
    data object OpenToolOptions : CarouselAction
}

/**
 * One carousel item. [brush] is set for built-in and custom brushes so the item can draw a live
 * stroke preview; extension brushes carry their pre-rendered bitmap by [key] instead.
 */
internal data class CarouselEntry(
    val key: String,
    val label: String,
    val action: CarouselAction,
    val selected: Boolean,
    val icon: Int? = null,
    val brush: AzphaltBrush? = null,
)

/** The editor state the carousel reads — a narrow slice, so the derivation stays unit-testable. */
internal data class CarouselInputs(
    val activeTool: Tool,
    val activeBrushName: String?,
    /**
     * The Ink utensil in hand (`vm.activeInkUtensil`), or null. While one is, only its entry is lit:
     * [activeBrushName] then carries the utensil's display name, which the built-in "Ink Pen" shares.
     */
    val activeInkUtensil: InkUtensil? = null,
    val builtInBrushes: List<AzphaltBrush>,
    /** Saved Brush Studio brushes, as (id, brush). */
    val customBrushes: List<Pair<String, AzphaltBrush>>,
    /** Installed extension brushes, as (composite id, display name). */
    val extensionBrushes: List<Pair<String, String>>,
    val stabilizerLevel: Int,
    val stabilizerAlgorithm: StabilizerAlgorithm,
    val smudgeMode: ColorSmudgeEngine.Mode,
    val selectionShape: SelectionShape,
    val toolOptionsOpen: Boolean,
)

/** Tools that act on existing pixels rather than lay new paint down — the "Effects" page. */
internal val EFFECT_TOOLS: List<Tool> = listOf(
    Tool.BLUR, Tool.SHARPEN, Tool.SMUDGE, Tool.LIQUIFY,
    Tool.DODGE, Tool.BURN, Tool.HEAL, Tool.CLONE, Tool.COLOR,
)

/** Tools whose strokes go through the stabilizer, so its stops are meaningful options for them. */
internal val STABILIZED_TOOLS: Set<Tool> = setOf(
    Tool.BRUSH, Tool.ERASER, Tool.PEN, Tool.BLUR, Tool.SHARPEN, Tool.SMUDGE,
    Tool.DODGE, Tool.BURN, Tool.HEAL, Tool.CLONE, Tool.COLOR,
)

/** Discrete stabilizer stops. The slider in Tool Options still reaches every value in between. */
private const val STABILIZER_STEP = 25
private const val STABILIZER_STOP_COUNT = 4
internal val STABILIZER_STOPS: List<Pair<String, Int>> = List(STABILIZER_STOP_COUNT) { i ->
    val level = i * STABILIZER_STEP
    (if (level == 0) "Stabilizer off" else "Stabilizer $level") to level
}

internal fun carouselEntries(category: CarouselCategory, input: CarouselInputs): List<CarouselEntry> =
    when (category) {
        CarouselCategory.BRUSHES -> brushEntries(input)
        CarouselCategory.EFFECTS -> effectEntries(input)
        CarouselCategory.OPTIONS -> optionEntries(input)
    }

/**
 * The item the carousel should centre on: the first selected entry, or null when nothing on this
 * page is current (the carousel then stays where the user left it rather than jumping to item 0).
 */
internal fun selectedCarouselIndex(entries: List<CarouselEntry>): Int? =
    entries.indexOfFirst { it.selected }.takeIf { it >= 0 }

private fun brushEntries(input: CarouselInputs): List<CarouselEntry> = buildList {
    // Same precedence as the rail's classifiers: a name can collide across the three sources, and
    // only the first match is lit, so at most one item is ever "current". While an Ink utensil is in
    // hand, name matching is skipped entirely: only that utensil's entry below is lit.
    var lit = input.activeInkUtensil != null
    fun isActive(name: String): Boolean = (!lit && name == input.activeBrushName).also { if (it) lit = true }
    input.builtInBrushes.forEach { brush ->
        add(
            CarouselEntry(
                key = "builtin.${brush.name}", label = brush.name,
                action = CarouselAction.BuiltInBrush(brush.name),
                selected = isActive(brush.name), brush = brush,
            ),
        )
    }
    input.customBrushes.forEach { (id, brush) ->
        add(
            CarouselEntry(
                key = "custom.$id", label = brush.name,
                action = CarouselAction.CustomBrush(id),
                selected = isActive(brush.name), brush = brush,
            ),
        )
    }
    input.extensionBrushes.forEach { (id, name) ->
        add(
            CarouselEntry(
                key = "ext.$id", label = name,
                action = CarouselAction.ExtensionBrush(id),
                selected = isActive(name), icon = GraffuxIcons.BrushImport,
            ),
        )
    }
    INK_UTENSIL_CATALOG.forEach { entry ->
        add(
            CarouselEntry(
                key = "ink.${entry.utensil.id}", label = entry.label,
                action = CarouselAction.InkUtensilPick(entry.utensil),
                selected = input.activeInkUtensil == entry.utensil, icon = entry.icon,
            ),
        )
    }
}

private fun effectEntries(input: CarouselInputs): List<CarouselEntry> = EFFECT_TOOLS.mapNotNull { tool ->
    val entry = TOOL_CATALOG[tool] ?: return@mapNotNull null
    CarouselEntry(
        key = entry.id, label = entry.label,
        action = CarouselAction.PickTool(tool),
        selected = input.activeTool == tool, icon = entry.icon,
    )
}

private fun optionEntries(input: CarouselInputs): List<CarouselEntry> = buildList {
    val tool = input.activeTool
    if (tool == Tool.SMUDGE) addAll(smudgeEntries(input))
    if (tool == Tool.SELECT) addAll(selectEntries(input))
    if (tool in STABILIZED_TOOLS) addAll(stabilizerEntries(input))
    add(
        CarouselEntry(
            key = "toolOptions", label = "All options",
            action = CarouselAction.OpenToolOptions,
            selected = input.toolOptionsOpen, icon = GraffuxIcons.BrushSettings,
        ),
    )
}

private fun smudgeEntries(input: CarouselInputs): List<CarouselEntry> = buildList {
    run {
        ColorSmudgeEngine.Mode.entries.forEach { mode ->
            add(
                CarouselEntry(
                    key = "smudge.${mode.name}",
                    label = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                    action = CarouselAction.SmudgeMode(mode),
                    selected = input.smudgeMode == mode, icon = GraffuxIcons.Smudge,
                ),
            )
        }
    }
}

private fun selectEntries(input: CarouselInputs): List<CarouselEntry> = buildList {
    run {
        SelectionShape.entries.forEach { shape ->
            add(
                CarouselEntry(
                    key = "selectShape.${shape.name}", label = shape.label,
                    action = CarouselAction.SelectShape(shape),
                    selected = input.selectionShape == shape,
                    icon = when (shape) {
                        SelectionShape.FREEHAND -> GraffuxIcons.SelectLasso
                        SelectionShape.RECTANGLE -> GraffuxIcons.SelectRect
                        SelectionShape.ELLIPSE -> GraffuxIcons.SelectEllipse
                        SelectionShape.AUTOMATIC -> GraffuxIcons.SelectWand
                    },
                ),
            )
        }
    }
}

private fun stabilizerEntries(input: CarouselInputs): List<CarouselEntry> = buildList {
    run {
        STABILIZER_STOPS.forEach { (label, level) ->
            add(
                CarouselEntry(
                    key = "stabilizer.$level", label = label,
                    action = CarouselAction.StabilizerLevel(level),
                    selected = input.stabilizerLevel == level, icon = GraffuxIcons.PenInk,
                ),
            )
        }
        // Only worth choosing while the stabilizer is on; with it off every algorithm is a no-op.
        if (input.stabilizerLevel > 0) {
            StabilizerAlgorithm.entries.forEach { algorithm ->
                add(
                    CarouselEntry(
                        key = "stabilizerAlgo.${algorithm.name}", label = algorithm.label,
                        action = CarouselAction.Stabilizer(algorithm),
                        selected = input.stabilizerAlgorithm == algorithm, icon = GraffuxIcons.PenInk,
                    ),
                )
            }
        }
    }
}
