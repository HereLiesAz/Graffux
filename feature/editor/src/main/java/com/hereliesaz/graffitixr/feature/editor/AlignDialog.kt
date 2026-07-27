// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/AlignDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/**
 * Window listing the six ways to align the active layer within the canvas. Replaces the flat
 * "Align" rail sub-item list (was 6 always-visible rail buttons); the rail now has a single
 * "Align" item that opens this instead.
 */
@Composable
fun AlignDialog(
    onAlign: (AlignMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = listOf(
        "Left" to AlignMode.LEFT,
        "Center" to AlignMode.H_CENTER,
        "Right" to AlignMode.RIGHT,
        "Top" to AlignMode.TOP,
        "Middle" to AlignMode.V_CENTER,
        "Bottom" to AlignMode.BOTTOM,
    )
    FloatingWindow(title = "Align", onDismiss = onDismiss) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(choices) { (label, mode) ->
                AzButton(
                    text = label,
                    onClick = { onAlign(mode); onDismiss() },
                    shape = AzButtonShape.RECTANGLE,
                )
            }
        }
    }
}
