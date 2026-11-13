package com.hereliesaz.graffux

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hereliesaz.graffitixr.design.components.GraffuxAlertDialog

/** The one-time "I am 16 or older" declaration that must come before stroke contribution can be switched on. */
@Suppress("FunctionNaming")
@Composable
internal fun StrokeAgeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    GraffuxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_stroke_age_title)) },
        text = { Text(stringResource(R.string.settings_stroke_age_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_stroke_age_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_stroke_age_cancel)) }
        },
    )
}
