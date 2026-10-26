package com.hereliesaz.graffux

/**
 * [favorites] with [key] toggled: removing it keeps the rest in place, adding it appends, so the
 * Favorites page keeps the order the user starred things in. The repository applies the same rule.
 */
internal fun toggleCarouselFavorite(favorites: List<String>, key: String): List<String> =
    if (key in favorites) favorites - key else favorites + key

/**
 * The item the carousel should centre on: the first selected entry, or null when nothing on this
 * page is current (the carousel then stays where the user left it rather than jumping to item 0).
 */
internal fun selectedCarouselIndex(entries: List<CarouselEntry>): Int? =
    entries.indexOfFirst { it.selected }.takeIf { it >= 0 }

/**
 * Whether scrolling [action] into the hero slot, with no tap, may run it. Only picks that simply
 * replace the thing in hand do: brushes, Ink utensils and effect tools. Stabilizer, smudge and
 * selection-shape stops change a setting and Tool Options opens a window, so those need a tap.
 *
 * Installed azphalt effects need a tap too, every kind. A LUT (`applyInstalledLut`) regrades and
 * replaces the active layer's pixels. A filter *and* an extension tool both go through
 * `onExtensionContributionSelected`, which executes the contribution's sandbox entry once (or opens
 * its params panel) — an extension "tool" arms nothing the way `setActiveTool` does, it runs.
 */
internal fun carouselAutoActivates(action: CarouselAction): Boolean = when (action) {
    is CarouselAction.BuiltInBrush,
    is CarouselAction.CustomBrush,
    is CarouselAction.ExtensionBrush,
    is CarouselAction.InkUtensilPick,
    is CarouselAction.PickTool,
    -> true
    is CarouselAction.StabilizerLevel,
    is CarouselAction.Stabilizer,
    is CarouselAction.SmudgeMode,
    is CarouselAction.SelectShape,
    is CarouselAction.ExtensionContribution,
    is CarouselAction.ExtensionLut,
    is CarouselAction.GroupCommand,
    -> false
}

/**
 * Whether the row coming to rest with [entry] in the hero slot should run its action. Never for
 * the entry already selected (re-running a pick can put a tool down), so a settle on the same hero
 * is a no-op and the selection's own re-centre can't loop back into a click. A tap ([byTap]) runs
 * any action; a drag or fling only an auto-activating one ([carouselAutoActivates]).
 */
internal fun carouselSettleSelects(entry: CarouselEntry, byTap: Boolean): Boolean =
    !entry.selected && (byTap || carouselAutoActivates(entry.action))
