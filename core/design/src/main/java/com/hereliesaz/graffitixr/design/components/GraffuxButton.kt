package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * How a [GraffuxButton] is outlined. RECTANGLE and SQUARE are bordered (the button opens or confirms
 * something); NONE and NONE_SQUARE are borderless (it just does something).
 */
enum class GraffuxButtonShape { RECTANGLE, SQUARE, NONE, NONE_SQUARE }

/**
 * Minimum height for a full-width [GraffuxButton] in a card action row, so a row of them lines up with
 * the card's own text rather than standing taller than it. A minimum, never a fixed height: at a large
 * font scale the label needs more room, and a fixed 32dp cut it off (use `heightIn(min = ...)`).
 */
val FullWidthButtonHeight = 32.dp

private val ButtonShape = RoundedCornerShape(2.dp)
private val DefaultPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)

/**
 * The app's text button: a square-cornered outlined label at the ordinary label size. Every dialog
 * and window in the app uses it, so one place decides what a button looks like.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun GraffuxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: GraffuxButtonShape = GraffuxButtonShape.RECTANGLE,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
    contentPadding: PaddingValues = DefaultPadding,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val ink = color.takeOrElse { onSurface }
    val bordered = shape == GraffuxButtonShape.RECTANGLE || shape == GraffuxButtonShape.SQUARE
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
        enabled = enabled,
        shape = ButtonShape,
        border = if (bordered) BorderStroke(1.dp, ink.copy(alpha = if (enabled) 1f else DISABLED_ALPHA)) else null,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = ink,
            disabledContentColor = ink.copy(alpha = DISABLED_ALPHA),
        ),
        contentPadding = contentPadding,
    ) {
        // Two lines, not one: at a large font scale or in a narrow dialog a longer label wraps rather than
        // being cut to an ellipsis, and the button grows to hold it.
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = MAX_LABEL_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val DISABLED_ALPHA = 0.38f
private const val MAX_LABEL_LINES = 2
