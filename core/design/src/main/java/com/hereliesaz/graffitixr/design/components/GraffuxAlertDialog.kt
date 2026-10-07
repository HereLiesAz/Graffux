package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Square, like [FloatingWindow]'s AzWindow chrome and every AzButton.RECTANGLE in the app. */
private val DialogShape = RoundedCornerShape(0.dp)

/**
 * The app's modal dialog: a real [AlertDialog] (it blocks the canvas, which a [FloatingWindow]
 * deliberately does not) dressed in [FloatingWindow]'s theme, so the two kinds read as one family.
 *
 * Material's defaults gave every modal a tinted `surfaceContainerHigh` container, tonal elevation
 * and 28dp rounded corners, none of which the floating windows have. Here: the same translucent
 * `surface` and `outlineVariant` outline as [FloatingWindow], square corners, no tonal tint, and
 * title and body in the scheme's own on-surface colors. Use it for every modal; never call
 * [AlertDialog] directly.
 */
@Suppress("FunctionNaming")
@Composable
fun GraffuxAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = Modifier.border(1.dp, colors.outlineVariant, DialogShape),
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = DialogShape,
        containerColor = colors.surface.copy(alpha = SURFACE_ALPHA),
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}

/** [FloatingWindow]'s surface alpha. */
private const val SURFACE_ALPHA = 0.85f
