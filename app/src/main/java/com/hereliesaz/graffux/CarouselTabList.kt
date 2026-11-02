package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
}.map { section ->
    // Favorites stays even when empty: its quick button always has somewhere to go, and the empty
    // set says how to star something.
    section.copy(groups = section.groups.filter { it.entries.isNotEmpty() || it.key == FAVORITES_SET_KEY })
}
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
            listOf(CarouselTabGroup(FAVORITES_SET_KEY, "Favorites", CarouselCategory.FAVORITES, starred)),
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
 * selection, else the first with anything in it. Null only when the list is empty.
 */
internal fun selectedTabGroup(sections: List<CarouselTabSection>, requested: String?): CarouselTabGroup? {
    val groups = sections.flatMap { it.groups }
    return groups.firstOrNull { it.key == requested }
        ?: groups.firstOrNull { group ->
            group.category != CarouselCategory.FAVORITES && group.entries.any { it.selected }
        }
        ?: groups.firstOrNull { it.entries.isNotEmpty() }
        ?: groups.firstOrNull()
}

/** The one Favorites set (starred entries): the target of the picker's Favorites button. */
internal const val FAVORITES_SET_KEY = "fav.starred"

/**
 * Every set the picker offers, once each, in list order: a type with a single set (Ink, My Brushes)
 * is that one set, and a type with several (Basic's sets, installed packs) offers each. The type
 * heading is not a set of its own, so nothing is listed twice.
 */
internal fun carouselSets(sections: List<CarouselTabSection>): List<CarouselTabGroup> =
    sections.flatMap { it.groups }.distinctBy { it.key }

/**
 * The roller's label for each of [sets], by key, unique: a label two sets share is told apart by
 * its type ("Gravity · INSTALLED"), and any clash left after that by a count.
 */
internal fun carouselSetLabels(sections: List<CarouselTabSection>): Map<String, String> {
    val typed = sections.flatMap { section -> section.groups.map { it to section.type } }.distinctBy { it.first.key }
    val counts = typed.groupingBy { it.first.label }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return typed.associate { (group, type) ->
        val base = if ((counts[group.label] ?: 0) > 1) "${group.label} · $type" else group.label
        val n = seen.merge(base, 1, Int::plus) ?: 1
        group.key to if (n > 1) "$base ($n)" else base
    }
}

/**
 * The set to go back to after picking [picked]: the one showing until now ([current]), or, when the
 * same set is picked again, the remembered [previous]. Favorites has its own quick button, so it is
 * never the set to go back to: leaving it keeps the [previous] set.
 */
internal fun previousSetAfter(picked: String, current: String?, previous: String?): String? =
    if (current != null && current != picked && current != FAVORITES_SET_KEY) current else previous

private val QuickButtonHeight = 40.dp
/**
 * The set picker: a quarter-circle wheel of the sets ([CarouselSetWheel]) in the sheet's bottom corner
 * away from the rail ([LocalSetWheelOnRight], from the handedness setting), with two quick buttons stacked on
 * the rail side — Favorites, and the set picked before this one, named, for switching back and
 * forth. Each set is its own list, so nothing scrolls on from one set into the next. [onSelect] is
 * called with the committed set (after the wheel's dwell, see [WHEEL_DWELL_MS]). AzRoller's
 * type-to-filter search went with it: the wheel has no field.
 *
 * [previousKey] is the set to offer for going back, when the caller keeps it (the brush sheet saves
 * it in [CarouselUi]); left null, the strip remembers the last switch itself.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun CarouselTabList(
    sections: List<CarouselTabSection>,
    selectedKey: String?,
    onSelect: (CarouselTabGroup) -> Unit,
    modifier: Modifier = Modifier,
    previousKey: String? = null,
) {
    val sets = remember(sections) { carouselSets(sections) }
    if (sets.isEmpty()) return
    val labels = remember(sections) { carouselSetLabels(sections) }
    var ownPrevious by rememberSaveable { mutableStateOf<String?>(null) }
    val previous = (previousKey ?: ownPrevious)?.let { key -> sets.firstOrNull { it.key == key } }
        ?.takeIf { it.key != selectedKey && it.key != FAVORITES_SET_KEY }
    val favorites = sets.firstOrNull { it.key == FAVORITES_SET_KEY }
    val pick: (CarouselTabGroup) -> Unit = { group ->
        ownPrevious = previousSetAfter(group.key, selectedKey, ownPrevious)
        onSelect(group)
    }
    val onRight = LocalSetWheelOnRight.current
    // The strip takes exactly the height the picker had before the wheel (StripHeight), so the cards
    // above sit where they always did. The wheel and the quick buttons are drawn over it, anchored
    // in its bottom corner and reaching down into the sheet's gesture-free bottom inset (above the
    // nav bar's), never up into the cards.
    BoxWithConstraints(
        modifier.fillMaxWidth().height(StripHeight).padding(horizontal = PickerGap).testTag("carousel.tabs"),
    ) {
        val radius = setWheelRadius(maxWidth - QuickColumnWidth - PickerGap, StripHeight + PickerOverhang)
        val quick: @Composable () -> Unit = {
            if (favorites != null) {
                Box(Modifier.width(QuickColumnWidth)) {
                    QuickSetButton("Favorites", favorites.key == selectedKey, "carousel.quick.favorites") {
                        pick(favorites)
                    }
                }
            }
            if (previous != null) {
                Box(Modifier.width(QuickColumnWidth)) {
                    QuickSetButton(labels.getValue(previous.key), false, "carousel.quick.previous") { pick(previous) }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .requiredHeight(radius),
            verticalAlignment = Alignment.Bottom,
            // The quick buttons sit right beside the wheel, on its rail side.
            horizontalArrangement = Arrangement.spacedBy(PickerGap, if (onRight) Alignment.End else Alignment.Start),
        ) {
            if (onRight) quick()
            CarouselSetWheel(sets, labels, selectedKey, radius, pick)
            if (!onRight) quick()
        }
    }
}

/** The picker strip's layout height: what it was before the wheel, so the cards never move. */
internal val StripHeight = 56.dp

/** How far the wheel reaches below the strip: into the sheet's bottom inset ([CarouselBottomInset]). */
internal val PickerOverhang = CarouselBottomInset

@Suppress("FunctionNaming")
@Composable
private fun QuickSetButton(label: String, lit: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(QuickButtonHeight)
            .clip(RoundedCornerShape(QuickButtonHeight / 2))
            .background(if (lit) colors.onSurface else colors.surface.copy(alpha = 0.72f))
            .border(1.dp, colors.outline, RoundedCornerShape(QuickButtonHeight / 2))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = lit }
            .padding(horizontal = 14.dp)
            .testTag(tag),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (lit) colors.surface else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

