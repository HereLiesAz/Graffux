@file:Suppress("MagicNumber", "FunctionNaming", "LongParameterList", "MatchingDeclarationName", "MaxLineLength")

package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.design.theme.RobotoCondensedFamily
import com.hereliesaz.graffitixr.design.theme.RobotoMonoFamily
import kotlin.math.roundToInt

/*
 * The controls the design tools are built from, measured off the mockup's "Design tools" boards:
 * pill chips, the numeric fields, and the label + controls rows. Each comes in the two densities
 * the mockup draws: the phone's bottom sheet (14sp rows, 44dp tall, 96dp label column) and the
 * tablet's 320dp side panel (13sp, 72dp label column, 32dp fields).
 */

/** The design tools' colours. */
internal object DesignInk {
    val Ink = Color(0xFFEDEDED)
    val Muted = Color(0xFFB4B4BA)
    val Faint = Color(0xFF8A8A90)
    val Line = Color(0xFF2A2A2E)
    val Swatch = Color(0xFF3A3A3F)
    val Canvas = Color(0xFF0E0E0F)
    val Field = Color(0xFF161618)
    val Panel = Color(0xFF121214)
}

/** Which of the two layouts a control is drawn for. */
internal enum class DesignDensity(val rowLabelWidth: Dp, val fontSize: Int, val rowHeight: Dp) {
    SHEET(96.dp, 14, 44.dp),
    PANEL(72.dp, 13, 40.dp),
}

/** A pill chip (the mockup's 32dp buttons): ink-filled when [on], outlined otherwise. */
@Composable
internal fun DesignChip(label: String, on: Boolean, tag: String, height: Dp = 32.dp, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .then(
                if (on) Modifier.background(DesignInk.Ink) else Modifier.border(1.dp, DesignInk.Line, RoundedCornerShape(height / 2)),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = if (height > 32.dp) 14.dp else 10.dp)
            .testTag(tag),
    ) {
        Text(
            label,
            color = if (on) DesignInk.Canvas else DesignInk.Ink,
            fontSize = if (height > 32.dp) 14.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = RobotoCondensedFamily,
            maxLines = 1,
        )
    }
}

/** A row of chips, one of which is [selected]. */
@Composable
internal fun <T> DesignChoice(
    options: List<Pair<T, String>>,
    selected: T?,
    tagPrefix: String,
    onPick: (T) -> Unit,
) {
    // Scrolls sideways when the chips are wider than the row (Pin X / Pin Y on a phone).
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        options.forEach { (value, label) ->
            DesignChip(label, on = value == selected, tag = "$tagPrefix.${label.lowercase()}", onClick = { onPick(value) })
        }
    }
}

/** A label and its controls on one line. */
@Composable
internal fun DesignRow(label: String, density: DesignDensity, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(density.rowHeight)
            .padding(horizontal = if (density == DesignDensity.SHEET) 20.dp else 0.dp),
    ) {
        Text(
            label,
            color = if (density == DesignDensity.SHEET) DesignInk.Ink else DesignInk.Muted,
            fontSize = density.fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = RobotoCondensedFamily,
            maxLines = 1,
            modifier = Modifier.width(density.rowLabelWidth),
        )
        content()
    }
}

/** A section's heading in the side panel, with an optional action at its end ("+", "Remove"). */
@Composable
internal fun DesignSectionHeader(title: String, action: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(24.dp)) {
        Text(
            title,
            color = DesignInk.Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = RobotoCondensedFamily,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(32.dp)
                    .clickable(role = Role.Button, onClick = onAction)
                    .semantics { contentDescription = actionLabel ?: action }
                    .padding(horizontal = 8.dp)
                    .testTag("design.section.${title.lowercase()}.action"),
            ) {
                Text(action, color = DesignInk.Muted, fontSize = if (action.length == 1) 18.sp else 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** One side-panel section: a rule above, 12 x 16 padding, its rows 8dp apart. */
@Composable
internal fun DesignPanelSection(tag: String, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawTopRule()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
    ) { content() }
}

private fun Modifier.drawTopRule(): Modifier = drawBehind {
    drawLine(DesignInk.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
}

/** How a number reads in a field: whole numbers bare, others to one place. */
internal fun designNumber(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return if (rounded == rounded.roundToInt().toFloat()) rounded.roundToInt().toString() else rounded.toString()
}

/**
 * A numeric field: [label] then the value in mono. Typing does nothing until Done or the field
 * loses focus, then [onCommit] gets the parsed number (an entry that does not parse is dropped and
 * the field shows the value again).
 */
@Composable
internal fun DesignNumberField(
    label: String,
    value: Float?,
    density: DesignDensity,
    tag: String,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = value != null,
) {
    val shown = value?.let(::designNumber) ?: "–"
    var text by remember(shown) { mutableStateOf(shown) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(shown) { if (!focused) text = shown }
    // Done then the focus leaving would otherwise commit the same entry twice (two undo steps).
    var committed by remember(shown) { mutableStateOf<String?>(null) }
    fun commit() {
        val entry = text.trim()
        if (entry == committed) return
        val parsed = entry.replace(',', '.').toFloatOrNull()
        if (parsed != null && parsed != value) {
            committed = entry
            onCommit(parsed)
        } else {
            text = shown
        }
    }
    val field: @Composable (Modifier) -> Unit = { m ->
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = DesignInk.Ink,
                fontFamily = RobotoMonoFamily,
                fontSize = if (density == DesignDensity.SHEET) 13.sp else 12.sp,
            ),
            cursorBrush = SolidColor(DesignInk.Ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
            modifier = m
                .onFocusChanged { state ->
                    if (focused && !state.isFocused) commit()
                    focused = state.isFocused
                }
                .semantics { contentDescription = label }
                .testTag(tag),
        )
    }
    if (density == DesignDensity.SHEET) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = modifier
                .height(40.dp)
                .border(1.dp, DesignInk.Line, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp),
        ) {
            Text(label, color = DesignInk.Faint, fontSize = 12.sp, fontFamily = RobotoCondensedFamily)
            field(Modifier.weight(1f))
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
            Text(label, color = DesignInk.Faint, fontSize = 11.sp, fontFamily = RobotoMonoFamily, modifier = Modifier.width(18.dp))
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .background(DesignInk.Field, RoundedCornerShape(8.dp))
                    .border(1.dp, DesignInk.Line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp),
            ) { field(Modifier.fillMaxWidth()) }
        }
    }
}

/** A colour swatch, [size] square with the mockup's thin outline. */
@Composable
internal fun DesignSwatch(argb: Long?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(argb?.let { Color(it.toInt()) } ?: Color.Transparent, RoundedCornerShape(if (size > 24.dp) 6.dp else 4.dp))
            .border(1.dp, DesignInk.Swatch, RoundedCornerShape(if (size > 24.dp) 6.dp else 4.dp)),
    )
}

/** `#RRGGBB` (with the alpha first when it is not opaque). */
internal fun designHex(argb: Long): String {
    val v = argb and 0xFFFFFFFFL
    val alpha = (v ushr 24).toInt()
    return if (alpha == 0xFF) "#%06X".format(v and 0xFFFFFF) else "#%08X".format(v)
}
