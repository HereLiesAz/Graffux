package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.ToolRanking
import com.hereliesaz.graffitixr.common.model.ToolUsage

/** How many tools a strip shows before it starts scrolling. */
private const val STRIP_LIMIT = 12

/** Which of the three sets the sheet is showing. */
internal enum class ShortcutTab(val label: String) {
    FAVORITES("Favourites"),
    RECENT("Recent"),
    FREQUENT("Most used"),
}

/**
 * The shortcuts sheet: the tools you actually reach for, one swipe from the bottom of the screen.
 *
 * The rail holds every tool this app has, which is what makes it complete and also what makes it a
 * place you have to *navigate*: fourteen tools across a strip and three nested rails, most of them
 * one you use daily and the rest ones you use twice a year. This is the other half of that — a flat
 * row of whichever ones matter to you, reachable without opening anything.
 *
 * **Three sets behind low-profile tabs**, and the order of them is the point:
 *
 *  * **Favourites** — what you pinned. Long-press any tool here or in the other two strips.
 *  * **Recent** — what you last used, newest first.
 *  * **Most used** — what you use most, commonest first.
 *
 * The last two exist so the sheet is worth something on day one. A shortcuts strip that is empty
 * until you configure it is a shortcuts strip nobody configures, so the app fills it in from what
 * you do and gets out of the way once you have opinions: Favourites is the default tab as soon as
 * it has anything in it, and Recent until then.
 */
@Composable
internal fun ShortcutsSheet(
    activeTool: Tool,
    usage: ToolUsage,
    favorites: List<String>,
    onPickTool: (Tool) -> Unit,
    onToggleFavorite: (Tool) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Defaults to whichever tab has something to show, so the sheet never opens on an empty strip.
    var tab by remember(favorites.isEmpty()) {
        mutableStateOf(if (favorites.isEmpty()) ShortcutTab.RECENT else ShortcutTab.FAVORITES)
    }

    val tools = when (tab) {
        ShortcutTab.FAVORITES -> ToolRanking.favorites(favorites, STRIP_LIMIT)
        ShortcutTab.RECENT -> ToolRanking.mostRecent(usage, STRIP_LIMIT)
        ShortcutTab.FREQUENT -> ToolRanking.mostFrequent(usage, STRIP_LIMIT)
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ShortcutTab.entries.forEach { entry ->
                TabLabel(entry.label, selected = entry == tab) { tab = entry }
            }
        }

        if (tools.isEmpty()) {
            Text(
                when (tab) {
                    // Says what to do, not merely that there is nothing here.
                    ShortcutTab.FAVORITES -> "Hold a tool to pin it here."
                    else -> "Tools you use will collect here."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tools.forEach { tool ->
                ToolChip(
                    tool = tool,
                    isActive = tool == activeTool,
                    isFavorite = tool.name in favorites,
                    onPick = { onPickTool(tool) },
                    onToggleFavorite = { onToggleFavorite(tool) },
                )
            }
        }
    }
}

/**
 * A tab. Low-profile deliberately: a label and an underline, no container, no elevation. The sheet
 * sits over the artwork, and three filled tab chips would be a band of chrome across it — the tabs
 * are a way of choosing which strip you are looking at, not a thing to look at.
 */
@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
        )
        Box(
            Modifier
                .padding(top = 2.dp)
                .height(2.dp)
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

/**
 * One tool. Tap picks it up — and picking a tool you already hold puts it down, which is the same
 * rule the rail's own tool items follow, so the two surfaces cannot disagree about what a second tap
 * means. Long-press pins or unpins it, which is how the Favourites strip is built: from the tools
 * you are already looking at, rather than from a separate editing mode you have to find first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolChip(
    tool: Tool,
    isActive: Boolean,
    isFavorite: Boolean,
    onPick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val entry = TOOL_CATALOG[tool] ?: return
    val tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
            )
            .combinedClickable(onClick = onPick, onLongClick = onToggleFavorite)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(entry.icon),
            contentDescription = entry.label,
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
        Text(
            // The pin is on the label rather than a badge on the glyph: the glyph is the tool, and
            // a second mark on it reads as part of the drawing at this size.
            if (isFavorite) "★ ${entry.label}" else entry.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

