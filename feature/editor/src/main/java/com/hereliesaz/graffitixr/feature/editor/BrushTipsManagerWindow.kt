// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/BrushTipsManagerWindow.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/**
 * Every installed extension's brush(es), each with an on/off switch for whether it shows up in a
 * picker (the brush rail, the collapsed brush group) -- see
 * `SettingsRepository.hiddenBrushTipIds`'s own doc comment for why this exists: an extension can
 * bundle many brushes, and not every one belongs in a strip reached for mid-painting. Hiding here
 * is presentation-only -- a hidden brush still loads and paints fine if reached some other way;
 * this list, not the picker itself, is where an entry ever comes back.
 */
@Composable
fun BrushTipsManagerWindow(
    allBrushAssets: List<Pair<String, String>>,
    hiddenIds: Set<String>,
    onSetHidden: (id: String, hidden: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    FloatingWindow(title = "Manage Brush Tips", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (allBrushAssets.isEmpty()) {
                Text(
                    "No brush extensions installed yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
            allBrushAssets.forEach { (id, name) ->
                val visible = id !in hiddenIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Toggle from anywhere on the row (accessibility); the Switch just reflects state.
                        .toggleable(
                            value = visible,
                            role = Role.Switch,
                            onValueChange = { onSetHidden(id, !it) },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = visible, onCheckedChange = null)
                }
            }
        }
    }
}
