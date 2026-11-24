@file:Suppress("FunctionNaming")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.util.NamedPalette
import com.hereliesaz.graffitixr.common.util.NamedPaletteBook
import com.hereliesaz.graffitixr.common.util.PaletteEdit
import com.hereliesaz.graffitixr.design.components.ConfirmDialog

/** Named palette management using the same scrolling ToolSurface body as the other picker pages. */
@Composable
internal fun NamedPalettePanel(book: NamedPaletteBook?, current: Color, onPick: (Color) -> Unit,
    onEdit: (PaletteEdit) -> Unit, legacy: @Composable () -> Unit) {
    var cards by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    if (book == null) {
        Text("Named palettes are unavailable", style = MaterialTheme.typography.bodyMedium)
        legacy()
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = !cards, onClick = { cards = false }, label = { Text("Compact") })
        FilterChip(selected = cards, onClick = { cards = true }, label = { Text("Cards") })
    }
    if (creating) {
        PaletteNameField(newName, { newName = it }, "Create", {
            onEdit(PaletteEdit.Create(newName)); creating = false
        }, { creating = false })
    } else TextButton(enabled = book.palettes.size < NamedPaletteBook.MAX_PALETTES,
        onClick = { newName = ""; creating = true }, modifier = Modifier.testTag("touchColor.palette.new")) {
        Text("New palette")
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(8.dp)) {
        TextButton(onClick = { onEdit(PaletteEdit.Select(null)) }, enabled = book.activeId != null,
            modifier = Modifier.testTag("touchColor.palette.legacy.active")) {
            Text(if (book.activeId == null) "Default · Saved colours" else "Set saved colours active")
        }
        legacy()
    }
    book.palettes.forEach { palette ->
        key(palette.id) { NamedPaletteCard(palette, book.activeId == palette.id, book.palettes.size < NamedPaletteBook.MAX_PALETTES,
            current, onPick, onEdit, cards) }
    }
}

@Composable
private fun NamedPaletteCard(palette: NamedPalette, active: Boolean, canDuplicate: Boolean, current: Color,
    onPick: (Color) -> Unit, onEdit: (PaletteEdit) -> Unit, cards: Boolean) {
    var renaming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(palette.name) }
    var confirming by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showSlots by remember(active) { mutableStateOf(active) }
    if (confirming) ConfirmDialog(
        title = "Delete palette?",
        message = "Delete \"${palette.name}\" and its swatches?",
        confirmLabel = "Delete",
        onConfirm = { confirming = false; onEdit(PaletteEdit.Delete(palette.id)) },
        onDismiss = { confirming = false },
    )
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Text(palette.name, style = MaterialTheme.typography.titleSmall)
        if (active) Text("Active palette", style = MaterialTheme.typography.labelSmall)
        else TextButton(onClick = { onEdit(PaletteEdit.Select(palette.id)) },
            modifier = Modifier.testTag("touchColor.palette.active.${palette.id}")) { Text("Set active") }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide options" else "Palette options") }
        if (expanded) {
            if (renaming) PaletteNameField(name, { name = it }, "Rename", {
                onEdit(PaletteEdit.Rename(palette.id, name)); renaming = false
            }, { renaming = false })
            else TextButton(onClick = { name = palette.name; renaming = true }) { Text("Rename") }
            TextButton(enabled = canDuplicate, onClick = { onEdit(PaletteEdit.Duplicate(palette.id)) }) { Text("Duplicate") }
            TextButton(onClick = { confirming = true }) { Text("Delete") }
        }
        TextButton(onClick = { showSlots = !showSlots }) { Text(if (showSlots) "Hide swatches" else "Show swatches") }
        if (showSlots) NamedPaletteSlots(palette, current, onPick, onEdit, cards)
    }
}

/** Valid names are bounded by the persisted collection contract; no silent truncation. */
@Composable
private fun PaletteNameField(name: String, onName: (String) -> Unit, action: String, onDone: () -> Unit, onCancel: () -> Unit) {
    OutlinedTextField(value = name, onValueChange = { if (it.length <= NamedPalette.MAX_NAME) onName(it) },
        singleLine = true, label = { Text("Palette name") }, modifier = Modifier.fillMaxWidth().testTag("touchColor.palette.name"))
    Row {
        TextButton(onClick = onCancel) { Text("Cancel") }
        TextButton(enabled = name.isNotBlank(), onClick = onDone) { Text(action) }
    }
}

/** Each slot retains its own index. Empty slots add; filled slots pick or clear on long press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NamedPaletteSlots(palette: NamedPalette, current: Color, onPick: (Color) -> Unit,
    onEdit: (PaletteEdit) -> Unit, cards: Boolean) {
    val touch = if (cards) 80.dp else 48.dp
    val face = if (cards) 64.dp else 36.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = recentGridColumns(maxWidth, touch)
        Column {
            palette.slots.withIndex().chunked(columns).forEach { row ->
                Row {
                    row.forEach { (index, argb) ->
                        val color = argb?.let { Color(it) }
                        val description = if (color == null) "Empty slot ${index + 1}, add current colour"
                            else "Slot ${index + 1}, ${hexOf(color)}"
                        Box(Modifier.size(touch).combinedClickable(
                            role = Role.Button,
                            onClick = {
                                if (color == null) onEdit(PaletteEdit.SetSlot(palette.id, index, current.toArgb()))
                                else onPick(color)
                            },
                            onLongClickLabel = if (color == null) null else "Clear swatch",
                            onLongClick = if (color == null) null else ({ onEdit(PaletteEdit.SetSlot(palette.id, index, null)) }),
                        ).semantics { contentDescription = description }
                            .testTag("touchColor.palette.${palette.id}.slot.$index"), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(face).background(color ?: MaterialTheme.colorScheme.surface, RoundedCornerShape(5.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                                if (color == null) Text("+", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
