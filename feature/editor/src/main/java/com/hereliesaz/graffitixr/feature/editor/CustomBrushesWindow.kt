// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/CustomBrushesWindow.kt
package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.data.brush.CustomBrush
import com.hereliesaz.graffitixr.design.components.ConfirmDialog
import com.hereliesaz.graffitixr.design.components.FullWidthButtonHeight
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.design.components.GraffuxButtonShape
import com.hereliesaz.graffitixr.design.components.SurfaceKind
import com.hereliesaz.graffitixr.design.components.ToolSurface

/**
 * A gallery/manager over every brush the user has saved in Brush Studio — [BrushStudioWindow]
 * itself only ever holds one brush at a time (the draft in hand), so seeing them all side by
 * side, picking one to paint with, or cleaning up old ones needed a window of its own. Mirrors
 * the azphalt Store's own card convention (a rounded surfaceVariant row per item, small
 * per-card action buttons — see [CardActionPadding]'s twin in StoreWindow.kt) rather than
 * inventing a new one.
 */
@Composable
fun CustomBrushesWindow(
    brushes: List<CustomBrush>,
    activeBrushName: String?,
    brushColor: Color,
    secondaryColor: Color = Color.Black,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
    texturedPreviews: Map<String, Bitmap> = emptyMap(),
    onExport: ((String) -> Unit)? = null,
    onExportSet: ((String) -> Unit)? = null,
    onExportLibrary: (() -> Unit)? = null,
    onDuplicate: ((String) -> Unit)? = null,
    onMove: ((String, String?) -> Unit)? = null,
    onRenameSet: ((String, String) -> Unit)? = null,
    onReorderSet: ((String, Boolean) -> Unit)? = null,
) {
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val pendingDelete = brushes.firstOrNull { it.id == pendingDeleteId }
    val sets = brushes.mapNotNull { it.importedSet }.distinctBy { it.id }
    var renameSetId by remember { mutableStateOf<String?>(null) }
    var setName by remember { mutableStateOf("") }
    val renaming = sets.firstOrNull { it.id == renameSetId }
    if (renaming != null && onRenameSet != null) {
        AlertDialog(
            onDismissRequest = { renameSetId = null },
            title = { Text("Rename brush set") },
            text = {
                OutlinedTextField(
                    value = setName,
                    onValueChange = { if (it.length <= 128) setName = it },
                    singleLine = true,
                    label = { Text("Set name") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = setName.isNotBlank(),
                    onClick = { onRenameSet(renaming.id, setName); renameSetId = null },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameSetId = null }) { Text("Cancel") } },
        )
    }
    var pendingMoveId by remember { mutableStateOf<String?>(null) }
    val pendingMove = brushes.firstOrNull { it.id == pendingMoveId }
    if (pendingMove != null && onMove != null) {
        AlertDialog(
            onDismissRequest = { pendingMoveId = null },
            title = { Text("Move brush") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    TextButton(
                        enabled = pendingMove.importedSet != null,
                        onClick = { onMove(pendingMove.id, null); pendingMoveId = null },
                    ) { Text("My Brushes") }
                    sets.forEach { set ->
                        TextButton(
                            enabled = pendingMove.importedSet?.id != set.id,
                            onClick = { onMove(pendingMove.id, set.id); pendingMoveId = null },
                        ) { Text(set.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pendingMoveId = null }) { Text("Cancel") } },
        )
    }
    if (pendingDelete != null) {
        ConfirmDialog(
            title = "Delete brush?",
            message = "\"${pendingDelete.brush.name}\" will be deleted permanently. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { onDelete(pendingDelete.id); pendingDeleteId = null },
            onDismiss = { pendingDeleteId = null },
        )
    }

    ToolSurface(kind = SurfaceKind.EDITOR, title = "My Brushes", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GraffuxButton(
                text = "New Brush",
                onClick = onNew,
                shape = GraffuxButtonShape.RECTANGLE,
                modifier = Modifier.fillMaxWidth().heightIn(min = FullWidthButtonHeight),
            )

            if (brushes.isEmpty()) {
                Text(
                    "Nothing saved yet. Brush Studio's Save Brush button adds one here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (brushes.isNotEmpty() && onExportLibrary != null) {
                GraffuxButton(
                    text = "Export all .brushlibrary",
                    onClick = onExportLibrary,
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier.fillMaxWidth().heightIn(min = FullWidthButtonHeight),
                )
            }
            brushes.forEachIndexed { index, custom ->
                val set = custom.importedSet
                if (set != null && brushes.getOrNull(index - 1)?.importedSet?.id != set.id) {
                    Text(set.name, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (onRenameSet != null) {
                            TextButton(onClick = { setName = set.name; renameSetId = set.id }) { Text("Rename") }
                        }
                        if (onReorderSet != null) {
                            val position = sets.indexOfFirst { it.id == set.id }
                            TextButton(enabled = position > 0, onClick = { onReorderSet(set.id, true) }) { Text("Up") }
                            TextButton(
                                enabled = position < sets.lastIndex,
                                onClick = { onReorderSet(set.id, false) },
                            ) { Text("Down") }
                        }
                    }
                    if (onExportSet != null) {
                        GraffuxButton(
                            text = "Export .brushset",
                            onClick = { onExportSet(set.id) },
                            shape = GraffuxButtonShape.RECTANGLE,
                            modifier = Modifier.fillMaxWidth().heightIn(min = FullWidthButtonHeight),
                        )
                    }
                }
                BrushCard(
                    custom = custom,
                    isActive = custom.brush.name == activeBrushName,
                    brushColor = brushColor,
                    secondaryColor = secondaryColor,
                    onSelect = { onSelect(custom.id) },
                    onEdit = { onEdit(custom.id) },
                    onDeleteRequest = { pendingDeleteId = custom.id },
                    texturedPreview = texturedPreviews[custom.id],
                    onExport = onExport?.let { export -> { export(custom.id) } },
                    onDuplicate = onDuplicate?.let { duplicate -> { duplicate(custom.id) } },
                    onMove = if (onMove != null) ({ pendingMoveId = custom.id }) else null,
                )
            }
        }
    }
}

@Composable
private fun BrushCard(
    custom: CustomBrush,
    isActive: Boolean,
    brushColor: Color,
    secondaryColor: Color,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDeleteRequest: () -> Unit,
    texturedPreview: Bitmap?,
    onExport: (() -> Unit)?,
    onDuplicate: (() -> Unit)?,
    onMove: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onSelect)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                custom.brush.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (isActive) {
                Text("In use", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (texturedPreview != null) {
            Image(texturedPreview.asImageBitmap(), contentDescription = null,
                colorFilter = ColorFilter.tint(brushColor), modifier = Modifier.fillMaxWidth().height(40.dp))
        } else if (custom.brush.shapePath == null && custom.brush.grainPath == null) {
            BrushPreview(custom.brush, brushColor, secondaryColor, height = 40.dp)
        }
        Spacer(Modifier.height(6.dp))
        // Equal heights: a label that wraps makes its button taller, and the other stretches to match.
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GraffuxButton(
                text = "Edit",
                onClick = onEdit,
                shape = GraffuxButtonShape.RECTANGLE,
                contentPadding = CardActionPadding,
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = FullWidthButtonHeight),
            )
            GraffuxButton(
                text = "Delete",
                onClick = onDeleteRequest,
                shape = GraffuxButtonShape.RECTANGLE,
                contentPadding = CardActionPadding,
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = FullWidthButtonHeight),
            )
        }
        if (onDuplicate != null || onMove != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onDuplicate != null) TextButton(onClick = onDuplicate, modifier = Modifier.weight(1f)) {
                    Text("Duplicate")
                }
                if (onMove != null) TextButton(onClick = onMove, modifier = Modifier.weight(1f)) {
                    Text("Move")
                }
            }
        }
        if (onExport != null) {
            Spacer(Modifier.height(6.dp))
            GraffuxButton(
                text = "Export .brush",
                onClick = onExport,
                shape = GraffuxButtonShape.RECTANGLE,
                modifier = Modifier.fillMaxWidth().heightIn(min = FullWidthButtonHeight),
            )
        }
    }
}

/** Matches StoreWindow's own [CardActionPadding] — a repeated per-card action button reads as
 *  oversized at AzButton's default padding. Kept as its own copy rather than exporting StoreWindow's:
 *  the two files' cards aren't otherwise coupled, and this one is free to diverge later. */
private val CardActionPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
