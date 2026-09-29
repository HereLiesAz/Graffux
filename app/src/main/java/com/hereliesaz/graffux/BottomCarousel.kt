package com.hereliesaz.graffux

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.feature.editor.BrushPreview

private val CarouselHeight = 124.dp
private val HeroItemWidth = 168.dp
private val ItemSpacing = 6.dp
private val TipSize = 36.dp
private val HeroTipSize = 44.dp
internal val StarTouchSize = 32.dp
internal val StarSize = 18.dp
private val MaxCarouselWidth = 560.dp
private val PreviewHeight = 40.dp
private val TabRowHeight = 36.dp
internal val CardShape = RoundedCornerShape(20.dp)

/** Which page is showing and whether the sheet is open — host-owned, so it survives recomposition. */
internal data class CarouselUi(val category: CarouselCategory, val sheetOpen: Boolean)

/** What the strip draws: its items plus what the brush previews need to render them. */
internal data class CarouselContent(
    val entries: List<CarouselEntry>,
    val brushColor: Color,
    val secondaryColor: Color,
    val extensionPreviews: Map<String, Bitmap>,
    /** Installed brushes' tip thumbnails (`EditorViewModel.installedBrushTips`), by composite id. */
    val extensionTips: Map<String, Bitmap> = emptyMap(),
)

/** The history state the Undo/Redo tabs read, and the calls they make. */
internal data class CarouselHistory(
    val undoCount: Int,
    val redoCount: Int,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
)

/**
 * M3 Expressive hero carousel of brushes, Ink utensils, effect tools and tool options.
 *
 * Top to bottom: the selected entry's stroke preview (drawn *above* the hero card, not inside it),
 * the [HorizontalCenteredHeroCarousel] — one large item in the exact centre, smaller items browsing
 * off either side, the selection snapped into the centre — and the tab row (see [CAROUSEL_TABS]).
 * The host places this across the full window width (an AzNavRail `background` page), so "centre"
 * means the centre of the screen, not of the strip beside the rail.
 *
 * Cards come in three tiers, set by the carousel's keyline strategy (see [carouselTier]): the hero
 * shows the tip visual, name and details; medium cards the tip and name; small cards the tip alone.
 * Hero and medium cards carry a star toggle ([onToggleFavorite]) — outlined, or filled when starred.
 */
@Suppress("FunctionNaming", "LongParameterList") // Composable naming, as in SettingsScreen.kt.
@Composable
internal fun BottomCarousel(
    ui: CarouselUi,
    onUiChange: (CarouselUi) -> Unit,
    content: CarouselContent,
    history: CarouselHistory,
    onEntryClick: (CarouselEntry) -> Unit,
    onToggleFavorite: (CarouselEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(max = MaxCarouselWidth).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val selected = selectedCarouselIndex(content.entries)?.let { content.entries[it] }
        SelectedPreview(selected, content)
        when {
            content.entries.isNotEmpty() -> key(ui.category) {
                CarouselStrip(content, onEntryClick, onToggleFavorite)
            }
            ui.category == CarouselCategory.FAVORITES -> EmptyHint(
                "No favorites yet. Tap the star on any brush, Ink, effect or option card to add it.",
            )
        }
        CarouselTabRow(ui, onUiChange, history)
    }
}

/** The selected entry's stroke, above the hero card. Fixed height, so the strip never jumps. */
@Suppress("FunctionNaming")
@Composable
private fun SelectedPreview(entry: CarouselEntry?, content: CarouselContent) {
    Box(Modifier.fillMaxWidth().height(PreviewHeight), contentAlignment = Alignment.Center) {
        val bitmap = (entry?.action as? CarouselAction.ExtensionBrush)?.let { content.extensionPreviews[it.id] }
        when {
            entry?.brush != null -> Box(Modifier.widthIn(max = HeroItemWidth * 2).fillMaxWidth()) {
                BrushPreview(entry.brush, content.brushColor, content.secondaryColor, height = PreviewHeight)
            }
            bitmap != null ->
                Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.height(PreviewHeight))
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().height(CarouselHeight), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), CardShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * Undo · Favorites · Brushes · Ink · Effects · Options · Redo. Undo and Redo are actions, not pages:
 * they never change [CarouselUi.category]. Hiding is the sheet's job ([CarouselSheet]), not a
 * chevron's; while the sheet is shut, the rail carries Undo and Redo instead.
 */
@Suppress("FunctionNaming")
@Composable
private fun CarouselTabRow(ui: CarouselUi, onUiChange: (CarouselUi) -> Unit, history: CarouselHistory) {
    val colors = MaterialTheme.colorScheme
    val pages = CAROUSEL_TABS.filterIsInstance<CarouselTab.Page>()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CAROUSEL_TABS.forEach { tab ->
            when (tab) {
                CarouselTab.Undo, CarouselTab.Redo -> HistoryTab(tab, history)
                is CarouselTab.Page -> Unit
            }
            if (tab == CarouselTab.Undo) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f).height(TabRowHeight)) {
                    pages.forEachIndexed { index, page ->
                        SegmentedButton(
                            selected = page.category == ui.category,
                            onClick = { onUiChange(ui.copy(category = page.category)) },
                            shape = SegmentedButtonDefaults.itemShape(index, pages.size),
                            modifier = Modifier.weight(1f),
                            // Tight padding: all five pages plus Undo/Redo fit a phone's width.
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            icon = {},
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = colors.onSurface,
                                activeContentColor = colors.surface,
                                inactiveContainerColor = colors.surface.copy(alpha = 0.72f),
                                inactiveContentColor = colors.onSurface,
                                activeBorderColor = colors.outline,
                                inactiveBorderColor = colors.outline,
                            ),
                        ) {
                            Text(
                                page.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun HistoryTab(tab: CarouselTab, history: CarouselHistory) {
    val enabled = carouselTabEnabled(tab, history.undoCount, history.redoCount)
    val isUndo = tab == CarouselTab.Undo
    IconButton(
        onClick = if (isUndo) history.onUndo else history.onRedo,
        enabled = enabled,
        modifier = Modifier.size(TabRowHeight + 8.dp),
    ) {
        Icon(
            painterResource(if (isUndo) GraffuxIcons.Undo else GraffuxIcons.Redo),
            contentDescription = tab.label,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
        )
    }
}

private const val DISABLED_ALPHA = 0.38f

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarouselStrip(
    content: CarouselContent,
    onEntryClick: (CarouselEntry) -> Unit,
    onToggleFavorite: (CarouselEntry) -> Unit,
) {
    val entries = content.entries
    val selected = selectedCarouselIndex(entries)
    val spacingPx = with(LocalDensity.current) { ItemSpacing.toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth().height(CarouselHeight)) {
        val widthPx = constraints.maxWidth.toFloat()
        val sizes = carouselKeylineSizes(widthPx, spacingPx)
        // The hero slot follows the current selection, whichever surface changed it (this strip,
        // the rail, the shortcuts sheet or the Tool Options window).
        CenteredHeroRow(
            count = entries.size,
            centredIndex = selected,
            widthPx = widthPx,
            spacing = ItemSpacing,
            modifier = Modifier.fillMaxSize(),
        ) { index, sizePx ->
            // The tier comes from the size the keylines gave this item right now, never its index.
            val tier = carouselTier(sizePx, sizes.hero, sizes.smallCeiling)
            CarouselItem(
                entries[index],
                tier,
                content,
                onEntryClick,
                onToggleFavorite,
                Modifier.fillMaxSize().clip(CardShape).testTag("carousel.card.${tier.name}"),
            )
        }
    }
}

@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun CarouselItem(
    entry: CarouselEntry,
    tier: CarouselTier,
    content: CarouselContent,
    onEntryClick: (CarouselEntry) -> Unit,
    onToggleFavorite: (CarouselEntry) -> Unit,
    clip: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val border = if (entry.selected) Modifier else Modifier.border(BorderStroke(1.dp, colors.outline), CardShape)
    val card = carouselCardContent(entry, tier)
    Box(
        modifier = clip
            .background(if (entry.selected) colors.onSurface else colors.surfaceVariant)
            .then(border)
            .semantics {
                this.selected = entry.selected
                contentDescription = entry.label
                if (entry.favorite) stateDescription = "Favorite"
            }
            .clickable(role = Role.Tab) { onEntryClick(entry) },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (entry.selected) colors.surface else colors.onSurface
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            TipVisual(carouselTip(entry), content, tint, if (tier == CarouselTier.HERO) HeroTipSize else TipSize)
            card.name?.let {
                Text(
                    it,
                    color = tint,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            card.details.forEach {
                Text(
                    it,
                    color = tint.copy(alpha = DETAIL_ALPHA),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (tier != CarouselTier.SMALL) {
            FavoriteToggle(entry, tint, onToggleFavorite, Modifier.align(Alignment.TopEnd))
        }
    }
}
