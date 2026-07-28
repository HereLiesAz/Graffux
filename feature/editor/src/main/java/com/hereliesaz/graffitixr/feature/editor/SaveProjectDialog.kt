// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/SaveProjectDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.model.ProjectFile
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/**
 * Save: name the project, then choose where the `.fux` goes.
 *
 * Naming happens here rather than being left to the system file picker's own filename field
 * because the name is the *project's* name, not just the file's — it's what the gallery shows and
 * what the next save offers back. Confirming hands the sanitised filename to the picker, so what
 * the user typed is what they see there.
 *
 * The work is already on disk before this dialog is ever opened (autosave), so this is never the
 * thing standing between the user and losing work — it's for putting a copy somewhere they choose.
 */
@Composable
fun SaveProjectDialog(
    currentName: String,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    val fileName = ProjectFile.suggestedFileName(name)

    FloatingWindow(title = "Save project", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Project name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Shows what the file will actually be called, so the sanitising rules (an illegal
            // character dropped, a blank name becoming "Untitled") are visible before the picker
            // opens rather than being a surprise afterwards.
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Your work is saved automatically as you go. This saves a copy you can move, " +
                    "share, or open on another device.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
            AzButton(
                text = "Choose location…",
                onClick = { onConfirm(ProjectFile.sanitizeName(name)) },
                shape = AzButtonShape.RECTANGLE,
            )
        }
    }
}
