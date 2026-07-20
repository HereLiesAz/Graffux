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
    fun topHit(layers: List<Layer>, tap: Offset, canvasWidth: Float, canvasHeight: Float): String? {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return null
        val cx = canvasWidth / 2f
        val cy = canvasHeight / 2f
        // Render order is bottom-to-top, so the topmost layer is last; test in reverse.
        for (layer in layers.asReversed()) {
            if (!layer.isVisible || layer.scale <= 0f) continue
            val (halfW, halfH) = localHalfExtents(layer, canvasWidth, canvasHeight) ?: continue
            if (halfW <= 0f || halfH <= 0f) continue

            // Invert the render transform: undo translation + centre, then rotation, then scale.
            val dx = tap.x - layer.offset.x - cx
            val dy = tap.y - layer.offset.y - cy
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
