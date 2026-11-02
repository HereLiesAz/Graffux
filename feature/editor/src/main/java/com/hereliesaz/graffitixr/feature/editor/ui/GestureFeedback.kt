// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/ui/GestureFeedback.kt
package com.hereliesaz.graffitixr.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.RotationAxis
import kotlinx.coroutines.delay

/**
 * A brief "Axis:" confirmation after the rotation axis changes (double-tap or the rail's cycler).
 */
@Composable
fun GestureFeedback(state: EditorUiState, modifier: Modifier = Modifier, onFeedbackShown: () -> Unit = {}) {
    // A double-tap's axis change shows briefly, then clears itself.
    LaunchedEffect(state.showRotationAxisFeedback, state.activeRotationAxis) {
        if (state.showRotationAxisFeedback) {
            delay(AXIS_FEEDBACK_MS)
            onFeedbackShown()
        }
    }
    // Only a brief confirmation of an axis change: the rail's axis cycler is the persistent cue.
    val showFeedback = state.showRotationAxisFeedback
    val activeLayer = state.layers.find { it.id == state.activeLayerId }
    val isLocked = activeLayer?.isImageLocked ?: false

    if (showFeedback && !isLocked) {
        val axisLabel = when (state.activeRotationAxis) {
            RotationAxis.X -> "Axis: X (Cyan)"
            RotationAxis.Y -> "Axis: Y (Pink)"
            RotationAxis.Z -> "Axis: Z (Green)"
        }

        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .background(Color.DarkGray, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = axisLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** How long a double-tap's axis change stays on screen. */
private const val AXIS_FEEDBACK_MS = 1200L
