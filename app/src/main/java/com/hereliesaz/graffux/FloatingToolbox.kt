@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** One row of a [FloatingToolbox]: a labelled action. */
internal data class ToolboxAction(val text: String, val lit: Boolean = false, val onClick: () -> Unit)

/** What a [FloatingToolbox] shows: its header button, and the rows it opens to. */
internal data class ToolboxSpec(
    val id: String,
    val title: String,
    val icon: Int,
    val lit: Boolean,
    val actions: List<ToolboxAction>,
)

/**
 * A workspace's toolbox (Animation, 3D, Reference, Figma, Extensions), switched on from View…: a
 * one-button pill that opens to its actions beside it. Drag the pill anywhere; it starts at
 * [initialOffset] (pixels from the top-left of the space it is placed in).
 */
@Suppress("FunctionNaming")
@Composable
internal fun FloatingToolbox(
    spec: ToolboxSpec,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    initialOffset: IntOffset,
    modifier: Modifier = Modifier,
) {
    var offset by rememberSaveable(spec.id, stateSaver = IntOffsetSaver) { mutableStateOf(initialOffset) }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .offset { offset }
            .pointerInput(spec.id) {
                detectDragGestures { change, drag ->
                    change.consume()
                    offset = IntOffset(offset.x + drag.x.roundToInt(), offset.y + drag.y.roundToInt())
                }
            },
    ) {
        RailPill {
            RailButton(
                label = spec.title,
                light = RailLight(active = spec.lit),
                testTag = "toolbox.${spec.id}",
                onClick = { onExpandedChange(!expanded) },
            ) { RailGlyph(spec.icon, active = spec.lit) }
        }
        if (expanded) {
            Column(Modifier.offset(x = RailEdgeGap)) {
                MenuPanel(
                    rows = spec.actions.map { MenuRow.Page(if (it.lit) "${it.text} ✓" else it.text, it.onClick) },
                    onDismiss = {},
                    title = spec.title,
                    maxHeight = 360.dp,
                )
            }
        }
    }
}

private val IntOffsetSaver = androidx.compose.runtime.saveable.Saver<IntOffset, List<Int>>(
    save = { listOf(it.x, it.y) },
    restore = { IntOffset(it[0], it[1]) },
)
