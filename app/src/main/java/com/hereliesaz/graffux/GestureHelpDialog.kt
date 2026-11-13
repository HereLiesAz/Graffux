@file:Suppress("FunctionNaming", "MagicNumber")

package com.hereliesaz.graffux

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import com.hereliesaz.graffitixr.design.components.GraffuxAlertDialog
import com.hereliesaz.graffitixr.design.theme.GestureHelpStrings

/** One line of the gesture help: each multi-finger slot and what it is set to now. */
internal fun gestureHelpLines(
    mapping: Map<GestureSlot, GestureAction>,
    slotName: (GestureSlot) -> String = { it.label },
    actionName: (GestureAction) -> String = { it.label },
): List<String> =
    GestureSlot.entries.map { slot -> "${slotName(slot)}: ${actionName(mapping[slot] ?: slot.defaultAction)}" }

/**
 * Every gesture the editor answers to, in one place (menu → Gestures…): the reassignable
 * multi-finger ones with their current action — so whatever a gesture was set to is never a
 * surprise — and the touches on the chrome that nothing on screen otherwise names.
 */
@Composable
internal fun GestureHelpDialog(
    mapping: Map<GestureSlot, GestureAction>,
    strings: GestureHelpStrings,
    close: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val slotNames = GestureSlot.entries.associateWith { stringResource(it.labelRes()) }
    val actionNames = GestureAction.entries.associateWith { stringResource(it.labelRes()) }
    GraffuxAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(close) } },
        dismissButton = { TextButton(onClick = onOpenSettings) { Text(strings.settings) } },
        title = { Text(strings.title) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("gesture.help"),
            ) {
                Text(strings.intro, style = MaterialTheme.typography.bodyMedium)
                gestureHelpLines(mapping, { slotNames.getValue(it) }, { actionNames.getValue(it) }).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
                Text(
                    strings.others,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                )
                val others = listOf(
                    strings.sizeChip, strings.drawDistance, strings.layerTap, strings.sliderReset, strings.edgeSwipe,
                    strings.hiddenUi,
                )
                others.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
    )
}
