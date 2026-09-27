package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import java.util.concurrent.ConcurrentHashMap

/**
 * Where each raster layer's bitmap lands on screen, recorded as the layers are laid out, so the
 * live-stroke overlay can be told how overlay pixels map to layer pixels (see [layerToOverlay]).
 * Written on the UI thread, read on it at stroke start.
 */
class OverlayGeometry {
    private class Placement(val coordinates: LayoutCoordinates, val bitmapWidth: Int, val bitmapHeight: Int)

    private val placements = ConcurrentHashMap<String, Placement>()

    /** The overlay SurfaceView's top-left in window coordinates. */
    @Volatile
    var overlayOrigin: Offset = Offset.Zero

    /** Called from each layer Image's onGloballyPositioned (after its graphicsLayer). */
    fun place(layerId: String, coordinates: LayoutCoordinates, bitmapWidth: Int, bitmapHeight: Int) {
        placements[layerId] = Placement(coordinates, bitmapWidth, bitmapHeight)
    }

    fun forget(layerId: String) {
        placements.remove(layerId)
    }

    /**
     * Affine map from layer bitmap pixels to overlay pixels, `[a, b, c, d, e, f]` meaning
     * `(a*u + b*v + c, d*u + e*v + f)`, or null if the layer isn't placed. Composes the Image's
     * ContentScale.Fit with everything above it (its graphicsLayer, the workspace camera) by
     * mapping three points through the real layout, so it matches what Compose draws.
     */
    fun layerToOverlay(layerId: String): FloatArray? = placements[layerId]
        ?.takeIf { it.coordinates.isAttached && it.bitmapWidth > 0 && it.bitmapHeight > 0 }
        ?.let { p ->
            val coords = p.coordinates
            val size = coords.size
            // ContentScale.Fit: uniform scale, centred.
            val scale = minOf(size.width.toFloat() / p.bitmapWidth, size.height.toFloat() / p.bitmapHeight)
            val left = (size.width - p.bitmapWidth * scale) / 2f
            val top = (size.height - p.bitmapHeight * scale) / 2f
            val origin = coords.localToWindow(Offset(left, top)) - overlayOrigin
            val unitU = coords.localToWindow(Offset(left + scale, top)) - overlayOrigin - origin
            val unitV = coords.localToWindow(Offset(left, top + scale)) - overlayOrigin - origin
            floatArrayOf(unitU.x, unitV.x, origin.x, unitU.y, unitV.y, origin.y).takeIf { scale > 0f }
        }
}

/** Applies an affine `[a, b, c, d, e, f]` to (u, v). */
@Suppress("MagicNumber") // Indices into the six affine coefficients.
internal fun applyAffine(m: FloatArray, u: Float, v: Float): Offset =
    Offset(m[0] * u + m[1] * v + m[2], m[3] * u + m[4] * v + m[5])

/** Inverse of an affine `[a, b, c, d, e, f]`, or null when it's singular. */
@Suppress("MagicNumber") // Indices into the six affine coefficients.
internal fun invertAffine(m: FloatArray): FloatArray? {
    val det = m[0] * m[4] - m[1] * m[3]
    if (kotlin.math.abs(det) < AFFINE_EPSILON) return null
    val ia = m[4] / det
    val ib = -m[1] / det
    val id = -m[3] / det
    val ie = m[0] / det
    return floatArrayOf(ia, ib, -(ia * m[2] + ib * m[5]), id, ie, -(id * m[2] + ie * m[5]))
}

private const val AFFINE_EPSILON = 1e-9f

/** Records where a layer's bitmap lands for the direct-display overlay; a no-op when [geometry] is null. */
internal fun Modifier.recordOverlayPlacement(
    geometry: OverlayGeometry?,
    layerId: String,
    bitmapWidth: Int,
    bitmapHeight: Int,
): Modifier = if (geometry == null) this else onGloballyPositioned {
    geometry.place(layerId, it, bitmapWidth, bitmapHeight)
}
