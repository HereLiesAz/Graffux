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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.azphalt.AssetType
import com.hereliesaz.graffitixr.common.azphalt.SignatureStatus
import com.hereliesaz.graffitixr.data.azphalt.InstalledExtension
import com.hereliesaz.graffitixr.design.components.ConfirmDialog
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/**
 * Manages installed azphalt extensions. [onBrowse] opens the in-app search/browse flow
 * ([BrowseStoreWindow]) — the primary way to acquire new ones now, backed directly by the azphalt
 * Repository API — with the delegated store-app/web handoff (spec/store-app.md) still reachable from
 * there as a secondary route. This window's own job is the "what's already here" list: letting the
 * user update or remove any of them.
 */
@Composable
fun StoreWindow(
    installed: List<InstalledExtension>,
    /** Installed extension id -> a newer version the azphalt Repository API reports, if any
     *  (spec/repository-api.md § 6 `POST /updates`). Absent = no known update. */
    updatesAvailable: Map<String, String> = emptyMap(),
    /** Extension ids currently mid-update, for a busy indicator on that card's Update button — the
     *  same download/install call BrowseStoreWindow shows a spinner for takes just as long here. */
    installingIds: Set<String> = emptySet(),
    onBrowse: () -> Unit,
    onUpdate: (InstalledExtension) -> Unit = {},
    onUninstall: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
                    InstalledExtensionCard(
                        extension = extension,
                        updateVersion = updatesAvailable[extension.id],
                        updating = extension.id in installingIds,
                        onUpdate = { onUpdate(extension) },
                        onUninstall = { pendingUninstall = extension },
                    )
                }
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        AzButton(
            text = "Uninstall",
            onClick = onUninstall,
            shape = AzButtonShape.RECTANGLE,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

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
