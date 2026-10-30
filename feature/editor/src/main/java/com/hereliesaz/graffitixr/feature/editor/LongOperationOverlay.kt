@file:Suppress("FunctionNaming", "MagicNumber")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings

/**
 * The overlay while a long operation runs (an export, a poster PDF, finding the subject, a
 * flatten): a scrim that swallows touches, so nothing reaches the canvas meanwhile; a spinner with
 * what is happening under it, announced by TalkBack; and Cancel when the operation can stop.
 */
@Composable
fun LongOperationOverlay(label: String?, onCancel: (() -> Unit)?, modifier: Modifier = Modifier) {
    val strings = rememberAppStrings().messages
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            // Every touch stops here: the canvas under a running export must not take strokes.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .testTag("editor.progress"),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .background(Color(0xF0161618), RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp))
            Text(
                label ?: strings.working,
                color = Color.White,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (onCancel != null) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("editor.progress.cancel"),
                ) {
                    Text(strings.cancel)
                }
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.45f
