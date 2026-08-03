package com.hereliesaz.graffitixr.common.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * A freehand (lasso) selection: the region every raster tool is confined to while it is active.
 *
 * [path] is stored in **world space** (unzoomed, unpanned document space).
 * This ensures the selection remains anchored to the canvas content when the camera moves.
 * It maps into any layer's bitmap space by skipping the camera step of mapScreenToBitmap.
 *
 * The polygon is implicitly closed (last point joins the first); it is never stored closed, so a
 * round-trip through [inverted] or a re-map can't accumulate duplicate vertices.
 *
 * When [inverted] is true the selected region is everything *outside* the polygon instead.
 */
data class Selection(
    val path: List<Offset>,
    val canvasSize: IntSize,
    val inverted: Boolean = false,
) {
    /** A selection needs at least a triangle to enclose any area; anything less selects nothing. */
    val isUsable: Boolean get() = path.size >= 3 && canvasSize.width > 0 && canvasSize.height > 0
}
