// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/BrowseStoreWindow.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.azphalt.PackageSummary
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/**
 * In-app browse/search over the azphalt Repository API (spec/repository-api.md) — Graffux acting as a
 * full *client* of the standard rather than only delegating acquisition to a separate store app
 * ([AzphaltStoreHandoff]/[StoreChooserDialog], which still exist and still work as a second route in).
 * Free packages install straight through here; a paid one's [onBuy] is left to the caller, since
 * Graffux has no in-app payment of its own and routes a purchase to the web checkout instead.
 */
@Composable
fun BrowseStoreWindow(
    query: String,
    results: List<PackageSummary>,
    loading: Boolean,
    error: String?,
    installedIds: Set<String>,
    updatesAvailable: Map<String, String>,
    installingIds: Set<String>,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onInstall: (PackageSummary) -> Unit,
    onBuy: (PackageSummary) -> Unit,
    onOtherSources: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Loads the default listing the first time the window opens, same as opening a store app would.
    LaunchedEffect(Unit) { onSearch() }

    FloatingWindow(title = "Browse the Azphalt Store", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                label = { Text("Search brushes, LUTs, filters…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )

            if (loading) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp))
                }
            }

            error?.let {
                Text(
                    "Couldn't reach the store: $it",
                    color = Color(0xFFE53935),
                    style = MaterialTheme.typography.bodySmall,
                )
                AzButton(text = "Retry", onClick = onSearch, shape = AzButtonShape.RECTANGLE)
            }

            if (!loading && error == null && results.isEmpty()) {
                Text(
                    "No packages found.",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(results, key = { it.id }) { pkg ->
                    PackageCard(
                        pkg = pkg,
                        installed = pkg.id in installedIds,
                        updateVersion = updatesAvailable[pkg.id],
                        installing = pkg.id in installingIds,
                        onInstall = { onInstall(pkg) },
                        onBuy = { onBuy(pkg) },
                    )
                }
            }

            // A real store app or the web storefront can still do things this in-app browse can't
            // (Play Billing for a paid package, chief among them) — offered, not hidden, just no
            // longer the only door in.
            AzButton(
                text = "Other sources (store app / browser)…",
                onClick = onOtherSources,
                shape = AzButtonShape.RECTANGLE,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PackageCard(
    pkg: PackageSummary,
    installed: Boolean,
    updateVersion: String?,
    installing: Boolean,
    onInstall: () -> Unit,
    onBuy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        val previewUrl = pkg.preview?.image?.takeIf { it.startsWith("http") }
        if (previewUrl != null) {
            AsyncImage(
                model = previewUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.height(6.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(pkg.localizedName(null), style = MaterialTheme.typography.titleSmall)
                val subtitle = buildString {
                    pkg.author?.let { append(it) }
                    if (pkg.priceStatus.isPaid) {
                        if (isNotEmpty()) append(" · ")
                        append("Paid")
                    }
                    pkg.rating?.let {
                        if (isNotEmpty()) append(" · ")
                        append("★ %.1f".format(it))
                        if (pkg.ratingCount > 0) append(" (${pkg.ratingCount})")
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                pkg.localizedDescription(null)?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 2)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Box(Modifier.fillMaxWidth()) {
            when {
                installing -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(modifier = Modifier.height(20.dp)) }

                updateVersion != null -> AzButton(
                    text = "Update to $updateVersion",
                    onClick = onInstall,
                    shape = AzButtonShape.RECTANGLE,
                    modifier = Modifier.fillMaxWidth(),
                )

                installed -> AzButton(
                    text = "Installed",
                    onClick = {},
                    shape = AzButtonShape.RECTANGLE,
                    modifier = Modifier.fillMaxWidth(),
                )

                pkg.priceStatus.isPaid -> AzButton(
                    text = "Buy",
                    onClick = onBuy,
                    shape = AzButtonShape.RECTANGLE,
                    modifier = Modifier.fillMaxWidth(),
                )

                else -> AzButton(
                    text = "Get",
                    onClick = onInstall,
                    shape = AzButtonShape.RECTANGLE,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
