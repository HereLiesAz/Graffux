package com.hereliesaz.graffux

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far up the carousel stage of the sheet is pulled. [SHORT]: the horizontal carousel strip.
 * [HALF] and [FULL]: the current set as a grid of the same cards, about half and nearly all of the
 * screen tall.
 */
internal enum class CarouselDetent { SHORT, HALF, FULL }

/** Whether [this] detent shows the set as a grid rather than the carousel strip. */
internal val CarouselDetent.showsGrid: Boolean get() = this != CarouselDetent.SHORT

/**
 * One stage up: the tab list opens the carousel (always at [CarouselDetent.SHORT]); the carousel
 * steps on to the half and then the full grid when [gridAllowed] (the card sheets, not the photo or
 * design bodies). Already at the top, nothing changes.
 */
internal fun CarouselUi.steppedUp(gridAllowed: Boolean): CarouselUi = when {
    !showingCarousel -> copy(showingCarousel = true, detent = CarouselDetent.SHORT)
    !gridAllowed -> this
    detent == CarouselDetent.SHORT -> copy(detent = CarouselDetent.HALF)
    detent == CarouselDetent.HALF -> copy(detent = CarouselDetent.FULL)
    else -> this
}

/**
 * The sheet Tool Options opens: up on [category] at the short detent, where the carousel's hero can
 * grow into the item's controls (the grid has no hero to grow).
 */
internal fun CarouselUi.forToolOptions(category: CarouselCategory): CarouselUi =
    copy(category = category, sheetOpen = true, tab = null, showingCarousel = true, detent = CarouselDetent.SHORT)

/**
 * One stage down, or null when the sheet is at its lowest open stage and shuts: the tab list, or,
 * on a sheet with no tab list ([tabListStage] false: the brush page, whose packs are on the rail),
 * the carousel.
 */
internal fun CarouselUi.steppedDown(tabListStage: Boolean = true): CarouselUi? = when {
    !showingCarousel -> null
    !tabListStage && detent == CarouselDetent.SHORT -> null
    detent == CarouselDetent.FULL -> copy(detent = CarouselDetent.HALF)
    detent == CarouselDetent.HALF -> copy(detent = CarouselDetent.SHORT)
    else -> copy(showingCarousel = false)
}

/** The grid's height at [detent]: half the screen, or all of it but the room above the sheet. */
internal fun carouselGridHeight(detent: CarouselDetent, screenHeight: Dp): Dp = when (detent) {
    CarouselDetent.FULL -> screenHeight * FULL_FRACTION
    else -> screenHeight * HALF_FRACTION
}

private const val HALF_FRACTION = 0.5f
private const val FULL_FRACTION = 0.8f
private val GridSpacing = 8.dp

/**
 * The current set as a grid: every entry once, as the same card (size and content) the carousel's
 * hero shows, and the grid ends with the set. Tapping a card picks it; its star stars it.
 */
@Suppress("FunctionNaming")
@Composable
internal fun CarouselSetGrid(
    detent: CarouselDetent,
    content: CarouselContent,
    onEntryClick: (CarouselEntry) -> Unit,
    onToggleFavorite: (CarouselEntry) -> Unit,
) {
    val screen = LocalConfiguration.current.screenHeightDp.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(HeroRestSize.width),
        modifier = Modifier
            .fillMaxWidth()
            .height(carouselGridHeight(detent, screen))
            .testTag("carousel.grid.${detent.name}"),
        contentPadding = PaddingValues(GridSpacing),
        horizontalArrangement = Arrangement.spacedBy(GridSpacing, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(GridSpacing),
    ) {
        items(content.entries, key = { it.key }) { entry ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GridCard(entry, content, onEntryClick, onToggleFavorite)
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun GridCard(
    entry: CarouselEntry,
    content: CarouselContent,
    onEntryClick: (CarouselEntry) -> Unit,
    onToggleFavorite: (CarouselEntry) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // A selected brush card keeps the dark fill (its live stroke is in the brush colour) and is
    // marked by an accent border instead.
    val brushFace = isBrushCard(entry)
    val lightFill = entry.selected && !brushFace
    val border = when {
        lightFill -> Modifier
        entry.selected -> Modifier.border(BorderStroke(2.dp, colors.primary), CardShape)
        else -> Modifier.border(BorderStroke(1.dp, colors.outline), CardShape)
    }
    Box(
        Modifier
            .size(HeroRestSize)
            .clip(CardShape)
            .then(border)
            .background(if (lightFill) colors.onSurface else colors.surfaceVariant)
            .clickable(role = Role.Tab) { onEntryClick(entry) }
            .semantics {
                selected = entry.selected
                contentDescription = entry.label
                if (entry.favorite) stateDescription = "Favorite"
            }
            .testTag("carousel.grid.card"),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (lightFill) colors.surface else colors.onSurface
        if (brushFace) {
            BrushCardFace(entry, content, tint, onEntryClick, onToggleFavorite)
        } else {
            CardIdentity(entry, CarouselTier.HERO, content, tint, onToggleFavorite)
        }
    }
}
