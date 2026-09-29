package com.hereliesaz.graffux



/**
 * One slot in the tab row under the carousel. [Page] tabs switch what the strip shows; [Undo] and
 * [Redo] have no page — tapping one runs the history call and leaves the current page selected.
 */
internal sealed interface CarouselTab {
    val label: String

    data object Undo : CarouselTab {
        override val label = "Undo"
    }

    data class Page(val category: CarouselCategory) : CarouselTab {
        override val label: String get() = category.label
    }

    data object Redo : CarouselTab {
        override val label = "Redo"
    }
}

/** The tab row, left to right: history at either end, the pages in between. */
internal val CAROUSEL_TABS: List<CarouselTab> = buildList {
    add(CarouselTab.Undo)
    CarouselCategory.entries.forEach { add(CarouselTab.Page(it)) }
    add(CarouselTab.Redo)
}

/** Whether [tab] can be tapped right now: history tabs only while there is history that way. */
internal fun carouselTabEnabled(tab: CarouselTab, undoCount: Int, redoCount: Int): Boolean = when (tab) {
    CarouselTab.Undo -> undoCount > 0
    CarouselTab.Redo -> redoCount > 0
    is CarouselTab.Page -> true
}

/**
 * Whether the rail's own Undo and Redo items are shown. They exist for when the carousel's tab row
 * (which has Undo and Redo at its ends) is not on screen: shown while the sheet is shut, or while the
 * carousel is off screen for any other reason (hidden from the areas dropdown, a bottom panel up, the
 * UI hidden for capture); gone while the open sheet already offers both, so the two never duplicate.
 */
internal fun railHistoryItemsVisible(carouselOnScreen: Boolean, sheetOpen: Boolean): Boolean =
    !(carouselOnScreen && sheetOpen)

/** A vertical fling faster than this (dp/s) decides the sheet's side on its own. */
private const val SHEET_FLING_DP_PER_SEC = 400f

/** Past this fraction of the way shut, a slow release settles the sheet shut. */
private const val SHEET_HALFWAY = 0.5f

/**
 * Where the carousel sheet settles when a drag ends: [fractionShut] is how far down it is (0 = open,
 * 1 = shut), [velocityDpPerSec] the release velocity (positive = downward). A decisive fling wins;
 * otherwise it goes to whichever side it is nearer.
 */
internal fun carouselSheetSettlesOpen(fractionShut: Float, velocityDpPerSec: Float): Boolean = when {
    velocityDpPerSec <= -SHEET_FLING_DP_PER_SEC -> true
    velocityDpPerSec >= SHEET_FLING_DP_PER_SEC -> false
    else -> fractionShut < SHEET_HALFWAY
}

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
