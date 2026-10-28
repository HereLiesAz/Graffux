package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import com.hereliesaz.graffitixr.common.model.FrameOps
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.buildLayerTree
import kotlin.math.ceil

/**
 * Frame [frameId] alone as a PNG-ready bitmap, at its box's size (one pixel per world unit): the
 * frame and what is in it, composited on the exporter's own path in the [canvasW] x [canvasH]
 * canvas the layers are measured in, then cut to the box. Adjustment layers draw their current
 * renders. Null when there is no such frame.
 */
fun ExportManager.compositeFrame(layers: List<Layer>, frameId: String, canvasW: Int, canvasH: Int): Bitmap? {
    val frame = layers.firstOrNull { it.id == frameId && it.type == LayerType.FRAME } ?: return null
    val box = FrameOps.boxOf(layers, frame)
    val world = FrameOps.worldTransform(layers, frame)
    val subtree = layers
        .filter { it.id == frameId || FrameOps.isDescendant(layers, it, frameId) }
        .map {
            if (it.id == frameId) {
                it.copy(parentId = null, offset = world.offset, scale = world.scale, rotationZ = world.rotation)
            } else {
                it
            }
        }
    val result = Bitmap.createBitmap(
        ceil(box.width).toInt().coerceAtLeast(1),
        ceil(box.height).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(result)
    canvas.translate(-(canvasW / 2f + box.x), -(canvasH / 2f + box.y))
    drawNodes(canvas, buildLayerTree(subtree), canvasW, canvasH, null)
    return result
}
