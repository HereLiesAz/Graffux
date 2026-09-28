package com.hereliesaz.graffux

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.feature.editor.BrushPreview

private val CarouselHeight = 72.dp
private val PreferredItemWidth = 112.dp
private val MaxCarouselWidth = 560.dp

/**
 * M3 Expressive multi-browse carousel of brushes, effect tools and tool options, pinned to the
 * bottom of the editor.
 *
 * Centre alignment: material3 1.5.0-alpha29's [HorizontalMultiBrowseCarousel] only lays out
 * start-aligned keylines (large items first, small items trailing) — the centre-aligned keyline
 * arrangement from the M3 Expressive spec (small · large · small) is exposed only as
 * `HorizontalCenteredHeroCarousel`, which is a hero layout (one large item) rather than multi-browse.
 * The carousel here approximates centre alignment the way the task allowed: symmetric content
 * padding of half the leftover width either side of one preferred item, so the item scrolled to
 * (the current selection) sits mid-strip with smaller items browsing off both edges.
 */
/** Which page is showing and whether the strip is open — host-owned, so it survives recomposition. */
internal data class CarouselUi(val category: CarouselCategory, val expanded: Boolean)

/** What the strip draws: its items plus what the brush previews need to render them. */
internal data class CarouselContent(
    val entries: List<CarouselEntry>,
    val brushColor: Color,
    val secondaryColor: Color,
    val extensionPreviews: Map<String, Bitmap>,
)

@Suppress("FunctionNaming") // Composable naming, as in SettingsScreen.kt.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BottomCarousel(
    ui: CarouselUi,
    onUiChange: (CarouselUi) -> Unit,
    content: CarouselContent,
    onEntryClick: (CarouselEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val category = ui.category
    val expanded = ui.expanded
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.widthIn(max = MaxCarouselWidth).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (expanded) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.height(36.dp)) {
                    CarouselCategory.entries.forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = value == category,
                            onClick = { onUiChange(ui.copy(category = value)) },
                            shape = SegmentedButtonDefaults.itemShape(index, CarouselCategory.entries.size),
                            icon = {},
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = colors.onSurface,
                                activeContentColor = colors.surface,
                                inactiveContainerColor = colors.surface.copy(alpha = 0.72f),
                                inactiveContentColor = colors.onSurface,
                                activeBorderColor = colors.outline,
                                inactiveBorderColor = colors.outline,
                            ),
                        ) { Text(value.label, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            IconButton(onClick = { onUiChange(ui.copy(expanded = !expanded)) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    painterResource(if (expanded) GraffuxIcons.ChevronDown else GraffuxIcons.ChevronUp),
                    contentDescription = if (expanded) "Hide carousel" else "Show carousel",
                    tint = colors.onSurface,
                )
            }
        }
        if (expanded && content.entries.isNotEmpty()) {
            CarouselStrip(content, onEntryClick)
        }
    }
}

@Suppress("FunctionNaming") // Composable naming, as in SettingsScreen.kt.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarouselStrip(content: CarouselContent, onEntryClick: (CarouselEntry) -> Unit) {
    val entries = content.entries
    val state = rememberCarouselState { entries.size }
    val selected = selectedCarouselIndex(entries)
    // The centred item follows the current selection, whichever surface changed it (this strip, the
    // rail, the shortcuts sheet or the Tool Options window).
    LaunchedEffect(selected, entries.size) {
        if (selected != null) state.animateScrollToItem(selected)
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(CarouselHeight)) {
        val sidePad = ((maxWidth - PreferredItemWidth) / 2).coerceAtLeast(0.dp)
        HorizontalMultiBrowseCarousel(
            state = state,
            preferredItemWidth = PreferredItemWidth,
            modifier = Modifier.fillMaxSize(),
            itemSpacing = 6.dp,
            flingBehavior = CarouselDefaults.singleAdvanceFlingBehavior(state),
            contentPadding = PaddingValues(horizontal = sidePad),
        ) { index ->
            val clip = Modifier.fillMaxSize().maskClip(RoundedCornerShape(20.dp))
            CarouselItem(entries[index], content, onEntryClick, clip)
        }
    }
}

@Suppress("FunctionNaming") // Composable naming, as in SettingsScreen.kt.
@Composable
private fun CarouselItem(
    entry: CarouselEntry,
    content: CarouselContent,
    onEntryClick: (CarouselEntry) -> Unit,
    clip: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val border = if (entry.selected) Modifier else Modifier.border(BorderStroke(1.dp, colors.outline), shape)
    Box(
        modifier = clip
            .background(if (entry.selected) colors.onSurface else colors.surfaceVariant)
            .then(border)
            .semantics {
                this.selected = entry.selected
                contentDescription = entry.label
            }
            .clickable(role = Role.Tab) { onEntryClick(entry) },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (entry.selected) colors.surface else colors.onSurface
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 6.dp),
        ) {
            val bitmap = (entry.action as? CarouselAction.ExtensionBrush)?.let { content.extensionPreviews[it.id] }
            when {
                entry.brush != null ->
                    BrushPreview(entry.brush, content.brushColor, content.secondaryColor, height = 32.dp)
                bitmap != null ->
                    Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.height(32.dp))
                entry.icon != null ->
                    Icon(
                        painterResource(entry.icon),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(28.dp),
                    )
            }
            Text(
                entry.label,
                color = tint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
