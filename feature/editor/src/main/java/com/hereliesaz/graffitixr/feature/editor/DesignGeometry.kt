@file:Suppress("MaxLineLength", "ReturnCount", "LongParameterList")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.FrameOps
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Rect
import com.hereliesaz.graffitixr.common.model.isFrame

/**
 * The properties panel's geometry: where a layer sits in world space (the canvas centre at 0, 0),
 * as one axis-aligned box, for any kind of layer. A frame reports its own box; anything else is
 * measured by [CanvasHitTest] (its shapes, or its painted pixels fitted to the canvas) after its
 * parents' transforms are folded in, so a layer inside a frame reports where it really is.
 */
object DesignGeometry {

    /** [layerId]'s box, or null when it has nothing to measure (an empty group, a blank layer). */
    fun box(layers: List<Layer>, layerId: String, canvasWidth: Float, canvasHeight: Float): Rect? {
        val layer = layers.firstOrNull { it.id == layerId } ?: return null
        if (layer.isFrame) return FrameOps.boxOf(layers, layer)
        val flat = FrameOps.flattenTransforms(layers).firstOrNull { it.id == layerId } ?: return null
        val corners = CanvasHitTest.layerScreenCorners(flat, canvasWidth, canvasHeight) ?: return null
        val cx = canvasWidth / 2f
        val cy = canvasHeight / 2f
        val left = corners.minOf { it.x } - cx
        val top = corners.minOf { it.y } - cy
        return Rect(left, top, corners.maxOf { it.x } - cx - left, corners.maxOf { it.y } - cy - top)
    }

    /** [box]'s four corners on screen (TL, TR, BR, BL) under the camera. */
    fun screenCorners(box: Rect, canvasWidth: Float, canvasHeight: Float, offset: Offset, zoom: Float, rotation: Float): List<Offset> =
        listOf(
            Offset(box.x, box.y), Offset(box.right, box.y), Offset(box.right, box.bottom), Offset(box.x, box.bottom),
        ).map { CanvasHitTest.worldToScreen(Offset(it.x + canvasWidth / 2f, it.y + canvasHeight / 2f), offset, zoom, rotation) }
}
