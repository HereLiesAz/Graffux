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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
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
                    color = MaterialTheme.colorScheme.error,
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

            // Plain Column, not LazyColumn: this whole window's content already sits inside
            // FloatingWindow's own scrollable Column (its own doc comment explains why -- AzWindow
            // caps height but doesn't scroll past it). A LazyColumn nested in there is a lazy
            // list -- built on SubcomposeLayout -- and AzWindow's sizing asks its content for an
            // intrinsic measurement, which Compose refuses to do across a SubcomposeLayout
            // boundary ("Asking for intrinsic measurements of SubcomposeLayout layouts is not
            // supported"), crashing the instant this window opened. Search results are a page at a
            // time, not an infinite feed, so losing lazy virtualization costs nothing real here.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                results.forEach { pkg ->
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

                // A plain label, not a button: AzButton has no confirmed "disabled" styling in this
                // codebase, and a full-opacity button that looks exactly like "Get"/"Buy" next to it
                // but silently no-ops on tap reads as broken, not as "already installed".
                installed -> Text(
                    text = "Installed",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
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
