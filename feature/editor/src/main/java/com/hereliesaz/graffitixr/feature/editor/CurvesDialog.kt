package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/**
 * No separate Apply step: dragging a curve point pushes the tone curve to the view model as soon
 * as the drag ends (not every intermediate point — a full-image tone-curve pass on every pixel of
 * finger movement is real cost this dialog doesn't need to pay to stay live). Close the window
 * when the result looks right; there's nothing left to confirm.
 */
@Composable
fun CurvesDialog(
    onDismissRequest: () -> Unit,
    onCurvesApplied: (List<Offset>) -> Unit
) {
    var points by remember {
        mutableStateOf(listOf(Offset(0f, 0f), Offset(1f, 1f)))
    }

    FloatingWindow(title = "Curves", onDismiss = onDismissRequest) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                CurvesAdjustment(
                    points = points,
                    onPointsChanged = { newPoints -> points = newPoints },
                    onDragEnd = { onCurvesApplied(points) }
                )
            }
        }
    }
}