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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
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
    /** Installed extensions' manifest `preview.image` thumbnails, by extension id. */
    val extensionIcons: Map<String, Bitmap> = emptyMap(),
    /** What the hero card's inline sliders read; null shows no sliders (see [heroAdjustments]). */
    val heroState: HeroAdjustmentState? = null,
    /** Routes a hero slider's new value to its existing editor setter. */
    val onAdjust: (HeroSetter, Float) -> Unit = { _, _ -> },
    /** The hero's "More": that item's full adjustments. Null hides the button. */
    val onMore: ((CarouselEntry) -> Unit)? = null,
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
 * Top to bottom: the hero entry's stroke preview (drawn *above* the hero card, not inside it, and
 * crossfading as the row scrolls), the [CenteredHeroRow] — one large item in the exact centre,
 * smaller items browsing off either side, the selection snapped into the centre and whatever
 * settles there selected (see [carouselSettleSelects]) — and the tab row (see [CAROUSEL_TABS]).
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
        if (content.entries.isNotEmpty()) {
            key(ui.category) {
                // The row's continuous scroll position, shared by the strip and the hero preview.
                val position = remember {
                    Animatable((selectedCarouselIndex(content.entries) ?: 0).toFloat())
                }
                HeroPreview(position, content)
                CarouselStrip(position, content, onEntryClick, onToggleFavorite)
            }
        } else {
            Box(Modifier.fillMaxWidth().height(PreviewHeight))
            if (ui.category == CarouselCategory.FAVORITES) {
                EmptyHint("No favorites yet. Tap the star on any brush, Ink, effect or option card to add it.")
            }
        }
        CarouselTabRow(ui, onUiChange, history)
    }
}

/**
 * The stroke of whichever entry is in the hero slot right now, above the hero card, crossfading
 * with its neighbour as the row scrolls ([carouselHeroBlend]). Which entries are composed changes
 * only when the row crosses an item ([derivedStateOf]); their opacity is read in the draw phase
 * ([graphicsLayer]), so scrolling does not recompose this every frame. Fixed height, so the strip
 * never jumps. Entries with no stroke (effects, options) draw nothing, so the stroke fades out.
 */
@Suppress("FunctionNaming")
@Composable
private fun HeroPreview(position: Animatable<Float, AnimationVector1D>, content: CarouselContent) {
    val count = content.entries.size
    val shown by remember(count) {
        derivedStateOf {
            carouselHeroBlend(position.value, count)?.let { listOfNotNull(it.heroIndex, it.neighborIndex) }.orEmpty()
        }
    }
    Box(Modifier.fillMaxWidth().height(PreviewHeight), contentAlignment = Alignment.Center) {
        shown.forEach { index ->
            val entry = content.entries[index]
            key(entry.key) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = carouselPreviewAlpha(position.value, index) },
                    contentAlignment = Alignment.Center,
                ) {
                    StrokePreview(entry, content)
                }
            }
        }
    }
}

/** One entry's stroke preview, or nothing for entries without one. */
@Suppress("FunctionNaming")
@Composable
private fun StrokePreview(entry: CarouselEntry, content: CarouselContent) {
    val bitmap = (entry.action as? CarouselAction.ExtensionBrush)?.let { content.extensionPreviews[it.id] }
    val tag = Modifier.testTag("carousel.preview.${entry.key}")
    // The brush in hand previews at the live Size and Flow the hero sliders are setting.
    val live = content.heroState?.takeIf { entry.selected }
    when {
        entry.brush != null -> Box(tag.widthIn(max = HeroItemWidth * 2).fillMaxWidth()) {
            BrushPreview(
                entry.brush, content.brushColor, content.secondaryColor, height = PreviewHeight,
                flow = live?.brushFlow ?: 1f,
                sizeOverridePx = live?.brushSize,
            )
        }
        bitmap != null ->
            Image(bitmap.asImageBitmap(), contentDescription = null, modifier = tag.height(PreviewHeight))
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
    position: Animatable<Float, AnimationVector1D>,
    content: CarouselContent,
    onEntryClick: (CarouselEntry) -> Unit,
    onToggleFavorite: (CarouselEntry) -> Unit,
) {
    val entries = content.entries
    val selected = selectedCarouselIndex(entries)
    val spacingPx = with(LocalDensity.current) { ItemSpacing.toPx() }
    val scope = rememberCoroutineScope()
    val currentEntries by rememberUpdatedState(entries)
    val click by rememberUpdatedState(onEntryClick)
    // Whatever comes to rest in the hero slot becomes the selection (see carouselSettleSelects).
    fun settle(index: Int, byTap: Boolean) {
        val entry = currentEntries.getOrNull(index) ?: return
        if (carouselSettleSelects(entry, byTap)) click(entry)
    }
    // The hero card grows upward (never wider) to fit its sliders, so the row is as tall as the
    // hero needs; every other card stays CarouselHeight, bottom-aligned.
    val heroIndex by remember { derivedStateOf { kotlin.math.round(position.value).toInt() } }
    val heroExtra = currentEntries.getOrNull(heroIndex)?.let { heroControlsHeight(it, content) } ?: 0.dp
    val rowHeight by animateDpAsState(CarouselHeight + heroExtra, label = "heroHeight")
    BoxWithConstraints(Modifier.fillMaxWidth().height(rowHeight)) {
        val widthPx = constraints.maxWidth.toFloat()
        val sizes = carouselKeylineSizes(widthPx, spacingPx)
        // The hero slot follows the current selection, whichever surface changed it (this strip,
        // the rail, the shortcuts sheet or the Tool Options window).
        CenteredHeroRow(
            count = entries.size,
            position = position,
            centredIndex = selected,
            widthPx = widthPx,
            spacing = ItemSpacing,
            onSettle = { settle(it, byTap = false) },
            modifier = Modifier.fillMaxSize().testTag("carousel.row"),
        ) { index, sizePx ->
            // The tier comes from the size the keylines gave this item right now, never its index.
            val tier = carouselTier(sizePx, sizes.hero, sizes.smallCeiling)
            CarouselItem(
                entries[index],
                tier,
                content,
                { entry ->
                    // The hero runs its action (a second tap on a tool puts it down); any other
                    // card scrolls into the hero slot first, and the settle then selects it.
                    if (position.value == index.toFloat()) {
                        click(entry)
                    } else {
                        scope.launch {
                            position.animateTo(index.toFloat())
                            settle(index, byTap = true)
                        }
                    }
                },
                onToggleFavorite,
                Modifier.clip(CardShape).testTag("carousel.card.${tier.name}"),
            )
        }
    }
}

private val SliderRowHeight = SLIDER_ROW_DP.dp
private val MoreRowHeight = MORE_ROW_DP.dp

/** The extra height the hero card takes for [entry]'s sliders and "More" button. */
private fun heroControlsHeight(entry: CarouselEntry, content: CarouselContent): androidx.compose.ui.unit.Dp {
    val state = content.heroState ?: return 0.dp
    val sliders = heroAdjustments(entry, state).size
    val more = content.onMore != null && heroHasMore(entry)
    return SliderRowHeight * sliders + if (more) MoreRowHeight else 0.dp
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
    val isHero = tier == CarouselTier.HERO
    // Only the hero may be taller than the row's base height; the rest sit on its bottom edge.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = clip
                .then(if (isHero) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(CarouselHeight))
                .background(if (entry.selected) colors.onSurface else colors.surfaceVariant)
                .then(border)
                .semantics {
                    this.selected = entry.selected
                    contentDescription = entry.label
                    if (entry.favorite) stateDescription = "Favorite"
                },
        ) {
            val tint = if (entry.selected) colors.surface else colors.onSurface
            val state = content.heroState
            if (isHero && state != null) {
                HeroControls(entry, heroAdjustments(entry, state), content, tint)
            }
            // The click target is the tip-and-name area, not the sliders above it.
            Box(
                Modifier.fillMaxWidth().weight(1f).clickable(role = Role.Tab) { onEntryClick(entry) },
                contentAlignment = Alignment.Center,
            ) {
                CardIdentity(entry, tier, content, tint, onToggleFavorite)
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CardIdentity(
    entry: CarouselEntry,
    tier: CarouselTier,
    content: CarouselContent,
    tint: Color,
    onToggleFavorite: (CarouselEntry) -> Unit,
) {
    val card = carouselCardContent(entry, tier)
    run {
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
