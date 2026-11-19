@file:Suppress("FunctionNaming")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay

/** How long an applied hex value rests before it counts as committed, so `#FF00A` on the way is not kept. */
internal const val HEX_COMMIT_DELAY_MS = 700L

/**
 * How long a typed three-digit value rests before it is applied. `FF00AA` passes through `FF0`, which is a
 * colour of its own (`#FFFF00`); applying it would flash the brush yellow mid-word. Six and eight digits are
 * never a prefix of a longer value the user means, so they apply at once.
 */
internal const val HEX_SHORT_REST_MS = 450L

private const val SHORT_HEX_DIGITS = 3

/** The digits in [text], without whitespace or a leading `#`. */
internal fun hexDigitCount(text: String): Int = text.trim().removePrefix("#").length

/** How long a valid typed [text] waits before it is applied: only the three-digit shorthand waits. */
internal fun hexApplyDelayMs(text: String): Long =
    if (hexDigitCount(text) == SHORT_HEX_DIGITS) HEX_SHORT_REST_MS else 0L

/** A typed colour on its way to being recorded; [applied] says whether it reached the brush yet. */
private class PendingHex(val colour: Color, var applied: Boolean)

/**
 * The hex field. A valid value applies live (three digits after [HEX_SHORT_REST_MS], six or eight at once),
 * and is committed once it rests [HEX_COMMIT_DELAY_MS], so a value typed and left behind is remembered. When the
 * sheet closes first, whatever was typed and valid is applied and committed there, with the latest value.
 * There is no Apply button.
 */
@Composable
internal fun HexColourField(
    selected: Color,
    onApply: (Color) -> Unit,
    onCommit: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedNow by rememberUpdatedState(selected)
    val apply by rememberUpdatedState(onApply)
    val commit by rememberUpdatedState(onCommit)
    var hex by remember { mutableStateOf(hexOf(selected)) }
    // True once the field was typed in: only a typed value is committed by resting, never one the sheet filled in.
    var typed by remember { mutableStateOf(false) }
    val pending = remember { arrayOfNulls<PendingHex>(1) }
    val colour = hexFieldColor(hex, selected)
    val invalid = hex.isNotBlank() && colour == null

    // Another path changed the colour (a recent swatch, a drag): the field shows it, unless it already names
    // that colour (typing "abc" must not turn into "#AABBCC" under the finger).
    LaunchedEffect(selected) {
        val named = hexFieldColor(hex, selected)
        if (named == null || named.toArgb() != selected.toArgb()) {
            hex = hexOf(selected)
            typed = false
        }
    }
    LaunchedEffect(colour) {
        // A value that never reached the brush and no longer parses is not what the field says: forget it.
        if (pending[0]?.applied != true) pending[0] = null
        val value = colour?.takeIf { typed } ?: return@LaunchedEffect
        val waiting = PendingHex(value, applied = false).also { pending[0] = it }
        delay(hexApplyDelayMs(hex))
        if (value.toArgb() != selectedNow.toArgb()) apply(value)
        waiting.applied = true
        delay(HEX_COMMIT_DELAY_MS)
        commit(value)
        pending[0] = null
    }
    // Closing the sheet inside the rest: the value is applied and recorded now rather than lost.
    DisposableEffect(Unit) {
        onDispose {
            pending[0]?.let { left ->
                if (!left.applied) apply(left.colour)
                commit(left.colour)
            }
        }
    }

    val invalidText = stringResource(R.string.colour_hex_invalid)
    OutlinedTextField(
        value = hex,
        onValueChange = {
            hex = it
            typed = true
        },
        label = { Text(stringResource(R.string.colour_hex_label)) },
        isError = invalid,
        supportingText = if (invalid) ({ Text(invalidText) }) else null,
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (invalid) error(invalidText) }
            .testTag("touchColor.hex"),
    )
}
