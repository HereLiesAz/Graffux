// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/CanvasHitTest.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure hit-testing for canvas taps → layer selection. Kept free of Android/Compose runtime types
 * (only the value-class [Offset] and plain [Layer] data) so the geometry is unit-testable without a
 * device.
 *
 * The geometry mirrors the per-layer render transform in `EditorScreen` exactly: every layer's
 * content is centred on the canvas centre, then `graphicsLayer` applies **scale** and **Z-rotation
 * about that centre** and finally a **pixel translation** by the layer's `offset`
 * (`transformOrigin = Center` on a full-screen node). To decide whether a screen-space [tap] lands
 * on a layer we invert that: subtract the offset and centre, un-rotate, un-scale, then test against
 * the layer's local content half-extents.
 *
 * Limitations (call out for on-device tuning): 3D X/Y rotation is ignored (≈0 in the 2D editor), and
 * raster bounds assume the `ContentScale.Fit` rect of the bitmap in the full canvas.
 */
internal object CanvasHitTest {

    /**
     * Returns the id of the topmost visible layer whose transformed content contains [tap]
     * (canvas pixels), or null if the tap misses every layer. Layers are tested front-to-back so
     * the visually topmost one wins.
     */
    fun topHit(
        layers: List<Layer>,
        tap: Offset,
        canvasWidth: Float,
        canvasHeight: Float,
        viewportOffset: Offset = Offset.Zero,
        viewportZoom: Float = 1f,
        viewportRotation: Float = 0f,
    ): String? {
        if (canvasWidth <= 0f || canvasHeight <= 0f || viewportZoom <= 0f) return null
        // Undo the camera first: screen → world (container) space, where the per-layer math lives.
        // screen = viewportOffset + zoom · R(rot) · world  ⇒  world = R(-rot) · (screen - offset) / zoom.
        val d = (tap - viewportOffset) / viewportZoom
        val camRad = Math.toRadians(-viewportRotation.toDouble())
        val camC = cos(camRad)
        val camS = sin(camRad)
        val worldTap = Offset(
            (d.x * camC - d.y * camS).toFloat(),
            (d.x * camS + d.y * camC).toFloat(),
        )
        val cx = canvasWidth / 2f
        val cy = canvasHeight / 2f
        // Render order is bottom-to-top, so the topmost layer is last; test in reverse.
        for (layer in layers.asReversed()) {
            if (!layer.isVisible || layer.scale <= 0f) continue
            val (halfW, halfH) = localHalfExtents(layer, canvasWidth, canvasHeight) ?: continue
            if (halfW <= 0f || halfH <= 0f) continue

            // Invert the render transform: undo translation + centre, then rotation, then scale.
            val dx = worldTap.x - layer.offset.x - cx
            val dy = worldTap.y - layer.offset.y - cy
            val rad = Math.toRadians(layer.rotationZ.toDouble())
            val c = cos(rad)
            val s = sin(rad)
            // R(-rotationZ) · (dx, dy), then divide by the uniform scale.
            val localX = ((dx * c + dy * s) / layer.scale).toFloat()
            val localY = ((-dx * s + dy * c) / layer.scale).toFloat()

            if (abs(localX) <= halfW && abs(localY) <= halfH) return layer.id
        }
        return null
    }

    /**
     * The four screen-space corners of [layer]'s content bounding box, in local TL, TR, BR, BL
     * order (connect them cyclically to draw the outline). This is the forward of [topHit]'s
     * inverse: local corners at (±halfW, ±halfH) are scaled and Z-rotated about the canvas centre,
     * then translated by the layer offset. Returns null when the layer has no measurable content or
     * the canvas is empty. Used to render the selection outline.
     */
    fun layerScreenCorners(
        layer: Layer,
        canvasWidth: Float,
        canvasHeight: Float,
        viewportOffset: Offset = Offset.Zero,
        viewportZoom: Float = 1f,
        viewportRotation: Float = 0f,
    ): List<Offset>? {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return null
        val (halfW, halfH) = localHalfExtents(layer, canvasWidth, canvasHeight) ?: return null
        val cx = canvasWidth / 2f
        val cy = canvasHeight / 2f
        val rad = Math.toRadians(layer.rotationZ.toDouble())
        val c = cos(rad)
        val s = sin(rad)
        val camRad = Math.toRadians(viewportRotation.toDouble())
        val camC = cos(camRad)
        val camS = sin(camRad)
        val locals = listOf(
            -halfW to -halfH, // TL
            halfW to -halfH,  // TR
            halfW to halfH,   // BR
            -halfW to halfH,  // BL
        )
        return locals.map { (lx, ly) ->
            // World (container) position, then apply the camera: screen = offset + zoom·R(rot)·world.
            val wx = cx + layer.offset.x + (layer.scale * (lx * c - ly * s)).toFloat()
            val wy = cy + layer.offset.y + (layer.scale * (lx * s + ly * c)).toFloat()
            val rx = (wx * camC - wy * camS).toFloat()
            val ry = (wx * camS + wy * camC).toFloat()
            Offset(viewportOffset.x + rx * viewportZoom, viewportOffset.y + ry * viewportZoom)
        }
    }

    /**
     * Index of the corner in [corners] nearest to [point] and within [radius] px, or null if none
     * is close enough. Used to decide whether a drag started on a selection resize handle.
     */
    fun nearestCornerIndex(point: Offset, corners: List<Offset>, radius: Float): Int? {
        var best = -1
        var bestDist = radius
        corners.forEachIndexed { i, c ->
            val d = (point - c).getDistance()
            if (d <= bestDist) {
                bestDist = d
                best = i
            }
        }
        return if (best >= 0) best else null
    }

    /**
     * The screen position of the rotation handle: out beyond the top edge's midpoint ([corners] TL,
     * TR), along the outward normal, by [distancePx]. Null if [corners] is degenerate. The handle
     * follows the box's rotation because it is derived from the (already-rotated) corners.
     */
    fun rotationHandlePos(corners: List<Offset>, distancePx: Float): Offset? {
        if (corners.size < 4) return null
        val topMid = (corners[0] + corners[1]) / 2f
        val boxCenter = (corners[0] + corners[1] + corners[2] + corners[3]) / 4f
        val dir = topMid - boxCenter
        val len = dir.getDistance()
        if (len < 0.001f) return null
        return topMid + (dir / len) * distancePx
    }

    /**
     * The signed angle (degrees, normalised to (-180, 180]) swept from [from] to [to] as seen from
     * [center] — the per-frame rotation delta for a rotation-handle drag. Positive follows the
     * screen's clockwise sense (y-down), matching `graphicsLayer.rotationZ`.
     */
    fun angleDeltaDegrees(center: Offset, from: Offset, to: Offset): Float {
        val a0 = Math.atan2((from.y - center.y).toDouble(), (from.x - center.x).toDouble())
        val a1 = Math.atan2((to.y - center.y).toDouble(), (to.x - center.x).toDouble())
        var d = Math.toDegrees(a1 - a0)
        while (d <= -180.0) d += 360.0
        while (d > 180.0) d -= 360.0
        return d.toFloat()
    }

    /** The visual centre of the box (average of its four [corners]); the pivot a rotate/resize
     *  drag turns about. */
    fun boxCenter(corners: List<Offset>): Offset =
        (corners[0] + corners[1] + corners[2] + corners[3]) / 4f

    /**
     * The layer's content half-extents in its own local (pre-transform) pixel space, centred on the
     * origin. Vector layers use the largest shape box; raster layers use the `ContentScale.Fit` rect
     * of the bitmap in the canvas. Returns null when the layer has no measurable content.
     */
    private fun localHalfExtents(layer: Layer, canvasWidth: Float, canvasHeight: Float): Pair<Float, Float>? {
        if (layer.shapes.isNotEmpty()) {
            val halfW = layer.shapes.maxOf { it.width } / 2f
            val halfH = layer.shapes.maxOf { it.height } / 2f
            return halfW to halfH
        }
        val bmp = layer.bitmap ?: return null
        if (bmp.width <= 0 || bmp.height <= 0) return null
        // ContentScale.Fit: scale the bitmap uniformly to fit the canvas, preserving aspect.
        val fit = minOf(canvasWidth / bmp.width, canvasHeight / bmp.height)
        return (bmp.width * fit / 2f) to (bmp.height * fit / 2f)
    }
}
