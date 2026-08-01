package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Where to get extensions from.
 *
 * Acquisition is delegated — Graffux is a host, not a marketplace (azphalt spec/store-app.md) — and
 * there are two ways to reach one, which are not interchangeable. The Android store app hands
 * verified bytes straight back to this app through an Intent, so an install is one round trip. The
 * web storefront is a browser: it can show and sell anything in the catalogue, but getting a package
 * from it back into the app goes through the `azphalt://install` deep link rather than a direct
 * hand-off.
 *
 * Only one of those is guaranteed to exist. A host with no store app installed is "a host with no
 * *browse* affordance, not a broken one" (spec § Discovery), so this asks rather than assuming, and
 * says which route is actually available before the user picks.
 *
 * [storeAppInstalled] decides what **Android** does, not whether it is offered: with no store app it
 * leads to installing one instead of dead-ending, which is the more useful answer to "I chose Android
 * and there is no Android store".
 */
@Composable
fun StoreChooserDialog(
    storeAppInstalled: Boolean,
    onWeb: () -> Unit,
    onAndroid: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Azphalt Store") },
        text = {
            Text(
                if (storeAppInstalled) {
                    "Browse for brushes, colour grades and filters.\n\n" +
                        "Android opens the store app installed on this device, which hands packages " +
                        "straight back to Graffux. Web opens azphalt.store in your browser."
                } else {
                    "Browse for brushes, colour grades and filters.\n\n" +
                        "No store app is installed on this device — Android will offer to get one. " +
                        "Web opens azphalt.store in your browser."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        // Three buttons, so they share the confirm slot: AlertDialog's dismiss slot holds exactly one,
        // and splitting them across both would put Cancel in the middle on some layouts.
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onWeb) { Text("Web") }
                TextButton(onClick = onAndroid) { Text("Android") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}
