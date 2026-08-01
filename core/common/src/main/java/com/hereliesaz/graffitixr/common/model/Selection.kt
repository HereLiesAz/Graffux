package com.hereliesaz.graffitixr.common.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * How a drag on the selection canvas becomes a region — Procreate's selection *modes*.
 *
 * Every mode produces the same thing, a [Selection] polygon, which is why they can share one tool
 * rather than being three: the difference is entirely in how the drag is read. [FREEHAND] traces
 * the finger; the other two read the drag as the two opposite corners of a bounding box and fit a
 * shape inside it.
 *
 * Procreate's fourth mode, Automatic, is absent — it selects by colour contiguity, which needs a
 * flood fill traced back out to a contour rather than a different reading of the same drag.
 */
enum class SelectionShape {
    /** The traced path itself. */
    FREEHAND,

    /** The drag's bounding box, as four corners. */
    RECTANGLE,

    /** The ellipse inscribed in the drag's bounding box, sampled into a polygon. */
    ELLIPSE;

    val label: String
        get() = when (this) {
            FREEHAND -> "Freehand"
            RECTANGLE -> "Rectangle"
            ELLIPSE -> "Ellipse"
        }
}

/**
 * Vertices used to approximate an [SelectionShape.ELLIPSE].
 *
 * The polygon is the selection — containment, clipping and the marching ants all read it directly —
 * so this is the resolution of the curve, not a preview detail. 64 puts the worst-case chord error
 * under a quarter-pixel on a 2000px-wide ellipse, which is below what any of those three can show,
 * while staying far cheaper to re-map than the thousands of points a traced lasso carries.
 */
const val ELLIPSE_VERTICES: Int = 64

/**
 * A selection: the region every raster tool is confined to while it is active.
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
