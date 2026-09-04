package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.design.components.FloatingWindow

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
    // FloatingWindow, not AlertDialog: this is a non-destructive "where do you want to browse
    // from" choice, not a gate on an irreversible action -- every other panel in the app (see
    // AddContentDialog, CornerRadiusDialog, etc.) is this same non-modal, draggable window that
    // keeps the canvas visible and interactive underneath; a full-scrim blocking modal here was
    // the one place in the whole tool surface that broke that contract for no reason tied to what
    // this dialog actually asks. Contrast ConfirmDialog, which stays a real AlertDialog on
    // purpose: it gates an action that can't be undone, and needs the canvas actually blocked.
    FloatingWindow(title = "Azphalt Store", onDismiss = onCancel) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AzButton(text = "Web", onClick = onWeb, shape = AzButtonShape.RECTANGLE)
                AzButton(text = "Android", onClick = onAndroid, shape = AzButtonShape.RECTANGLE)
                AzButton(text = "Cancel", onClick = onCancel, shape = AzButtonShape.RECTANGLE)
            }
        }
    }
}
