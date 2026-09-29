package com.hereliesaz.graffux

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlin.math.roundToInt

/**
 * Which hero card, if any, is grown to its full adjustments. Owned by [BottomCarousel] per page.
 *
 * It collapses (see [rememberHeroExpansion]) on "Less" or a second "More", on Back, when the row
 * moves another card into the hero slot, when that entry leaves the page, and when a stroke starts.
 */
@Stable
internal class HeroExpansion {
    var expandedKey: String? by mutableStateOf(null)
        private set

    /** The last key that was expanded, so the card can finish shrinking after [collapse]. */
    var shownKey: String? by mutableStateOf(null)
        private set

    /** Opens [key], or closes it if it is already open. Returns whether it is now open. */
    fun toggle(key: String): Boolean {
        expandedKey = if (expandedKey == key) null else key
        if (expandedKey != null) shownKey = expandedKey
        return expandedKey != null
    }

    fun collapse() {
        expandedKey = null
        pendingKey = null
    }

    /**
     * A card to grow once it is in the hero slot (Tool Options). The carousel may first have to
     * switch page and centre it; [HeroExpansionEffects] opens it when the hero is [key].
     */
    var pendingKey: String? by mutableStateOf(null)
        private set

    fun request(key: String) {
        pendingKey = key
    }

    /** Opens [key] now (it is the hero): [request]'s second half. */
    internal fun openPending(key: String) {
        pendingKey = null
        expandedKey = key
        shownKey = key
    }

    /** What the carousel page last drew (its entries and callbacks), for the expanded layer to draw. */
    var content: CarouselContent? by mutableStateOf(null)
    var onToggleFavorite: (CarouselEntry) -> Unit by mutableStateOf({})

    /** Where the hero card rests, in window pixels: the card grows out from here. */
    var anchor: HeroAnchor? by mutableStateOf(null)

    /** The row's scroll position, for the preview's crossfade. */
    var position: State<Float>? by mutableStateOf(null)

    /** The grow animation's progress, 0 at rest, 1 grown: the card and its preview both follow it. */
    var progress: Float by mutableFloatStateOf(0f)
}

/** The hero card's bottom-centre, in window pixels. */
internal data class HeroAnchor(val centerX: Float, val bottom: Float, val rowWidth: Float)

/** Whether an expanded card must close: the hero slot now holds another entry, or none. */
internal fun heroExpansionStale(expandedKey: String?, heroKey: String?): Boolean =
    expandedKey != null && expandedKey != heroKey

@Suppress("FunctionNaming")
@Composable
internal fun HeroExpansionEffects(expansion: HeroExpansion, position: State<Float>, content: CarouselContent) {
    val entries by rememberUpdatedState(content.entries)
    val strokeActive by rememberUpdatedState(content.strokeActive)
    // Moving another card into the hero slot (or the entry leaving the page) closes it.
    LaunchedEffect(expansion) {
        snapshotFlow { entries.getOrNull(position.value.roundToInt())?.key to expansion.expandedKey }
            .collect { (hero, open) -> if (heroExpansionStale(open, hero)) expansion.collapse() }
    }
    // A requested card (Tool Options) opens once it has come to rest in the hero slot.
    LaunchedEffect(expansion) {
        snapshotFlow { entries.getOrNull(position.value.roundToInt())?.key to expansion.pendingKey }
            .collect { (hero, pending) -> if (pending != null && hero == pending) expansion.openPending(pending) }
    }
    // A stroke starting closes it; the sheet itself shuts at the same moment (CarouselSheet).
    LaunchedEffect(expansion) {
        snapshotFlow { strokeActive() }.collect { if (it) expansion.collapse() }
    }
    BackHandler(enabled = expansion.expandedKey != null) { expansion.collapse() }
}

/**
 * The hero card grown in place: it starts at the hero's own size, over the hero, and grows (M3
 * Expressive's default spatial spring) upward over the stroke preview and a little wider, never
 * down into the tabs. It shows everything [heroSections] lists for the item, editing that
 * item's own settings through [CarouselContent.onAdjust] exactly as the inline sliders do, and
 * scrolls if it overflows. "Less" (or "More" again, or anything in [HeroExpansion]) shrinks it.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ExpandedHeroLayer(
    expansion: HeroExpansion,
    collapsedSize: DpSize = HeroRestSize,
    expandedSize: DpSize = HeroExpandedSize,
) {
    // One animation drives the card's size and, through it, the preview's position: both are placed
    // in the same layout pass from the card's measured height, so they move together frame by frame.
    val progress = remember { Animatable(0f) }
    val open = expansion.expandedKey != null
    LaunchedEffect(open) {
        progress.animateTo(if (open) 1f else 0f, MotionScheme.expressive().defaultSpatialSpec()) {
            expansion.progress = value
        }
        expansion.progress = progress.value
    }
    // A full-window layer with no pointer input of its own: only the card takes touches.
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val anchor = expansion.anchor ?: return@Box
        val content = expansion.content ?: return@Box
        val position = expansion.position ?: return@Box
        val entry = content.entries.firstOrNull { it.key == expansion.shownKey }
        if (entry == null || (!open && progress.value <= 0f)) return@Box
        Layout(
            content = {
                // The same crossfading, per-item preview the carousel draws at rest, lifted onto
                // this page so it is never hidden behind the card or the neighbours.
                Box(Modifier.testTag("carousel.expanded.preview")) { HeroPreview(position, content) }
                ExpandedHeroCard(
                    expansion, entry, content, progress.value, expansion.onToggleFavorite, collapsedSize, expandedSize,
                )
            },
        ) { measurables, constraints ->
            val previewH = with(density) { HeroPreviewHeight.roundToPx() }
            val gap = with(density) { PreviewGap.toPx() }
            val preview = measurables[0].measure(Constraints.fixed(anchor.rowWidth.roundToInt(), previewH))
            val card = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val cardTop = anchor.bottom - origin.y - card.height
                card.place((anchor.centerX - origin.x - card.width / 2f).roundToInt(), cardTop.roundToInt())
                preview.place(
                    (anchor.centerX - origin.x - preview.width / 2f).roundToInt(),
                    (cardTop - gap - preview.height).roundToInt(),
                )
            }
        }
    }
}

@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
private fun ExpandedHeroCard(
    expansion: HeroExpansion,
    entry: CarouselEntry,
    content: CarouselContent,
    t: Float,
    onToggleFavorite: (CarouselEntry) -> Unit,
    collapsedSize: DpSize,
    expandedSize: DpSize,
) {
    val state = content.heroState ?: return
    val sections = heroSections(entry, state.withItem(content.itemSettings(entry)))
    val colors = MaterialTheme.colorScheme
    val bg = if (entry.selected) colors.onSurface else colors.surfaceVariant
    val tint = if (entry.selected) colors.surface else colors.onSurface
    Box(
        Modifier
            .size(lerp(collapsedSize.width, expandedSize.width, t), lerp(collapsedSize.height, expandedSize.height, t))
            .clip(CardShape)
            .background(bg)
            // Swallow taps between controls, so nothing reaches the strip underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .testTag("carousel.expanded"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("carousel.expanded.scroll"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = StarTouchSize)) {
                    TipVisual(carouselTip(entry), content, tint, ExpandedTipSize)
                    Text(
                        entry.label,
                        color = tint,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                FavoriteToggle(entry, tint, onToggleFavorite, Modifier.align(Alignment.TopEnd))
            }
            carouselCardContent(entry, CarouselTier.HERO).details.forEach {
                Text(
                    it,
                    color = tint.copy(alpha = DETAIL_ALPHA),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            HeroSectionsView(entry, sections, content, tint)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(
                    onClick = { expansion.collapse() },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.height(MORE_ROW_DP.dp).testTag("carousel.expanded.less"),
                ) {
                    Text("Less", color = tint, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private val ExpandedTipSize = 40.dp
