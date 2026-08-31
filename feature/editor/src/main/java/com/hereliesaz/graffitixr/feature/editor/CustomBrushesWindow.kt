// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/CustomBrushesWindow.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.data.brush.CustomBrush
import com.hereliesaz.graffitixr.design.components.AzFullWidthButtonHeight
import com.hereliesaz.graffitixr.design.components.ConfirmDialog
import com.hereliesaz.graffitixr.design.components.FloatingWindow

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
) {
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val pendingDelete = brushes.firstOrNull { it.id == pendingDeleteId }
    if (pendingDelete != null) {
        ConfirmDialog(
            title = "Delete brush?",
            message = "\"${pendingDelete.brush.name}\" will be deleted permanently. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { onDelete(pendingDelete.id); pendingDeleteId = null },
            onDismiss = { pendingDeleteId = null },
        )
    }

    FloatingWindow(title = "My Brushes", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AzButton(
                text = "New Brush",
                onClick = onNew,
                shape = AzButtonShape.RECTANGLE,
                modifier = Modifier.fillMaxWidth().height(AzFullWidthButtonHeight),
            )

            if (brushes.isEmpty()) {
                Text(
                    "Nothing saved yet. Brush Studio's Save Brush button adds one here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }

            brushes.forEach { custom ->
                BrushCard(
                    custom = custom,
                    isActive = custom.brush.name == activeBrushName,
                    brushColor = brushColor,
                    secondaryColor = secondaryColor,
                    onSelect = { onSelect(custom.id) },
                    onEdit = { onEdit(custom.id) },
                    onDeleteRequest = { pendingDeleteId = custom.id },
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
        BrushPreview(custom.brush, brushColor, secondaryColor, height = 40.dp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AzButton(
                text = "Edit",
                onClick = onEdit,
                shape = AzButtonShape.RECTANGLE,
                contentPadding = CardActionPadding,
                modifier = Modifier.weight(1f).height(AzFullWidthButtonHeight),
            )
            AzButton(
                text = "Delete",
                onClick = onDeleteRequest,
                shape = AzButtonShape.RECTANGLE,
                contentPadding = CardActionPadding,
                modifier = Modifier.weight(1f).height(AzFullWidthButtonHeight),
            )
        }
    }
}

/** Matches StoreWindow's own [CardActionPadding] — a repeated per-card action button reads as
 *  oversized at AzButton's default padding. Kept as its own copy rather than exporting StoreWindow's:
 *  the two files' cards aren't otherwise coupled, and this one is free to diverge later. */
private val CardActionPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
