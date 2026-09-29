package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.model.InkUtensil
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine

/**
 * What the bottom carousel is showing. Chosen by the tab row below it, never inferred from the tool
 * in hand: Smudge, Blur and friends all paint *with* the current brush, so "an effect tool is armed"
 * is exactly when someone may want the Brushes page, and a carousel that flipped itself away from
 * the page they picked would fight them.
 *
 * [BRUSHES] is stamp brushes only (built-in, Brush Studio, extension); [INK] is the Jetpack Ink
 * utensils. They are different engines, so they are different pages.
 */
internal enum class CarouselCategory(val label: String) {
    FAVORITES("Favorites"),
    BRUSHES("Brushes"),
    INK("Ink"),
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

    /**
     * One declared filter or tool of an installed azphalt code/mixed extension, run the way the
     * Extensions panel runs it: `vm.onExtensionContributionSelected`, which shows the contribution's
     * own params panel when it declares one, else executes it in the sandbox straight away.
     */
    data class ExtensionContribution(
        val extensionId: String,
        val contributionId: String,
        val kind: ExtensionEffectKind,
    ) : CarouselAction

    /** An installed LUT extension, graded onto the active layer by `vm.applyInstalledLut`. */
    data class ExtensionLut(val extensionId: String) : CarouselAction
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
    /** Starred by the user; drawn as a star on the card and listed on the Favorites page. */
    val favorite: Boolean = false,
    /** Set on installed-extension effects: what the hero card's details and tip visual read. */
    val extensionEffect: ExtensionEffect? = null,
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
    /** Installed azphalt filters, tools and LUTs, listed on the Effects page after [EFFECT_TOOLS]. */
    val extensionEffects: List<ExtensionEffect> = emptyList(),
    /** Starred entry keys, in the order they were starred (`SettingsRepository.carouselFavorites`). */
    val favorites: List<String> = emptyList(),
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

internal fun carouselEntries(category: CarouselCategory, input: CarouselInputs): List<CarouselEntry> {
    val raw = when (category) {
        CarouselCategory.FAVORITES -> return favoriteEntries(input)
        CarouselCategory.BRUSHES -> brushEntries(input)
        CarouselCategory.INK -> inkEntries(input)
        CarouselCategory.EFFECTS -> effectEntries(input)
        CarouselCategory.OPTIONS -> optionEntries(input, allOptions = false)
    }
    return raw.markFavorites(input.favorites)
}

private fun List<CarouselEntry>.markFavorites(favorites: List<String>): List<CarouselEntry> {
    if (favorites.isEmpty()) return this
    val set = favorites.toSet()
    return map { if (it.key in set) it.copy(favorite = true) else it }
}

/**
 * Every starred entry, in starring order. Looked up across every page — Options included in full,
 * not just the stops the tool in hand shows — so a starred Smudge mode stays on this page whatever
 * tool is armed. A key whose entry no longer exists (a deleted custom brush) is skipped, not shown.
 */
private fun favoriteEntries(input: CarouselInputs): List<CarouselEntry> {
    if (input.favorites.isEmpty()) return emptyList()
    val all = (brushEntries(input) + inkEntries(input) + effectEntries(input) + optionEntries(input, allOptions = true))
        .associateBy { it.key }
    return input.favorites.distinct().mapNotNull { key -> all[key]?.copy(favorite = true) }
}

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
}

private fun inkEntries(input: CarouselInputs): List<CarouselEntry> = INK_UTENSIL_CATALOG.map { entry ->
    CarouselEntry(
        key = "ink.${entry.utensil.id}", label = entry.label,
        action = CarouselAction.InkUtensilPick(entry.utensil),
        selected = input.activeInkUtensil == entry.utensil, icon = entry.icon,
    )
}

private fun effectEntries(input: CarouselInputs): List<CarouselEntry> = EFFECT_TOOLS.mapNotNull { tool ->
    val entry = TOOL_CATALOG[tool] ?: return@mapNotNull null
    CarouselEntry(
        key = entry.id, label = entry.label,
        action = CarouselAction.PickTool(tool),
        selected = input.activeTool == tool, icon = entry.icon,
    )
} + input.extensionEffects.distinctBy(::extensionEffectKey).map(::extensionEffectEntry)

private fun optionEntries(input: CarouselInputs, allOptions: Boolean): List<CarouselEntry> = buildList {
    val tool = input.activeTool
    if (allOptions || tool == Tool.SMUDGE) addAll(smudgeEntries(input))
    if (allOptions || tool == Tool.SELECT) addAll(selectEntries(input))
    if (allOptions || tool in STABILIZED_TOOLS) addAll(stabilizerEntries(input, allOptions))
    // No "All options" card: every setting now lives on its item's own expanded ("More") card.
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

private fun stabilizerEntries(input: CarouselInputs, allOptions: Boolean): List<CarouselEntry> = buildList {
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
        if (allOptions || input.stabilizerLevel > 0) {
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
