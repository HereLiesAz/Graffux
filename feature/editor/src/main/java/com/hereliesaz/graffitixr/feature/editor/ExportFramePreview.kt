// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/ExportFramePreview.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.theme.ExportPreviewStrings
import com.hereliesaz.graffitixr.feature.editor.export.artboardRect

/** The smallest touch target (dp): Export and Cancel are never smaller. */
private val MinTouchTarget = 48.dp
private val Gap = 12.dp
private val Inset = 16.dp
private val ButtonCorner = 8.dp
private const val SCRIM_ALPHA = 0.6f
private const val OUTLINE_ALPHA = 0.95f
private const val OUTLINE_WIDTH_PX = 3f

/**
 * The export frame (spec/3d-space.md, "Export"): in a project with a 3D group the document boundary is not
 * drawn while editing, so before a document export runs this dims everything outside the artboard rectangle
 * (the rectangle [artboardRect] gives the exporter, so it is what the crop keeps) and offers Export or Cancel.
 * It sits over the canvas and takes every touch, so nothing underneath is edited while it is up; Back cancels.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun ExportFramePreview(
    documentWidth: Int,
    documentHeight: Int,
    strings: ExportPreviewStrings,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCancel)
    Box(
        modifier
            .fillMaxSize()
            .testTag("exportPreview")
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    ) {
        Canvas(
            Modifier.fillMaxSize().semantics { contentDescription = strings.frameDescription }
                .testTag("exportPreview.frame"),
        ) {
            val r = artboardRect(size.width.toInt(), size.height.toInt(), documentWidth, documentHeight)
            val scrim = Color.Black.copy(alpha = SCRIM_ALPHA)
            val (left, top, width) = r
            val height = r.last()
            val right = left + width
            val bottom = top + height
            drawRect(scrim, Offset.Zero, Size(size.width, top))
            drawRect(scrim, Offset(0f, bottom), Size(size.width, size.height - bottom))
            drawRect(scrim, Offset(0f, top), Size(left, height))
            drawRect(scrim, Offset(right, top), Size(size.width - right, height))
            drawRect(
                Color.White.copy(alpha = OUTLINE_ALPHA), Offset(left, top), Size(width, height),
                style = Stroke(OUTLINE_WIDTH_PX),
            )
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(Inset),
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
