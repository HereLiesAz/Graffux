package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.Tool

/**
 * What the sheet is offering: brushes while you paint, or the tool group the rail asked for (the
 * Vector group with the effect packs and tool options after it). Arming the brush or eraser puts it
 * on brushes, and with the brush in hand the tab list holds nothing that isn't a brush.
 */
internal enum class CarouselContext { BRUSHES, EFFECTS, TOOL_GROUP }

internal fun carouselContextOf(category: CarouselCategory): CarouselContext = when {
    category.isToolGroup -> CarouselContext.TOOL_GROUP
    category == CarouselCategory.EFFECTS || category == CarouselCategory.OPTIONS -> CarouselContext.EFFECTS
    else -> CarouselContext.BRUSHES
}

/** Tools whose strokes lay paint with the brush: arming one puts the sheet on its brushes. */
internal val BRUSH_CONTEXT_TOOLS: Set<Tool> = setOf(Tool.BRUSH, Tool.ERASER)

/**
 * One tab in the tab list: a pack or grouping of carousel entries. Tapping it opens the carousel
 * on [entries]. [category] is the carousel page the entries come from, so the sheet keeps working
 * in terms of pages for everything that already does (favourites, auto-select rules, hero cards).
 */
internal data class CarouselTabGroup(
    val key: String,
    val label: String,
    val category: CarouselCategory,
    val entries: List<CarouselEntry>,
)

/** A type heading (Favorites, Basic, Installed …) and the tabs that sit after it in the list. */
internal data class CarouselTabSection(val type: String, val groups: List<CarouselTabGroup>)

/**
 * The tab list for [context]: type headings with their packs between them. An empty pack is left
 * out, and a type with no packs left is left out with it, so the list never offers a tab that opens
 * an empty carousel. An entry may sit in more than one tab (a starred brush is in its pack too).
 *
 * [packNames] maps an installed extension id to its display name; installed brushes are grouped by
 * the extension that installed them, one tab per pack.
 */
internal fun carouselTabSections(
    context: CarouselContext,
    category: CarouselCategory,
    input: CarouselInputs,
    packNames: Map<String, String> = emptyMap(),
): List<CarouselTabSection> = when (context) {
    CarouselContext.BRUSHES -> brushSections(input, packNames)
    CarouselContext.EFFECTS -> effectSections(input)
    // The Vector group is where the effect tools live on the rail, so its list carries the effect
    // packs (installed filters and LUTs) and tool options after the group's own tab.
    CarouselContext.TOOL_GROUP -> listOf(
        CarouselTabSection(
            category.label.uppercase(),
            listOf(
                CarouselTabGroup("group.${category.name}", category.label, category, carouselEntries(category, input)),
            ),
        ),
    ) + if (category == CarouselCategory.VECTOR) effectSections(input) else emptyList()
}.map { section -> section.copy(groups = section.groups.filter { it.entries.isNotEmpty() }) }
    .filter { it.groups.isNotEmpty() }

private fun brushSections(input: CarouselInputs, packNames: Map<String, String>): List<CarouselTabSection> {
    val brushes = carouselEntries(CarouselCategory.BRUSHES, input)
    val ink = carouselEntries(CarouselCategory.INK, input)
    val brushKeys = (brushes + ink).map { it.key }.toSet()
    val starred = carouselEntries(CarouselCategory.FAVORITES, input).filter { it.key in brushKeys }
    val installed = brushes.filter { it.set == INSTALLED_SET }
        .groupBy { it.key.removePrefix("ext.").substringBefore(EXTENSION_ASSET_SEPARATOR) }
    return listOf(
        CarouselTabSection(
            "FAVORITES",
            listOf(CarouselTabGroup("fav.starred", "Starred", CarouselCategory.FAVORITES, starred)),
        ),
        CarouselTabSection(
            "BASIC",
            BuiltInBrushes.sets.map { set ->
                CarouselTabGroup(
                    "basic.${set.name}", set.name, CarouselCategory.BRUSHES,
                    brushes.filter { it.set == set.name },
                )
            },
        ),
        CarouselTabSection(
            "INK",
            listOf(CarouselTabGroup("ink", "Ink", CarouselCategory.INK, ink)),
        ),
        CarouselTabSection(
            "STUDIO",
            listOf(
                CarouselTabGroup(
                    "studio", "My Brushes", CarouselCategory.BRUSHES, brushes.filter { it.set == STUDIO_SET },
                ),
            ),
        ),
        CarouselTabSection(
            "INSTALLED",
            installed.map { (extensionId, entries) ->
                CarouselTabGroup(
                    "pack.$extensionId", packNames[extensionId] ?: extensionId, CarouselCategory.BRUSHES, entries,
                )
            }.sortedBy { it.label.lowercase() },
        ),
    )
}

private fun effectSections(input: CarouselInputs): List<CarouselTabSection> {
    val effects = carouselEntries(CarouselCategory.EFFECTS, input)
    return listOf(
        CarouselTabSection(
            "EFFECTS",
            listOf(
                CarouselTabGroup(
                    "fx.tools", "Tools", CarouselCategory.EFFECTS,
                    effects.filter { it.action is CarouselAction.PickTool },
                ),
            ),
        ),
        CarouselTabSection(
            "INSTALLED",
            listOf(
                CarouselTabGroup(
                    "fx.filters", "Filters", CarouselCategory.EFFECTS,
                    effects.filter { it.action is CarouselAction.ExtensionContribution },
                ),
                CarouselTabGroup(
                    "fx.luts", "LUTs", CarouselCategory.EFFECTS,
                    effects.filter { it.action is CarouselAction.ExtensionLut },
                ),
            ),
        ),
        CarouselTabSection(
            "OPTIONS",
            listOf(
                CarouselTabGroup(
                    "options", "Options", CarouselCategory.OPTIONS, carouselEntries(CarouselCategory.OPTIONS, input),
                ),
            ),
        ),
    )
}

/** Joins an installed brush's extension id and asset index (`EditorViewModel.installedBrushes`). */
private const val EXTENSION_ASSET_SEPARATOR = "::"

/**
 * The tab to show: [requested] when it is still in the list, else the tab holding the current
 * selection, else the first. Null only when the list is empty.
 */
internal fun selectedTabGroup(sections: List<CarouselTabSection>, requested: String?): CarouselTabGroup? {
    val groups = sections.flatMap { it.groups }
    return groups.firstOrNull { it.key == requested }
        ?: groups.firstOrNull { group ->
            group.category != CarouselCategory.FAVORITES && group.entries.any { it.selected }
        }
        ?: groups.firstOrNull()
}

/** One slot in the scrolling list: a type heading, or a tab. */
internal sealed interface CarouselTabListItem {
    data class Heading(val type: String) : CarouselTabListItem
    data class Tab(val group: CarouselTabGroup) : CarouselTabListItem
}

internal fun carouselTabListItems(sections: List<CarouselTabSection>): List<CarouselTabListItem> =
    sections.flatMap { section ->
        listOf(CarouselTabListItem.Heading(section.type)) + section.groups.map { CarouselTabListItem.Tab(it) }
    }

/** Repeats the list this many times each way, which no finger will ever scroll past. */
private const val LOOP_SPAN = 10_000

private val TabHeight = 40.dp
private val TabListHeight = 48.dp

/**
 * The tab list: type headings with their pack tabs between them, scrolling without end in either
 * direction (the list repeats). The selected tab is centred when the list appears or the selection
 * changes. Tapping a tab calls [onSelect]; headings are labels, not targets.
 */
@Suppress("FunctionNaming")
@Composable
internal fun CarouselTabList(
    sections: List<CarouselTabSection>,
    selectedKey: String?,
    onSelect: (CarouselTabGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(sections) { carouselTabListItems(sections) }
    if (items.isEmpty()) return
    val n = items.size
    val selectedIndex = items.indexOfFirst { it is CarouselTabListItem.Tab && it.group.key == selectedKey }
        .coerceAtLeast(0)
    val base = LOOP_SPAN / 2 * n
    val state = rememberLazyListState(initialFirstVisibleItemIndex = base + selectedIndex)
    LaunchedEffect(selectedKey, n) {
        // Nearest copy of the selected tab to where the list is now, then centred on it.
        val first = state.firstVisibleItemIndex
        val target = first - Math.floorMod(first, n) + selectedIndex
        state.scrollToItem(target)
        // Nothing picked yet (the photo sheet's first visit): the list starts at its first heading,
        // as the mockup's entry board draws it, rather than centring the heading.
        if (items.none { it is CarouselTabListItem.Tab && it.group.key == selectedKey }) return@LaunchedEffect
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target } ?: return@LaunchedEffect
        val viewportCentre = (info.viewportStartOffset + info.viewportEndOffset) / 2
        state.scrollBy((item.offset + item.size / 2 - viewportCentre).toFloat())
    }
    val colors = MaterialTheme.colorScheme
    LazyRow(
        state = state,
        modifier = modifier.fillMaxWidth().height(TabListHeight).testTag("carousel.tabs"),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        items(count = n * LOOP_SPAN) { index ->
            when (val item = items[index % n]) {
                is CarouselTabListItem.Heading -> Text(
                    item.type,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 1.sp),
                    color = colors.onSurface.copy(alpha = HEADING_ALPHA),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 14.dp, end = 6.dp),
                )
                is CarouselTabListItem.Tab -> TabChip(item.group, item.group.key == selectedKey, onSelect)
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun TabChip(group: CarouselTabGroup, lit: Boolean, onSelect: (CarouselTabGroup) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .height(TabHeight)
            .clip(RoundedCornerShape(TabHeight / 2))
            .background(if (lit) colors.onSurface else colors.surface.copy(alpha = 0.72f))
            .border(1.dp, colors.outline, RoundedCornerShape(TabHeight / 2))
            .clickable(role = Role.Tab) { onSelect(group) }
            .semantics { selected = lit }
            .padding(horizontal = 14.dp)
            .testTag("carousel.tab.${group.key}"),
    ) {
        Text(
            group.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (lit) colors.surface else colors.onSurface,
            maxLines = 1,
        )
    }
}

private const val HEADING_ALPHA = 0.6f
