package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * One option in a row of mutually exclusive choices (Settings option rows).
 *
 * The selected option is unmistakable without relying on colour alone:
 *  - a solid `primary` fill with an `onPrimary` label (>= 4.5:1; the theme picks `onPrimary` for that),
 *  - a leading check mark, and
 *  - a heavier 2dp border.
 * Unselected options stay secondary: transparent fill, 1dp outline and a plain `onSurface` label.
 * Disabled options fade to 38% in either state, and keep the check, so the selection still reads.
 *
 * Announced to accessibility services as a radio button with a selected state.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun GraffuxChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        // FilterChip announces a checkbox-like toggle; a choice row is a radio group.
        modifier = modifier.semantics { role = Role.RadioButton },
        label = { Text(label) },
        leadingIcon = if (selected) {
            { CheckMark(tint = scheme.onPrimary) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = scheme.onSurface,
            selectedContainerColor = scheme.primary,
            selectedLabelColor = scheme.onPrimary,
            selectedLeadingIconColor = scheme.onPrimary,
            disabledContainerColor = Color.Transparent,
            disabledLabelColor = scheme.onSurface.copy(alpha = DISABLED_ALPHA),
            disabledSelectedContainerColor = scheme.primary.copy(alpha = DISABLED_ALPHA),
            disabledLeadingIconColor = scheme.onPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = scheme.outline,
            selectedBorderColor = scheme.primary,
            disabledBorderColor = scheme.outlineVariant,
            disabledSelectedBorderColor = scheme.primary.copy(alpha = DISABLED_ALPHA),
            borderWidth = 1.dp,
            selectedBorderWidth = 2.dp,
        ),
    )
}

private const val DISABLED_ALPHA = 0.38f

/** A drawn check mark, so the design module needs no icon-pack dependency. */
@Suppress("FunctionNaming", "MagicNumber") // Glyph geometry, as fractions of the box.
@Composable
private fun CheckMark(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.54f)
            lineTo(w * 0.42f, h * 0.76f)
            lineTo(w * 0.82f, h * 0.26f)
        }
        drawPath(
            path,
            color = tint,
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
