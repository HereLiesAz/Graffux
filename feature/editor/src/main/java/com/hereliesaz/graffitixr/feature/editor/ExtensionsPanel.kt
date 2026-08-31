package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.Contribution
import com.hereliesaz.graffitixr.data.azphalt.InstalledExtension

/**
 * Installed extensions, with a second level for the ones that offer more than one thing to run.
 * A code extension can declare several `contributes.filters`/`.tools`/`.commands`, each its own
 * entry point — tapping the extension used to always run one fixed (and, for a multi-contribution
 * manifest with no top-level entry, sometimes silently no) action; [activeExtension] non-null means
 * this is now showing that extension's own list instead, so the user actually picks which one runs.
 */
@Composable
fun ExtensionsPanel(
    extensions: List<InstalledExtension>,
    activeExtension: InstalledExtension?,
    contributionsOf: (InstalledExtension) -> List<Pair<String, Contribution>>,
    onSelect: (String) -> Unit,
    onSelectContribution: (String, Contribution) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (activeExtension != null) {
                    Text(
                        "‹",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.clickable { onBack() }.padding(end = 8.dp),
                    )
                }
                Text(
                    activeExtension?.manifest?.name ?: "Extensions",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }
            Text(
                "Close",
                color = Color.Gray,
                modifier = Modifier
                    .clickable { onClose() }
                    .padding(8.dp)
            )
        }

        if (activeExtension != null) {
            val contributions = contributionsOf(activeExtension)
            LazyColumn(Modifier.fillMaxWidth()) {
                items(contributions) { (kindLabel, contribution) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectContribution(activeExtension.id, contribution) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(contribution.name, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                            Text(kindLabel, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else if (extensions.isEmpty()) {
            Text(
                "No code extensions installed.",
                color = Color.Gray,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(extensions) { extension ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(extension.id) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = extension.manifest.name,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            val description = extension.manifest.description
                            if (!description.isNullOrBlank()) {
                                Text(
                                    text = description,
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
