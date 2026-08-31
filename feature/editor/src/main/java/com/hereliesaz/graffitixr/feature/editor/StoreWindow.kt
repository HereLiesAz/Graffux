// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/StoreWindow.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.azphalt.AssetType
import com.hereliesaz.graffitixr.common.azphalt.PackageSummary
import com.hereliesaz.graffitixr.common.azphalt.SignatureStatus
import com.hereliesaz.graffitixr.data.azphalt.AzphaltStoreHandoff
import com.hereliesaz.graffitixr.data.azphalt.InstalledExtension
import com.hereliesaz.graffitixr.design.components.ConfirmDialog
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/** Which half of [StoreWindow] is showing. "Manage Extensions"/the Extensions rail toggle open on
 *  [INSTALLED]; "Get Extensions"/"Store…" open on [BROWSE]. */
enum class StoreTab { BROWSE, INSTALLED }

/**
 * The azphalt extensions window: a single [FloatingWindow] with two tabs sharing one title bar,
 * rather than the two entirely separate windows this used to be ([StoreWindow] for what's
 * installed, [BrowseStoreWindow] for the in-app Repository API search). Nothing about either half
 * changed — same data, same actions — only the container: one window whose tab you land on
 * depends on which entry point opened it ([initialTab]), and switching tabs never re-fetches or
 * loses either side's state, since both lists are already held in the caller's UI state either way.
 */
@Composable
fun StoreWindow(
    initialTab: StoreTab,
    // Installed tab.
    installed: List<InstalledExtension>,
    /** Installed extension id -> a newer version the azphalt Repository API reports, if any
     *  (spec/repository-api.md § 6 `POST /updates`). Absent = no known update. Shared with the
     *  Browse tab's own per-card "Update" affordance — the same map either side reads by id. */
    updatesAvailable: Map<String, String> = emptyMap(),
    /** Extension ids currently mid-update/install, for a busy indicator on that card's button —
     *  shared between both tabs, since installing from Browse and updating from Installed are the
     *  same underlying call. */
    installingIds: Set<String> = emptySet(),
    onUpdate: (InstalledExtension) -> Unit = {},
    onUninstall: (String) -> Unit,
    // Browse tab.
    query: String,
    results: List<PackageSummary>,
    loading: Boolean,
    error: String?,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onInstall: (PackageSummary) -> Unit,
    onBuy: (PackageSummary) -> Unit,
    onOtherSources: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(initialTab) }
    val installedIds = remember(installed) { installed.map { it.id }.toSet() }

    // Loads the default listing the moment this window opens, same as opening a store app would —
    // regardless of which tab it opens on, so Browse has something to show the instant it's tapped.
    LaunchedEffect(Unit) { onSearch() }

    var pendingUninstall by remember { mutableStateOf<InstalledExtension?>(null) }
    pendingUninstall?.let { target ->
        ConfirmDialog(
            title = "Uninstall extension?",
            message = "\"${target.manifest.name}\" and everything it added — its brushes, LUTs, " +
                "filters — will be removed. This can't be undone.",
            confirmLabel = "Uninstall",
            onConfirm = { onUninstall(target.id); pendingUninstall = null },
            onDismiss = { pendingUninstall = null },
        )
    }

    FloatingWindow(title = "Extensions", onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StoreTabChip(text = "Browse", selected = tab == StoreTab.BROWSE) { tab = StoreTab.BROWSE }
                StoreTabChip(text = "Installed", selected = tab == StoreTab.INSTALLED) { tab = StoreTab.INSTALLED }
            }

            Spacer(Modifier.height(10.dp))

            when (tab) {
                StoreTab.BROWSE -> BrowseTab(
                    query = query,
                    results = results,
                    loading = loading,
                    error = error,
                    installedIds = installedIds,
                    updatesAvailable = updatesAvailable,
                    installingIds = installingIds,
                    onQueryChanged = onQueryChanged,
                    onSearch = onSearch,
                    onInstall = onInstall,
                    onBuy = onBuy,
                    onOtherSources = onOtherSources,
                )
                StoreTab.INSTALLED -> InstalledTab(
                    installed = installed,
                    updatesAvailable = updatesAvailable,
                    installingIds = installingIds,
                    onUpdate = onUpdate,
                    onUninstall = { pendingUninstall = it },
                )
            }
        }
    }
}

/** A selectable label — this window's Browse/Installed switcher. Mirrors [SketchToolsDialog]'s
 *  own `PickerChip`, kept as a separate (near-identical) copy rather than shared: the two live in
 *  different files with no common "small chip" component today, and duplicating four lines here
 *  is cheaper than introducing one for a single reuse. */
@Composable
private fun StoreTabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else Color.Gray,
        )
    }
}

@Composable
private fun InstalledTab(
    installed: List<InstalledExtension>,
    updatesAvailable: Map<String, String>,
    installingIds: Set<String>,
    onUpdate: (InstalledExtension) -> Unit,
    onUninstall: (InstalledExtension) -> Unit,
) {
    if (installed.isEmpty()) {
        Text(
            text = "No extensions installed yet.",
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    // Plain Column, not LazyColumn: this whole window's content already sits inside FloatingWindow's
    // own scrollable Column (its own doc comment explains why -- AzWindow caps height but doesn't
    // scroll past it). A LazyColumn nested in there is a lazy list -- built on SubcomposeLayout --
    // and AzWindow's sizing asks its content for an intrinsic measurement, which Compose refuses to
    // do across a SubcomposeLayout boundary ("Asking for intrinsic measurements of SubcomposeLayout
    // layouts is not supported"), crashing the instant this window opened. Extension lists are
    // small (installed count, not a full catalog), so losing lazy virtualization costs nothing real
    // here.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        installed.forEach { extension ->
            InstalledExtensionCard(
                extension = extension,
                updateVersion = updatesAvailable[extension.id],
                updating = extension.id in installingIds,
                onUpdate = { onUpdate(extension) },
                onUninstall = { onUninstall(extension) },
            )
        }
    }
}

@Composable
private fun BrowseTab(
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
) {
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

        // Plain Column, not LazyColumn -- see InstalledTab's identical doc comment for why (this
        // window's content sits inside FloatingWindow's own scrollable Column either way). Search
        // results are a page at a time, not an infinite feed, so losing lazy virtualization costs
        // nothing real here.
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
        // The repository API's own preview.image is documented as EITHER an absolute https: URL OR
        // an in-package-relative path (spec/extension-manifest.md § preview) -- but for a package
        // that isn't installed yet there is no local package to resolve an in-package path against.
        // What the real server (azphalt.store) actually sends for a relative preview is a
        // site-relative path (e.g. "/previews/<id>.png"), fetchable straight off the same host this
        // listing itself came from -- resolving it against that host, rather than discarding it,
        // is the difference between every card showing its preview and none of them ever doing so.
        val previewUrl = pkg.preview?.image?.let { image ->
            when {
                image.startsWith("http") -> image
                image.startsWith("/") -> AzphaltStoreHandoff.WEB_STORE_URL + image
                else -> null
            }
        }
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
                    contentPadding = CardActionPadding,
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
                    contentPadding = CardActionPadding,
                    modifier = Modifier.fillMaxWidth(),
                )

                else -> AzButton(
                    text = "Get",
                    onClick = onInstall,
                    shape = AzButtonShape.RECTANGLE,
                    contentPadding = CardActionPadding,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun InstalledExtensionCard(
    extension: InstalledExtension,
    updateVersion: String? = null,
    updating: Boolean = false,
    onUpdate: () -> Unit = {},
    onUninstall: () -> Unit,
) {
    val capabilities = rememberExtensionCapabilities(extension)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(extension.manifest.name, style = MaterialTheme.typography.titleSmall)
                    SignatureBadge(extension.signature)
                }
                extension.manifest.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
        if (capabilities.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                capabilities.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (updateVersion != null) {
            if (updating) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                }
            } else {
                AzButton(
                    text = "Update to $updateVersion",
                    onClick = onUpdate,
                    shape = AzButtonShape.RECTANGLE,
                    contentPadding = CardActionPadding,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        AzButton(
            text = "Uninstall",
            onClick = onUninstall,
            shape = AzButtonShape.RECTANGLE,
            contentPadding = CardActionPadding,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A card-row action button's content padding — AzButton's own default is sized for a primary,
 *  standalone call to action; a repeated per-card "Get"/"Buy"/"Update"/"Uninstall" button reads as
 *  oversized next to the card it belongs to at that size. */
private val CardActionPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)

@Composable
private fun SignatureBadge(status: SignatureStatus) {
    val (text, color) = when (status) {
        SignatureStatus.SIGNED_TRUSTED -> "Verified" to Color(0xFF4CAF50)
        SignatureStatus.SIGNED_UNTRUSTED -> "Signed" to Color(0xFFFFA726)
        SignatureStatus.UNSIGNED -> "Unsigned" to Color.Gray
        SignatureStatus.INVALID -> "Invalid" to Color(0xFFE53935)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

private val SUPPORTED_ASSET_TYPES = setOf(AssetType.BRUSH, AssetType.LUT)

@Composable
private fun rememberExtensionCapabilities(extension: InstalledExtension): List<String> {
    val m = extension.manifest
    return remember(extension.id) {
        buildList {
            val assetTypes = m.assets.map { it.type }.distinct()
            for (type in assetTypes) {
                if (type in SUPPORTED_ASSET_TYPES) add(type.wire.replaceFirstChar { it.uppercase() })
            }
            m.contributes?.let { c ->
                if (c.filters.isNotEmpty()) add("Filter")
                if (c.tools.isNotEmpty()) add("Tool")
                if (c.commands.isNotEmpty()) add("Command")
            }
            val unsupported = assetTypes.filter { it !in SUPPORTED_ASSET_TYPES && it != AssetType.UNKNOWN }
            if (unsupported.isNotEmpty()) {
                add("(${unsupported.joinToString { it.wire }} not supported)")
            }
        }
    }
}
