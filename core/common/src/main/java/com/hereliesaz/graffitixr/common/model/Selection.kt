package com.hereliesaz.graffitixr.common.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * A freehand (lasso) selection: the region every raster tool is confined to while it is active.
 *
 * [path] is stored in **screen space**, exactly like [com.hereliesaz.graffitixr.feature.editor]'s
 * stroke paths, together with the [canvasSize] it was drawn against. That is deliberate: a
 * selection is document-level but each layer has its own bitmap resolution and transform, so there
 * is no single "native" pixel space to store it in. Keeping it in the same space as strokes means
 * it maps into any layer through the very same `mapScreenToBitmap` call the paint uses — so the
 * clip and the paint can never disagree about where the boundary is.
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
