@file:Suppress("FunctionNaming")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** A swatch's touch target: the 48dp accessibility minimum (the drawn circle is [RECENT_SWATCH_DRAWN] inside it). */
internal val RECENT_SWATCH_TOUCH: Dp = 48.dp
private val RECENT_SWATCH_DRAWN = 36.dp

/** How long "Deleted #RRGGBB" stays up. */
private const val DELETED_NOTICE_MS = 1_800L

/** How many [touch]-wide swatches fit in [width], at least one. */
internal fun recentGridColumns(width: Dp, touch: Dp = RECENT_SWATCH_TOUCH): Int =
    (width / touch).toInt().coerceAtLeast(1)

/**
 * The colours the user has used, newest first: tap one to use it, press and hold to delete it. Deleting
 * buzzes and says "Deleted #RRGGBB" in a polite live region (no dialog); TalkBack gets "Delete" as an
 * action of its own on every swatch, since a long press is hard to make there. Empty, it says how it fills.
 */
@Composable
fun RecentColorGrid(
    colors: List<Color>,
    selected: Color,
    onPick: (Color) -> Unit,
    onDelete: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var deleted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(deleted) {
        if (deleted != null) {
            delay(DELETED_NOTICE_MS)
            deleted = null
        }
    }
    val deletedFormat = stringResource(R.string.colour_recent_deleted, "%s")
    Column(modifier.testTag("touchColor.recent"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.colour_recent_heading), style = MaterialTheme.typography.labelMedium)
        if (colors.isEmpty()) {
            Text(
                stringResource(R.string.colour_recent_empty),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("touchColor.recent.empty"),
            )
        } else {
            BoxWithConstraints {
                val columns = recentGridColumns(maxWidth)
                Column {
                    colors.chunked(columns).forEach { row ->
                        Row {
                            row.forEach { colour ->
                                RecentSwatch(
                                    colour = colour,
                                    current = colour.toArgb() == selected.copy(alpha = 1f).toArgb(),
                                    onPick = { onPick(colour) },
                                    onDelete = { buzz ->
                                        if (buzz) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        deleted = deletedFormat.format(hexOf(colour))
                                        onDelete(colour)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        deleted?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("touchColor.recent.deleted"),
            )
        }
    }
}

/** One swatch: a [RECENT_SWATCH_TOUCH] target round a smaller circle. Tap uses it; hold or TalkBack deletes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentSwatch(colour: Color, current: Boolean, onPick: () -> Unit, onDelete: (buzz: Boolean) -> Unit) {
    val useLabel = stringResource(R.string.colour_recent_use)
    val deleteLabel = stringResource(R.string.colour_recent_delete)
    val hex = hexOf(colour)
    val description = stringResource(
        if (current) R.string.colour_recent_swatch_current else R.string.colour_recent_swatch,
        hex,
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(RECENT_SWATCH_TOUCH)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = useLabel,
                onClick = onPick,
                onLongClickLabel = deleteLabel,
                // combinedClickable already buzzes for a long click; only the TalkBack action needs its own.
                onLongClick = { onDelete(false) },
            )
            .semantics {
                contentDescription = description
                customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(true); true })
            }
            .testTag("touchColor.recent.swatch"),
    ) {
        Box(
            Modifier
                .size(RECENT_SWATCH_DRAWN)
                .background(colour, CircleShape)
                .border(if (current) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
        )
    }
}
