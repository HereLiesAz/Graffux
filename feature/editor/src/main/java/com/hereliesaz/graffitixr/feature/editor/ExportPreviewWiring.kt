package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.runtime.Composable
import com.hereliesaz.graffitixr.common.model.GestureSlot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The infinite-canvas camera: the layer stack, the artboard and the export frame's picture pan, zoom and rotate
 * together under this one transform (identity is a no-op). The editor's camera container and the tests share it.
 */
internal fun Modifier.viewportCamera(zoom: Float, offset: Offset, rotationDeg: Float): Modifier = graphicsLayer {
    scaleX = zoom
    scaleY = zoom
    rotationZ = rotationDeg
    translationX = offset.x
    translationY = offset.y
    transformOrigin = TransformOrigin(0f, 0f)
}

/**
 * The export frame's picture when [rect] is set (null: no export frame is up): placed inside the camera
 * container (see [viewportCamera]), so it follows the view and lands on what the exporter keeps.
 * [rect] is `exportFrameRect`.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ExportFrameLayer(rect: FloatArray?, modifier: Modifier = Modifier) {
    if (rect != null) ExportFrameOutline(rect, modifier)
}

/** The two-finger tap gestures (undo, redo, as mapped): not while the export frame is up. */
@Suppress("FunctionNaming")
@Composable
fun Modifier.historyGestures(
    gate: StrokeGate,
    exportPreview: Boolean,
    onGesture: (slot: GestureSlot, at: Offset) -> Unit,
): Modifier = multiFingerTaps(gate) { slot, at -> if (!exportPreview) onGesture(slot, at) }

/** The two-finger camera: off while [blocked] (the quick menu, or the export frame, is up). */
internal fun Modifier.cameraNavigationUnless(
    blocked: Boolean,
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
    onPanZoom: (pan: Offset, zoom: Float, centroid: Offset, rotation: Float) -> Unit,
): Modifier = if (blocked) this else canvasNavigation(onStart, onEnd, onPanZoom)
