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
 * One closed polygon in a [Selection], and what it does to the region built so far.
 *
 * [additive] true unions this ring in, false subtracts it. That pair is the whole of Procreate's
 * Add and Remove: a selection is not one shape but a short recipe for one, and keeping the recipe
 * rather than a merged outline means no polygon-clipping library is needed to build it — the rings
 * are combined at the two places that actually need a merged region (the rasteriser, via
 * `Path.op`, and the pure-Kotlin containment test, by evaluating them in order).
 *
 * The polygon is implicitly closed (last point joins the first) and is never stored closed, so a
 * re-map can't accumulate duplicate vertices.
 */
data class SelectionRing(
    val path: List<Offset>,
    val additive: Boolean = true,
) {
    /** A ring needs at least a triangle to enclose any area. */
    val isUsable: Boolean get() = path.size >= 3
}

/**
 * A selection: the region every raster tool is confined to while it is active.
 *
 * [rings] are stored in **screen space**, exactly like [com.hereliesaz.graffitixr.feature.editor]'s
 * stroke paths, together with the [canvasSize] they were drawn against. That is deliberate: a
 * selection is document-level but each layer has its own bitmap resolution and transform, so there
 * is no single "native" pixel space to store it in. Keeping it in the same space as strokes means
 * it maps into any layer through the very same `mapScreenToBitmap` call the paint uses — so the
 * clip and the paint can never disagree about where the boundary is.
 *
 * The rings are an **ordered** recipe, evaluated first to last: each unions in or cuts out of the
 * region built by the ones before it. Order matters and is not an implementation detail — add a
 * circle then subtract it and you have nothing; subtract then add and you have the circle.
 *
 * When [inverted] is true the selected region is everything *outside* the result instead.
 *
 * [featherPx] softens the boundary by that radius (screen px). Zero — the default — is a hard edge
 * and takes a genuinely different, much cheaper path through the rasteriser, so feathering costs
 * nothing until it is asked for.
 */
data class Selection(
    val rings: List<SelectionRing>,
    val canvasSize: IntSize,
    val inverted: Boolean = false,
    val featherPx: Float = 0f,
) {
    companion object {
        /**
         * The single-polygon case, which is still how most selections start.
         *
         * A factory rather than a secondary constructor: after erasure `(List<Offset>, …)` and
         * `(List<SelectionRing>, …)` are the same JVM signature, so the two cannot coexist as
         * constructors however different they look in Kotlin.
         */
        fun ofPolygon(
            path: List<Offset>,
            canvasSize: IntSize,
            inverted: Boolean = false,
            featherPx: Float = 0f,
        ): Selection = Selection(listOf(SelectionRing(path)), canvasSize, inverted, featherPx)
    }

    /**
     * A selection is usable when at least one ring encloses area. Subtractive rings alone can't:
     * cutting out of nothing leaves nothing, so a stack that never adds selects nothing no matter
     * how much it removes.
     */
    val isUsable: Boolean
        get() = canvasSize.width > 0 && canvasSize.height > 0 &&
            rings.any { it.additive && it.isUsable }

    /**
     * A representative outline, for the places that want *a* polygon rather than the true region —
     * naming the selection in a recorded command, and the drag-ghost preview. The first additive
     * ring, because that is the one the user drew first.
     */
    val outline: List<Offset> get() = rings.firstOrNull { it.additive }?.path ?: emptyList()

    /** The same selection with every ring shifted by [delta] — how a moved selection travels. */
    fun translated(delta: Offset): Selection =
        copy(rings = rings.map { ring -> ring.copy(path = ring.path.map { it + delta }) })
}
