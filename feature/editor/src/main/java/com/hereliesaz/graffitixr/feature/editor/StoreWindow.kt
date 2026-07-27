// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/StoreWindow.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.data.azphalt.MarketplaceEntry
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/**
 * The Azphalt Store: browse the live catalog ([EditorViewModel.searchStore]), and install or
 * uninstall packages. A floating window like every other tool option — it stays open over the
 * canvas while the user keeps browsing, rather than blocking it like a modal picker.
 */
@Composable
fun StoreWindow(
    state: EditorViewModel.StoreUiState,
    installedIds: Set<String>,
    onSearch: (String) -> Unit,
    onInstall: (MarketplaceEntry) -> Unit,
    onUninstall: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var queryText by remember { mutableStateOf(state.query) }

    FloatingWindow(title = "Azphalt Store", onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                label = { Text("Search") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(queryText) }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            AzButton(text = "Go", onClick = { onSearch(queryText) }, shape = AzButtonShape.RECTANGLE)
        }

        Spacer(Modifier.height(10.dp))

        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            state.error != null -> Text(
                text = state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            state.results.isEmpty() -> Text(
                text = "No packages found.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.results, key = { it.id }) { entry ->
                    StoreEntryCard(
                        entry = entry,
                        isInstalled = entry.id in installedIds,
                        onInstall = { onInstall(entry) },
                        onUninstall = { onUninstall(entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StoreEntryCard(
    entry: MarketplaceEntry,
    isInstalled: Boolean,
    onInstall: () -> Unit,
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
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                if (entry.author.isNotBlank()) {
                    Text("by ${entry.author}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
            Text(entry.priceLabel, style = MaterialTheme.typography.labelSmall)
        }
        if (entry.description.isNotBlank()) {
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (entry.tags.isNotEmpty()) {
            Text(
                text = entry.tags.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        when {
            !entry.installable -> Text(
                text = "Not supported on this device yet",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
            )
            isInstalled -> AzButton(
                text = "Uninstall",
                onClick = onUninstall,
                shape = AzButtonShape.RECTANGLE,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> AzButton(
                text = "Install",
                onClick = onInstall,
                shape = AzButtonShape.RECTANGLE,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
