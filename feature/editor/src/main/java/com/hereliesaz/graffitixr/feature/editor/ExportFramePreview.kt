// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/ExportFramePreview.kt
// MagicNumber: `rect` is the [left, top, width, height] array `exportFrameRect` / `artboardRect` return.
@file:Suppress("MagicNumber")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.hereliesaz.graffitixr.design.theme.ExportPreviewStrings

/** The smallest touch target (dp): Export and Cancel are never smaller. */
private val MinTouchTarget = 48.dp
private val Gap = 12.dp
private val Inset = 16.dp
private val ButtonCorner = 8.dp
private const val SCRIM_ALPHA = 0.6f
private const val BACKING_ALPHA = 0.85f
private const val OUTLINE_ALPHA = 0.95f
private const val OUTLINE_WIDTH_PX = 3f
private const val REACH = 100_000f
private const val REACH_SPAN = 200_000f

/**
 * The export frame's picture (spec/3d-space.md, "Export"): dims everything outside [rect] and outlines it.
 * [rect] is `[left, top, width, height]` in the canvas's own coordinates, from `exportFrameRect`, so this
 * is placed **inside the camera container**, where the layers are drawn: the exporter ignores pan, zoom and
 * rotation and keeps that fixed rectangle of the layers' world, and drawn in the same transformed space the
 * outline lands on exactly that part of the artwork whatever the view is. Non-interactive.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ExportFrameOutline(rect: FloatArray, modifier: Modifier = Modifier) {
    Canvas(modifier.testTag("exportPreview.frame")) {
        val scrim = Color.Black.copy(alpha = SCRIM_ALPHA)
        val left = rect[0]
        val top = rect[1]
        val right = left + rect[2]
        val bottom = top + rect[3]
        drawRect(scrim, Offset(-REACH, -REACH), Size(REACH_SPAN, top + REACH))
        drawRect(scrim, Offset(-REACH, bottom), Size(REACH_SPAN, REACH))
        drawRect(scrim, Offset(-REACH, top), Size(left + REACH, rect[3]))
        drawRect(scrim, Offset(right, top), Size(REACH, rect[3]))
        drawRect(
            Color.White.copy(alpha = OUTLINE_ALPHA), Offset(left, top), Size(rect[2], rect[3]),
            style = Stroke(OUTLINE_WIDTH_PX),
        )
    }
}

/**
 * The export frame's Export / Cancel and its message, in a dialog window: a window of its own is what makes
 * it modal. Touches, the two-finger camera and tap gestures, and TalkBack cannot reach the editor or the host's
 * chrome beneath, and Back cancels. Kept clear of the status bar, the display cutout and the navigation bar
 * (`safeDrawing`), and the message sits on a dark backing so it reads over any canvas.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ExportFrameControls(
    strings: ExportPreviewStrings,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // No window dim: the scrim is the outline's, and follows the camera.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().testTag("exportPreview")) {
            Column(
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(Inset)
                    .background(Color.Black.copy(alpha = BACKING_ALPHA), RoundedCornerShape(ButtonCorner))
                    .padding(Inset),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Gap),
            ) {
                Text(
                    strings.message,
                    color = Color.White,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(ButtonCorner),
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag("exportPreview.cancel")
                            .semantics { contentDescription = strings.cancelDescription },
                    ) { Text(strings.cancel) }
                    Button(
                        onClick = onConfirm,
                        shape = RoundedCornerShape(ButtonCorner),
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag("exportPreview.confirm")
                            .semantics { contentDescription = strings.confirmDescription },
                    ) { Text(strings.confirm) }
                }
            }
        }
    }
}
