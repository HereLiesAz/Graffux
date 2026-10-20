package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Matches the carousel's own edge gap, so the chips line up with the tab row below. */
private val SetChipEdgeGap = 16.dp

/** One set chip: its name and the index of the first entry in it, which tapping the chip selects. */
internal data class CarouselSetChip(val name: String, val firstIndex: Int)

/**
 * The Brushes page's set chips, in strip order: one per run of entries sharing a [CarouselEntry.set].
 * Empty when there is nothing to choose between (fewer than two sets), so no row is drawn.
 */
internal fun carouselSetChips(entries: List<CarouselEntry>): List<CarouselSetChip> {
    val chips = mutableListOf<CarouselSetChip>()
    entries.forEachIndexed { index, entry ->
        val set = entry.set ?: return@forEachIndexed
        if (chips.none { it.name == set }) chips += CarouselSetChip(set, index)
    }
    return if (chips.size < 2) emptyList() else chips
}

/**
 * The Brushes page's set chips (Basics · Aerosol · Ink & Line · … · Studio · Installed; see
 * [carouselSetChips]). Tapping one selects that set's first brush, and the strip re-centres on it
 * the way it follows any other selection. The chip lit is the set of whatever is in the hero slot,
 * read from the strip's live position, so it tracks a swipe across a set boundary. Nothing is drawn
 * when there is only one set.
 */
@Suppress("FunctionNaming")
@Composable
internal fun SetChipRow(position: State<Float>, entries: List<CarouselEntry>, onEntryClick: (CarouselEntry) -> Unit) {
    val chips = remember(entries) { carouselSetChips(entries) }
    if (chips.isEmpty()) return
    val current by remember(entries) {
        derivedStateOf {
            entries.getOrNull(position.value.roundToInt().coerceIn(0, entries.lastIndex))?.set
        }
    }
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SetChipEdgeGap)
            .testTag("carousel.sets"),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        chips.forEach { chip ->
            val lit = chip.name == current
            Text(
                chip.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                color = if (lit) colors.surface else colors.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (lit) colors.onSurface else colors.surface.copy(alpha = 0.72f))
                    .clickable(role = Role.Tab) { entries.getOrNull(chip.firstIndex)?.let(onEntryClick) }
                    .semantics { selected = lit }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("carousel.set.${chip.name}"),
            )
        }
    }
}
