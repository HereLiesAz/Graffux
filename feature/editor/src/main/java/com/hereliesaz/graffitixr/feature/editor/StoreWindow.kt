// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/StoreWindow.kt
@file:Suppress("TooManyFunctions") // one window: its tabs, card, dialogs and label helpers

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import com.hereliesaz.graffitixr.design.theme.Amber
import com.hereliesaz.graffitixr.design.theme.NeonGreen
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
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
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.design.components.GraffuxButtonShape
import com.hereliesaz.graffitixr.common.azphalt.AssetType
import com.hereliesaz.graffitixr.common.azphalt.PackageSummary
import com.hereliesaz.graffitixr.common.azphalt.SignatureStatus
import com.hereliesaz.graffitixr.data.azphalt.AzphaltStoreHandoff
import com.hereliesaz.graffitixr.data.azphalt.InstalledExtension
import com.hereliesaz.graffitixr.data.azphalt.purchase.BuyPrice
import com.hereliesaz.graffitixr.design.components.FullWidthButtonHeight
import com.hereliesaz.graffitixr.design.components.ConfirmDialog
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/** Which half of [StoreWindow] is showing. "Manage Extensions"/the Extensions rail toggle open on
 *  [INSTALLED]; "Azphalt Store…" opens on [BROWSE]. */
enum class StoreTab { BROWSE, INSTALLED }

/**
 * The azphalt extensions window: a single [FloatingWindow] with two tabs sharing one title bar,
 * rather than the two entirely separate windows this used to be ([StoreWindow] for what's
 * installed, [BrowseStoreWindow] for the in-app Repository API search). Nothing about either half
 * changed — same data, same actions — only the container: one window whose tab you land on
 * depends on which entry point opened it ([initialTab]), and switching tabs never re-fetches or
 * loses either side's state, since both lists are already held in the caller's UI state either way.
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
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
    /** In-app purchase progress per package id. */
    purchaseStates: Map<String, com.hereliesaz.graffitixr.data.azphalt.purchase.PurchaseState> = emptyMap(),
    /** Buy on azphalt.store instead (the web checkout); always offered next to in-app Buy. */
    onBuyOnWeb: (PackageSummary) -> Unit = {},
    onRestorePurchases: () -> Unit = {},
    /** An awaiting or pending purchase's "check again". */
    onCheckPurchase: (PackageSummary) -> Unit = {},
    /** Whose price each paid card shows; see [buyLabel]. */
    buyPrices: Map<String, BuyPrice> = emptyMap(),
    /** Asked for the paid packages on screen, so their [buyPrices] load. */
    onLoadPrices: (List<String>) -> Unit = {},
    /** "Restore with a purchase code", with what the buyer entered. */
    onRestoreWithCode: (String) -> Unit = {},
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

    FloatingWindow(title = if (tab == StoreTab.BROWSE) "Azphalt Store" else "Extensions", onDismiss = onDismiss) {
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
                    purchaseStates = purchaseStates,
                    onBuyOnWeb = onBuyOnWeb,
                    onRestorePurchases = onRestorePurchases,
                    onCheckPurchase = onCheckPurchase,
                    buyPrices = buyPrices,
                    onLoadPrices = onLoadPrices,
                    onRestoreWithCode = onRestoreWithCode,
                )
                StoreTab.INSTALLED -> InstalledTab(
                    installed = installed,
                    updatesAvailable = updatesAvailable,
                    installingIds = installingIds,
                    onUpdate = onUpdate,
                    onUninstall = { pendingUninstall = it },
                    onBrowse = { tab = StoreTab.BROWSE },
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
                if (selected) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                },
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun InstalledTab(
    installed: List<InstalledExtension>,
    updatesAvailable: Map<String, String>,
    installingIds: Set<String>,
    onUpdate: (InstalledExtension) -> Unit,
    onUninstall: (InstalledExtension) -> Unit,
    onBrowse: () -> Unit = {},
) {
    if (installed.isEmpty()) {
        Text(
            text = "No extensions installed yet. Brushes, colour grades and filters come from the Azphalt Store.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        GraffuxButton(text = "Browse the Azphalt Store", onClick = onBrowse, shape = GraffuxButtonShape.RECTANGLE)
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

@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
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
    purchaseStates: Map<String, com.hereliesaz.graffitixr.data.azphalt.purchase.PurchaseState>,
    onBuyOnWeb: (PackageSummary) -> Unit,
    onRestorePurchases: () -> Unit,
    onCheckPurchase: (PackageSummary) -> Unit,
    buyPrices: Map<String, BuyPrice>,
    onLoadPrices: (List<String>) -> Unit,
    onRestoreWithCode: (String) -> Unit,
) {
    val paidIds = remember(results) { results.filter { it.priceStatus.isPaid }.map { it.id } }
    LaunchedEffect(paidIds) { if (paidIds.isNotEmpty()) onLoadPrices(paidIds) }
    var askCode by remember { mutableStateOf(false) }
    if (askCode) {
        RestoreCodeDialog(
            onRestore = { code -> askCode = false; onRestoreWithCode(code) },
            onDismiss = { askCode = false },
        )
    }
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
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }

        error?.let {
            Text(
                "Couldn't reach the Azphalt Store: $it.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            GraffuxButton(text = "Retry", onClick = onSearch, shape = GraffuxButtonShape.RECTANGLE)
        }

        if (!loading && error == null && results.isEmpty()) {
            Text(
                "No packages found.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    purchase = purchaseStates[pkg.id],
                    onBuyOnWeb = { onBuyOnWeb(pkg) },
                    onCheckAgain = { onCheckPurchase(pkg) },
                    buyPrice = buyPrices[pkg.id],
                )
            }
        }

        // A real store app or the web storefront can still do things this in-app browse can't
        // (Play Billing for a paid package, chief among them) — offered, not hidden, just no
        // longer the only door in.
        // Restores licences bought in-app, on azphalt.store or in a store app, and installs what is missing.
        GraffuxButton(
            text = "Restore purchases",
            onClick = onRestorePurchases,
            shape = GraffuxButtonShape.RECTANGLE,
            modifier = Modifier
                .fillMaxWidth()
                .height(FullWidthButtonHeight)
                .padding(top = 4.dp),
        )
        // A card purchase on a reinstalled or new device: the buyer cookie is never backed up, so the
        // code azphalt.store showed at checkout (or its success link) brings it back.
        TextButton(onClick = { askCode = true }, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch)) {
            Text("Restore with a purchase code", style = MaterialTheme.typography.bodySmall)
        }
        GraffuxButton(
            text = "Other sources (store app / browser)…",
            onClick = onOtherSources,
            shape = GraffuxButtonShape.RECTANGLE,
            modifier = Modifier
                .fillMaxWidth()
                .height(FullWidthButtonHeight)
                .padding(top = 4.dp),
        )
    }
}

@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun PackageCard(
    pkg: PackageSummary,
    installed: Boolean,
    updateVersion: String?,
    installing: Boolean,
    onInstall: () -> Unit,
    onBuy: () -> Unit,
    purchase: com.hereliesaz.graffitixr.data.azphalt.purchase.PurchaseState? = null,
    onBuyOnWeb: () -> Unit = {},
    onCheckAgain: () -> Unit = {},
    buyPrice: BuyPrice? = null,
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
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
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
                        append(shownPrice(pkg.price, buyPrice) ?: "Paid")
                    }
                    pkg.rating?.let {
                        if (isNotEmpty()) append(" · ")
                        append("★ %.1f".format(it))
                        if (pkg.ratingCount > 0) append(" (${pkg.ratingCount})")
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                pkg.localizedDescription(null)?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Box(Modifier.fillMaxWidth()) {
            when {
                installing -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }

                updateVersion != null -> GraffuxButton(
                    text = "Update to $updateVersion",
                    onClick = onInstall,
                    shape = GraffuxButtonShape.RECTANGLE,
                    contentPadding = CardActionPadding,
                    modifier = Modifier.fillMaxWidth().height(FullWidthButtonHeight),
                )

                // A plain label, not a button: AzButton has no confirmed "disabled" styling in this
                // codebase, and a full-opacity button that looks exactly like "Get"/"Buy" next to it
                // but silently no-ops on tap reads as broken, not as "already installed".
                installed -> Text(
                    text = "Installed",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )

                purchase is com.hereliesaz.graffitixr.data.azphalt.purchase.PurchaseState.Working -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(purchase.step, style = MaterialTheme.typography.bodySmall)
                }

                // Stripe Checkout is open in a browser tab: say so, and let the user ask again once
                // they've paid instead of waiting for the app to come back to the foreground.
                purchase is com.hereliesaz.graffitixr.data.azphalt.purchase.PurchaseState.AwaitingCheckout -> Column(
                    Modifier.fillMaxWidth().testTag("store.purchase.awaiting"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Finish paying in the browser. It installs when the payment goes through.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    TextButton(onClick = onCheckAgain, modifier = Modifier.heightIn(min = MinTouch)) {
                        Text("I've paid — check now")
                    }
                }

                purchase is com.hereliesaz.graffitixr.data.azphalt.purchase.PurchaseState.Pending -> Column(
                    Modifier.fillMaxWidth().testTag("store.purchase.pending"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = purchase.message,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    TextButton(onClick = onCheckAgain, modifier = Modifier.heightIn(min = MinTouch)) {
                        Text("Check again")
                    }
                }

                pkg.priceStatus.isPaid -> Column(Modifier.fillMaxWidth()) {
                    GraffuxButton(
                        // The price before the tap, when the repository sends it.
                        text = buyLabel(pkg.price, buyPrice),
                        onClick = onBuy,
                        shape = GraffuxButtonShape.RECTANGLE,
                        contentPadding = CardActionPadding,
                        modifier = Modifier.fillMaxWidth().height(FullWidthButtonHeight),
                    )
                    // In-app is one way to buy, not the only one.
                    TextButton(onClick = onBuyOnWeb, modifier = Modifier.fillMaxWidth()) {
                        Text("or buy on azphalt.store", style = MaterialTheme.typography.bodySmall)
                    }
                    (purchase as? com.hereliesaz.graffitixr.data.azphalt.purchase.PurchaseState.Failed)?.let {
                        Text(
                            it.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                else -> GraffuxButton(
                    text = "Get",
                    onClick = onInstall,
                    shape = GraffuxButtonShape.RECTANGLE,
                    contentPadding = CardActionPadding,
                    modifier = Modifier.fillMaxWidth().height(FullWidthButtonHeight),
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
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

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
                    Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
        if (capabilities.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(capabilities.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Spacer(Modifier.height(6.dp))
        if (updateVersion != null) {
            if (updating) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                GraffuxButton(
                    text = "Update to $updateVersion",
                    onClick = onUpdate,
                    shape = GraffuxButtonShape.RECTANGLE,
                    contentPadding = CardActionPadding,
                    modifier = Modifier.fillMaxWidth().height(FullWidthButtonHeight),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        GraffuxButton(
            text = "Uninstall",
            onClick = onUninstall,
            shape = GraffuxButtonShape.RECTANGLE,
            contentPadding = CardActionPadding,
            modifier = Modifier.fillMaxWidth().height(FullWidthButtonHeight),
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
        SignatureStatus.SIGNED_TRUSTED -> "Verified" to NeonGreen
        SignatureStatus.SIGNED_UNTRUSTED -> "Signed" to Amber
        SignatureStatus.UNSIGNED -> "Unsigned" to MaterialTheme.colorScheme.onSurfaceVariant
        SignatureStatus.INVALID -> "Invalid" to MaterialTheme.colorScheme.error
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

/**
 * A paid card's button: "Buy · <price>" with the price of whoever will charge it, else plain "Buy".
 * A Play route shows Google's own localised price ([BuyPrice.Play]); a Stripe route the repository's.
 * Null [buyPrice] (a Play build whose route or Play price isn't known yet) shows no number at all,
 * rather than one the Play sheet may not charge.
 */
internal fun buyLabel(
    price: com.hereliesaz.graffitixr.common.azphalt.PackagePrice?,
    buyPrice: BuyPrice? = BuyPrice.Repository,
): String = shownPrice(price, buyPrice)?.let { "Buy · $it" } ?: "Buy"

/** The price text to show for a paid package; see [buyLabel]. */
internal fun shownPrice(
    price: com.hereliesaz.graffitixr.common.azphalt.PackagePrice?,
    buyPrice: BuyPrice?,
): String? = when (buyPrice) {
    is BuyPrice.Play -> buyPrice.formatted
    BuyPrice.Repository -> price?.let(::formatPrice)
    null -> null
}

/** [price] in the user's locale: "$4.99", "€12.00", "¥500"; unknown currencies as "499 XYZ". */
internal fun formatPrice(
    price: com.hereliesaz.graffitixr.common.azphalt.PackagePrice,
    locale: java.util.Locale = java.util.Locale.getDefault(),
): String {
    val code = price.currency.uppercase(java.util.Locale.ROOT)
    val currency = runCatching { java.util.Currency.getInstance(code) }.getOrNull()
        ?: return "${price.amountMinor} $code"
    val format = java.text.NumberFormat.getCurrencyInstance(locale).apply { this.currency = currency }
    val digits = currency.defaultFractionDigits.coerceAtLeast(0)
    return format.format(java.math.BigDecimal.valueOf(price.amountMinor, digits))
}

private val MinTouch = 48.dp

/** Asks for a card purchase's code (`cs_…`) or the success link azphalt.store showed. */
@Suppress("FunctionNaming")
@Composable
private fun RestoreCodeDialog(onRestore: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore with a purchase code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste the purchase code (it starts with cs_) or the link from your azphalt.store " +
                        "checkout. Every card purchase you made comes back with it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Purchase code or link") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRestore(code) }, enabled = code.isNotBlank()) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
