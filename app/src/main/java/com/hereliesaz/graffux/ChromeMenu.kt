@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.hereliesaz.graffitixr.design.theme.Amber
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One row of a chrome menu (the logo menu, a layer's menu, the brush's long-press menu). */
internal sealed interface MenuRow {
    /** A tap that does something and closes the menu. */
    data class Action(val text: String, val onClick: () -> Unit, val destructive: Boolean = false) : MenuRow

    /** An on/off property that acts and closes, read to TalkBack as a checkbox with its state. */
    data class Check(val label: String, val checked: Boolean, val onClick: () -> Unit) : MenuRow

    /** A section's heading: not tappable, read as a heading. */
    data class Header(val text: String) : MenuRow

    /** A text field, applied on Done; closes the menu. */
    data class Input(val hint: String, val initialValue: String, val onSubmit: (String) -> Unit) : MenuRow

    /** An on/off that stays open so several can be flipped in one visit. */
    data class Toggle(val label: String, val checked: Boolean, val onToggle: (Boolean) -> Unit) : MenuRow

    /** A tap that changes what the menu shows and keeps it open (View…, ‹ Back). */
    data class Page(val text: String, val onClick: () -> Unit) : MenuRow

    data object Divider : MenuRow
}

/** Collects [MenuRow]s; the receiver of every menu-building function in the app. */
internal class MenuBuilder {
    val rows = mutableListOf<MenuRow>()

    fun listItem(text: String, onClick: () -> Unit) {
        rows += MenuRow.Action(text, onClick)
    }

    /** An action that removes or wipes something: drawn apart, in the warning colour. */
    fun destructiveItem(text: String, onClick: () -> Unit) {
        rows += MenuRow.Action(text, onClick, destructive = true)
    }

    fun check(label: String, checked: Boolean, onClick: () -> Unit) {
        rows += MenuRow.Check(label, checked, onClick)
    }

    /** Starts a section: a divider (unless it is the first row) and a heading. */
    fun section(title: String) {
        if (rows.isNotEmpty()) rows += MenuRow.Divider
        rows += MenuRow.Header(title)
    }

    fun inputItem(hint: String, initialValue: String, onSubmit: (String) -> Unit) {
        rows += MenuRow.Input(hint, initialValue, onSubmit)
    }

    fun toggle(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        rows += MenuRow.Toggle(label, checked, onToggle)
    }

    fun page(text: String, onClick: () -> Unit) {
        rows += MenuRow.Page(text, onClick)
    }

    fun divider() {
        rows += MenuRow.Divider
    }
}

internal fun buildMenu(block: MenuBuilder.() -> Unit): List<MenuRow> = MenuBuilder().apply(block).rows

private val MenuShape = RoundedCornerShape(14.dp)

/**
 * A menu panel: [rows] under an optional [title], scrolling past [maxHeight]. No scrim and no
 * tap-catcher: a touch on the canvas closes it (see [CanvasTouch]), and so does any row that acts.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun MenuPanel(
    rows: List<MenuRow>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    maxHeight: Dp = 520.dp,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .widthIn(min = 200.dp, max = 280.dp)
            .heightIn(max = maxHeight)
            .clip(MenuShape)
            .background(RailFill.copy(alpha = 0.97f))
            .border(1.dp, RailBorder, MenuShape)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp)
            .testTag("chrome.menu"),
    ) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        rows.forEach { row ->
            when (row) {
                is MenuRow.Action -> MenuText(
                    row.text,
                    color = if (row.destructive) Amber else null,
                ) { onDismiss(); row.onClick() }
                is MenuRow.Check -> MenuCheck(row) { onDismiss(); row.onClick() }
                is MenuRow.Header -> Text(
                    row.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics { heading() },
                )
                is MenuRow.Page -> MenuText(row.text, onClick = row.onClick)
                is MenuRow.Toggle -> MenuText(
                    "${row.label}: ${if (row.checked) "shown" else "hidden"}",
                    trailing = if (row.checked) "●" else "○",
                ) { row.onToggle(!row.checked) }
                is MenuRow.Input -> MenuInput(row) { onDismiss() }
                MenuRow.Divider -> Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(RailBorder),
                )
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun MenuText(text: String, trailing: String? = null, color: Color? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 48dp: the touch-target minimum, and room for the text at larger font scales.
            .heightIn(min = MenuRowHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = color ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun MenuCheck(row: MenuRow.Check, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MenuRowHeight)
            .toggleable(value = row.checked, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            row.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // Drawn only; TalkBack reads the checkbox state from toggleable.
        Text(
            if (row.checked) "On" else "Off",
            style = MaterialTheme.typography.labelMedium,
            color = if (row.checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 8.dp).clearAndSetSemantics { },
        )
    }
}

private val MenuRowHeight = 48.dp

@Suppress("FunctionNaming")
@Composable
private fun MenuInput(row: MenuRow.Input, onDone: () -> Unit) {
    var value by remember(row.initialValue) { mutableStateOf(row.initialValue) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(row.hint, style = MaterialTheme.typography.labelSmall, color = colors.onSurface.copy(alpha = 0.6f))
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { row.onSubmit(value); onDone() }),
            modifier = Modifier
                .padding(top = 4.dp)
                .width(240.dp)
                .border(1.dp, RailBorder, RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.3f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
