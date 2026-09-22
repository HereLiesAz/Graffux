package com.hereliesaz.graffitixr.design.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.design.R

/**
 * A modal yes/no gate for an action that can't be undone — real [AlertDialog] rather than a
 * [FloatingWindow], because a destructive action needs the canvas actually blocked and the choice
 * actually forced, not a panel that can be ignored or that leaves the thing it's asking about
 * reachable underneath it.
 *
 * @param confirmLabel names the action itself ("Delete", "Delete project") rather than a bare
 * "OK" — the whole point is that the user reads what they're about to do before tapping it.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            AzButton(text = confirmLabel, onClick = onConfirm, shape = AzButtonShape.RECTANGLE)
        },
        dismissButton = {
            AzButton(text = stringResource(R.string.cancel_button), onClick = onDismiss, shape = AzButtonShape.RECTANGLE)
        },
    )
}
