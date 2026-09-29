package com.hereliesaz.graffux

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.BoxWithConstraints
import com.hereliesaz.graffux.carousel.Carousel
import com.hereliesaz.graffux.carousel.CarouselDefaults
import com.hereliesaz.graffux.carousel.CarouselState
import com.hereliesaz.graffux.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.CarouselItemSettings
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.feature.editor.BrushPreview

/**
 * Card heights at the small, medium and hero keylines (see [carouselCardHeight]). The hero is about
 * 1.5x a medium card; the row is as tall as the hero, and every card is centred on its middle.
 */
private val SmallCardHeight = 104.dp
private val MediumCardHeight = 136.dp
private val HeroCardHeight = 200.dp
private val CarouselHeight = HeroCardHeight
private val HeroItemWidth = 168.dp
private val SmallItemMinWidth = 40.dp
private val SmallItemMaxWidth = 56.dp
/** M3's `CarouselDefaults.AnchorSize`: the off-screen keylines items shrink into. */
private val AnchorWidth = 10.dp
private val ItemSpacing = 6.dp
private val TipSize = 36.dp
private val HeroTipSize = 44.dp
internal val StarTouchSize = 32.dp
internal val StarSize = 18.dp
private val MaxCarouselWidth = 560.dp
private val PreviewHeight = 40.dp

private const val EXPANDED_WIDTH_FACTOR = 1.5f

/**
 * Clear space between the stroke preview and the top of the hero card — at rest, while the card
 * grows, and grown (ExpandedHeroLayer keeps the lifted preview exactly this far above the card).
 */
internal val PreviewGap = 48.dp

/** The carousel column's spacing between its children (see [BottomCarousel]). */
private val ColumnSpacing = 4.dp

/** The preview's height, for the expanded layer that lifts it. */
internal val HeroPreviewHeight = PreviewHeight

/** The hero card's size at rest: what the expanded card grows from. */
internal val HeroRestSize = androidx.compose.ui.unit.DpSize(HeroItemWidth, HeroCardHeight)

/** The expanded ("More") card: wider, over the neighbours, and up to where the preview's top rests. */
internal val HeroExpandedSize = androidx.compose.ui.unit.DpSize(
    HeroItemWidth * EXPANDED_WIDTH_FACTOR,
    HeroCardHeight + PreviewHeight + PreviewGap,
)
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
    /**
     * An entry's own settings (see CarouselItemSettingsPlan), or null for one that owns none. They
     * replace [heroState]'s Size/Flow/Opacity/Softness/Strength on that entry's card and preview.
     */
    val itemSettings: (CarouselEntry) -> CarouselItemSettings? = { null },
    /** A hero slider on [CarouselEntry] moved: its setter and new value. */
    val onAdjust: (CarouselEntry, HeroSetter, Float) -> Unit = { _, _, _ -> },
    /**
     * Whether the hero offers "More", and what to tell the host when it is pressed. Pressing it
     * grows the hero card in place to that item's full adjustments; this runs as the card opens.
     * Null hides the button.
     */
    val onMore: ((CarouselEntry) -> Unit)? = null,
    /** Read (not observed by the host) to close an expanded card when a stroke starts. */
    val strokeActive: () -> Boolean = { false },
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
 * crossfading as the row scrolls), M3's [HorizontalCenteredHeroCarousel] — one large item in the
 * centre between two small ones, snapping one item per fling (`singleAdvanceFlingBehavior`),
 * the selection snapped into the centre and whatever settles there selected (see
 * [carouselSettleSelects]) — and the tab row (see [CAROUSEL_TABS]).
 * The host places this across the full window width (an AzNavRail `background` page), so "centre"
 * means the centre of the screen, not of the strip beside the rail.
 *
 * Cards come in three tiers, read off the width M3's keylines give each item (see [carouselTier]).
 * At rest M3 lays out small · HERO · small; the medium tier shows only mid-scroll. The hero
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
    expansion: HeroExpansion = remember { HeroExpansion() },
) {
    Column(
        modifier = modifier.widthIn(max = MaxCarouselWidth).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ColumnSpacing),
    ) {
        if (content.entries.isNotEmpty()) {
            key(ui.category) {
                val count = content.entries.size
                val state = rememberCarouselState(initialItem = selectedCarouselIndex(content.entries) ?: 0) {
                    count
                }
                // The row's continuous scroll position, shared by the strip and the hero preview:
                // read off the widths the forked carousel gives its items (see carouselHeroPosition).
                val widths = remember { mutableStateMapOf<Int, Float>() }
                val smallMaxPx = with(LocalDensity.current) { SmallItemMaxWidth.toPx() }
                val position = remember(state, smallMaxPx) {
                    derivedStateOf { carouselStripPosition(widths, smallMaxPx, state.currentItem) }
                }
                // "More" grows the hero card in place (CarouselHeroExpanded.kt) instead of opening
                // the Tool Options window. Keyed by entry, so it belongs to one card.
                HeroExpansionEffects(expansion, position, content)
                val stripContent = content.copy(
                    onMore = content.onMore?.let { hostMore ->
                        { entry: CarouselEntry -> if (expansion.toggle(entry.key)) hostMore(entry) }
                    },
                )
                // The grown card is drawn by ExpandedHeroLayer on its own AzNavRail page, in front
                // of this one; publish what it draws and where the hero sits.
                SideEffect {
                    expansion.content = stripContent
                    expansion.position = position
                    expansion.onToggleFavorite = onToggleFavorite
                }
                // While the card is grown (or growing), ExpandedHeroLayer draws this same preview
                // riding above the card, so this one steps aside.
                HeroPreview(position, content) { expansion.progress > 0f }
                // The column spaces each side of this spacer too; together they make PreviewGap.
                Spacer(Modifier.height(PreviewGap - ColumnSpacing * 2))
                Box(
                    Modifier.onGloballyPositioned { row ->
                        val r = row.boundsInWindow()
                        // The hero is centred in the row, as tall as it, HeroItemWidth wide.
                        expansion.anchor = HeroAnchor(r.center.x, r.bottom, r.width)
                    },
                ) {
                    CarouselStrip(state, widths, position, stripContent, onEntryClick, onToggleFavorite)
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().height(PreviewHeight + PreviewGap))
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
internal fun HeroPreview(position: State<Float>, content: CarouselContent, hidden: () -> Boolean = { false }) {
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
                        .graphicsLayer { alpha = if (hidden()) 0f else carouselPreviewAlpha(position.value, index) },
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
    // Every brush previews at its own Size and Flow — the ones its hero sliders set — whether or
    // not it is the one in hand.
    val own = content.itemSettings(entry)
    when {
        entry.brush != null -> Box(tag.widthIn(max = HeroItemWidth * 2).fillMaxWidth()) {
            BrushPreview(
                entry.brush, content.brushColor, content.secondaryColor, height = PreviewHeight,
                flow = own?.flow ?: 1f,
                sizeOverridePx = own?.size,
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

@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarouselStrip(
    state: CarouselState,
    widths: SnapshotStateMap<Int, Float>,
    position: State<Float>,
    content: CarouselContent,
    onEntryClick: (CarouselEntry) -> Unit,
    onToggleFavorite: (CarouselEntry) -> Unit,
) {
    val entries = content.entries
    val selected = selectedCarouselIndex(entries)
    val scope = rememberCoroutineScope()
    val currentEntries by rememberUpdatedState(entries)
    val click by rememberUpdatedState(onEntryClick)
    val programmatic = remember { ProgrammaticScrolls() }
    // Whatever comes to rest in the hero slot becomes the selection (see carouselSettleSelects).
    fun settle(index: Int, byTap: Boolean) {
        val entry = currentEntries.getOrNull(index) ?: return
        if (carouselSettleSelects(entry, byTap)) click(entry)
    }
    // The hero slot follows the current selection, whichever surface changed it (this strip, the
    // rail, the shortcuts sheet or the Tool Options window). A settle selects the item it already
    // rests on, so this animates nowhere and nothing loops.
    LaunchedEffect(selected, entries.size) {
        if (selected != null && selected != state.currentItem) programmatic.centre(state, selected)
    }
    // One settle per user drag or fling, and a haptic tick per item crossed under the finger.
    CarouselGestureEffects(state, programmatic) { settle(it, byTap = false) }
    val smallMaxPx = with(LocalDensity.current) { SmallItemMaxWidth.toPx() }
    // The row is as tall as the hero card; every card is centred on the row's middle, its height
    // interpolated from its laid-out width (carouselCardHeight), so small < medium < hero.
    val density = LocalDensity.current
    val keylines = rememberCentredKeylines(SmallItemMinWidth, SmallItemMaxWidth, AnchorWidth)
    val cardHeights = CarouselCardHeights(SmallCardHeight.value, MediumCardHeight.value, HeroCardHeight.value)
    BoxWithConstraints(Modifier.fillMaxWidth().height(CarouselHeight)) {
        // Index 1, 2, 3 of anchor · small · medium · HERO · … are the small, medium and hero
        // keylines. The medium width also floors the hero position's weights (carouselStripPosition).
        val lines = keylines(constraints.maxWidth.toFloat(), with(density) { ItemSpacing.toPx() })
        val small = lines.getOrNull(1)?.size
        val medium = lines.getOrNull(2)?.size
        SideEffect { if (medium != null) widths[MEDIUM_WIDTH_KEY] = medium else widths.remove(MEDIUM_WIDTH_KEY) }
    // The fork of M3's Carousel with custom, centred keylines: small · medium · HERO · medium ·
    // small, the focal range pinned so the first and last items rest centred too.
    Carousel(
        state = state,
        orientation = Orientation.Horizontal,
        keylineList = keylines,
        contentPadding = PaddingValues(0.dp),
        // One medium and one small item on each side of the hero.
        maxNonFocalVisibleItemCount = 2,
        modifier = Modifier.fillMaxSize().testTag("carousel.row"),
        itemSpacing = ItemSpacing,
        flingBehavior = CarouselDefaults.singleAdvanceFlingBehavior(state),
        pinFocalRange = true,
    ) { index ->
        // The tier is read off the width M3's keylines gave this item right now, never its index.
        val info = carouselItemDrawInfo
        val tier = carouselTier(info.size, info.maxSize, smallMaxPx)
        val cardHeight = if (small != null && medium != null) {
            carouselCardHeight(info.size, small, medium, info.maxSize, cardHeights).dp
        } else {
            MediumCardHeight
        }
        SideEffect {
            widths[index] = info.size
            // The hero position weighs each width against the hero keyline's, so it needs that too.
            widths[HERO_WIDTH_KEY] = info.maxSize
        }
        DisposableEffect(index) { onDispose { widths.remove(index) } }
        val entry = entries[index]
        // Every item is laid out at the hero width and masked down about its centre: keep the
        // name, tip and star inside the visible part (a medium card's star would sit off-mask).
        val maskInset = with(density) { info.maskRect.left.coerceAtLeast(0f).toDp() }
        CarouselItem(
            entry,
            tier,
            cardHeight,
            maskInset,
            content,
            { tapped ->
                // The hero runs its action (a second tap on a tool puts it down); any other card
                // scrolls into the hero slot first, and the settle then selects it.
                if (position.value == index.toFloat()) {
                    click(tapped)
                } else {
                    scope.launch {
                        programmatic.centre(state, index)
                        settle(index, byTap = true)
                    }
                }
            },
            onToggleFavorite,
            Modifier
                .maskClip(CardShape)
                .then(
                    if (entry.selected) {
                        Modifier
                    } else {
                        Modifier.maskBorder(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CardShape)
                    },
                )
                .testTag("carousel.card.${tier.name}"),
        )
    }
    }
}

/**
 * Scrolls the strip starts itself (re-centring on an outside selection, tap-to-centre). Their end
 * is not a user settle, and crossing items during them does not tick.
 */
internal class ProgrammaticScrolls {
    var count = 0
        private set

    /** Animates [state] to [index] with M3's own item animation, marked as the strip's own scroll. */
    suspend fun centre(state: CarouselState, index: Int) {
        count++
        try {
            state.animateScrollToItem(index)
        } finally {
            count--
        }
    }
}

@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun CarouselItem(
    entry: CarouselEntry,
    tier: CarouselTier,
    height: androidx.compose.ui.unit.Dp,
    maskInset: androidx.compose.ui.unit.Dp,
    content: CarouselContent,
    onEntryClick: (CarouselEntry) -> Unit,
    onToggleFavorite: (CarouselEntry) -> Unit,
    clip: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isHero = tier == CarouselTier.HERO
    // Content taller than the card scrolls vertically inside it (verticalScroll only moves when it
    // overflows). Horizontal drags still reach the row; verticalScroll takes part in nested scroll,
    // so whatever the card does not consume goes on to the sheet (CarouselSheet).
    val scroll = rememberScrollState()
    // An overflowing card starts at its foot, where the hero's controls are.
    var pinnedToFoot by remember(entry.key, tier) { mutableStateOf(false) }
    LaunchedEffect(entry.key, tier, scroll.maxValue) {
        if (!pinnedToFoot && scroll.maxValue > 0) {
            scroll.scrollTo(scroll.maxValue)
            pinnedToFoot = true
        }
    }
    // Every card is centred on the row's middle line, so all tiers share one vertical centre.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(height)) {
            BoxWithConstraints(
                modifier = clip
                    .fillMaxSize()
                    .background(if (entry.selected) colors.onSurface else colors.surfaceVariant)
                    .semantics {
                        this.selected = entry.selected
                        contentDescription = entry.label
                        if (entry.favorite) stateDescription = "Favorite"
                    },
            ) {
                val cardHeight = maxHeight
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .testTag("carousel.card.scroll"),
                ) {
                    val tint = if (entry.selected) colors.surface else colors.onSurface
                    val adjustments = content.heroState
                        ?.let { heroAdjustments(entry, it.withItem(content.itemSettings(entry))) }
                        .orEmpty()
                    val onMore = content.onMore?.takeIf { heroHasMore(entry) }
                    val hasControls = adjustments.isNotEmpty() || onMore != null
                    if (isHero && content.heroState != null && hasControls) {
                        CompactHero(
                            entry, adjustments, onMore, content, tint, maskInset, cardHeight, onEntryClick,
                            onToggleFavorite,
                        )
                    } else {
                        // On a side card the whole visible card is the click target.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = cardHeight)
                                .clickable(role = Role.Tab) { onEntryClick(entry) }
                                .padding(horizontal = maskInset),
                            contentAlignment = Alignment.Center,
                        ) {
                            CardIdentity(entry, tier, content, tint, onToggleFavorite)
                        }
                    }
                }
            }
            ActiveHighlight(carouselHeroHighlighted(entry, tier))
        }
    }
}

/**
 * The hero with controls, kept short: the tip beside the name (the star in the corner) and the
 * details on top, the compact sliders and "More" at the foot. At least [minHeight] tall, so the
 * controls sit on the card's bottom edge; taller content scrolls (see [CarouselItem]). The click
 * target is the tip, name and details, never the sliders or More.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun CompactHero(
    entry: CarouselEntry,
    adjustments: List<HeroAdjustment>,
    onMore: ((CarouselEntry) -> Unit)?,
    content: CarouselContent,
    tint: Color,
    maskInset: androidx.compose.ui.unit.Dp,
    minHeight: androidx.compose.ui.unit.Dp,
    onEntryClick: (CarouselEntry) -> Unit,
    onToggleFavorite: (CarouselEntry) -> Unit,
) {
    val details = carouselCardContent(entry, CarouselTier.HERO).details
    Column(
        Modifier.fillMaxWidth().heightIn(min = minHeight),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Tab) { onEntryClick(entry) }
                .padding(horizontal = maskInset)
                .padding(top = 6.dp)
                .testTag("carousel.hero.identity"),
        ) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = StarTouchSize - 8.dp),
                ) {
                    TipVisual(carouselTip(entry), content, tint, TipSize)
                    Text(
                        entry.label,
                        color = tint,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                details.forEach {
                    Text(
                        it,
                        color = tint.copy(alpha = DETAIL_ALPHA),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FavoriteToggle(entry, tint, onToggleFavorite, Modifier.align(Alignment.TopEnd))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = maskInset).padding(bottom = 2.dp)) {
            if (adjustments.isNotEmpty()) HeroControls(entry, adjustments, content, tint)
            if (onMore != null) {
                Box(Modifier.fillMaxWidth().padding(end = 2.dp), contentAlignment = Alignment.CenterEnd) {
                    HeroMoreButton(entry, onMore, tint)
                }
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
