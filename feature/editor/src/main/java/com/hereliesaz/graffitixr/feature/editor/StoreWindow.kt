// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/StoreWindow.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.data.azphalt.InstalledExtension
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/**
 * Manages installed azphalt extensions and hands off acquiring new ones to a real store app
 * (spec/store-app.md) — Graffux is a host, not a marketplace, so browsing/searching/purchasing lives
 * in a separate, installable app the way it would for any other content the OS handles via an Intent.
 * [onBrowse] resolves and launches that handoff (or reports there's nothing to launch); this window's
 * own job is just the "what's already here" list and letting the user remove any of them.
 */
@Composable
fun StoreWindow(
    installed: List<InstalledExtension>,
    onBrowse: () -> Unit,
    onUninstall: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    FloatingWindow(title = "Extensions", onDismiss = onDismiss) {
        AzButton(
            text = "Browse the Azphalt Store",
            onClick = onBrowse,
            shape = AzButtonShape.RECTANGLE,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))

        if (installed.isEmpty()) {
            Text(
                text = "No extensions installed yet.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(installed, key = { it.id }) { extension ->
                    InstalledExtensionCard(extension = extension, onUninstall = { onUninstall(extension.id) })
                }
            }
        }
    }
}

@Composable
private fun InstalledExtensionCard(
    extension: InstalledExtension,
    onUninstall: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(extension.manifest.name, style = MaterialTheme.typography.titleSmall)
                extension.manifest.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        AzButton(
            text = "Uninstall",
            onClick = onUninstall,
            shape = AzButtonShape.RECTANGLE,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
