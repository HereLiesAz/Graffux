package com.hereliesaz.graffitixr.common.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.serialization.IntSizeSerializer
import com.hereliesaz.graffitixr.common.serialization.OffsetSerializer
import kotlinx.serialization.Serializable

/**
 * Shape of the lasso region being drawn — freehand finger trace or geometric figure.
 *
 * [FREEHAND] traces the finger, while [RECTANGLE] and [ELLIPSE] take the drag as two opposite corners of a box and fit a
 * figure inside it. [AUTOMATIC] is the odd one — a tap, and the only mode that reads the artwork
 * rather than the gesture.
 */
enum class SelectionShape {
    /** The traced path itself. */
    FREEHAND,

    /** The drag's bounding box, as four corners. */
    RECTANGLE,

    /** The ellipse inscribed in the drag's bounding box, sampled into a polygon. */
    ELLIPSE,

    /**
     * Procreate's Automatic: a **tap**, not a drag. Selects the contiguous run of similar colour
     * under the finger, which is why it is the one mode that reads the artwork instead of the
     * gesture — and the one that produces holes.
     */
    AUTOMATIC;

    /** True when the mode is driven by a tap rather than by dragging out a figure. */
    val isTap: Boolean get() = this == AUTOMATIC

    val label: String
        get() = when (this) {
            FREEHAND -> "Freehand"
            RECTANGLE -> "Rectangle"
            ELLIPSE -> "Ellipse"
            AUTOMATIC -> "Automatic"
        }
}

/**
 * What a completed selection drag does to the selection already on the canvas — Procreate's Add and
 * Remove, plus the default of simply replacing.
 *
 * Orthogonal to [SelectionShape], and deliberately so: any shape can be added or removed, which is
 * how a lasso subtracted from a rectangle gets you a shape neither mode could draw. Two pickers of
 * three, not one picker of nine.
 */
enum class SelectionOp {
    /** Replaces whatever was selected. */
    NEW,

    /** Unions onto the current selection. */
    ADD,

    /** Cuts out of the current selection. */
    REMOVE;

    val label: String
        get() = when (this) {
            NEW -> "New"
            ADD -> "Add"
            REMOVE -> "Remove"
        }
}

/**
 * How the Transform panel deforms the active layer — Procreate's Freeform / Distort / Warp.
 *
 * [FREEFORM] is the transform this app already had: position, scale and rotation, which move the
 * layer without bending it. The other two bend it, and differ only in how finely: [DISTORT] gives
 * four corner handles, so the only deformation available is a perspective one, and [WARP] subdivides
 * the same grid so each cell bends on its own.
 */
enum class TransformMode {
    FREEFORM,
    DISTORT,
    WARP;

    /** Handles per side. Freeform has no grid; distort is the corners; warp subdivides. */
    val gridSize: Int
        get() = when (this) {
            FREEFORM -> 0
            DISTORT -> 2
            WARP -> 4
        }

    val label: String
        get() = when (this) {
            FREEFORM -> "Freeform"
            DISTORT -> "Distort"
            WARP -> "Warp"
        }
}

/**
 * A selection the user has put aside under a name — Procreate's Save & Load.
 *
 * Worth having because a selection can be expensive to make: a magic-wand region refined with half a
 * dozen Add and Remove strokes represents real work, and until now the only way to keep it was not
 * to select anything else. Saved with the project, so it survives closing the app.
 */
@Serializable
data class SavedSelection(
    val name: String,
    val selection: Selection,
)

/**
 * Vertices used to approximate an [SelectionShape.ELLIPSE].
 *
 * The polygon is the selection — containment, clipping and the marching ants all read it directly —
 * so this is the resolution of the curve, not a preview detail.
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
@Serializable
data class SelectionRing(
    val path: List<@Serializable(with = OffsetSerializer::class) Offset>,
    val additive: Boolean = true,
) {
    /** A ring needs at least a triangle to enclose any area. */
    val isUsable: Boolean get() = path.size >= 3
}

/**
 * A selection: the region every raster tool is confined to while it is active.
 *
 * [rings] are stored in **screen space**, exactly like stroke paths, together with the [canvasSize]
 * they were drawn against.
 *
 * When [inverted] is true the selected region is everything *outside* the result instead.
 *
 * [featherPx] softens the boundary by that radius (screen px). Zero — the default — is a hard edge.
 */
@Serializable
data class Selection(
    val rings: List<SelectionRing>,
    @Serializable(with = IntSizeSerializer::class)
    val canvasSize: IntSize,
    val inverted: Boolean = false,
    val featherPx: Float = 0f,
) {
    /** The single-polygon compatibility constructor for path-only callers. */
    val path: List<Offset> get() = outline

    companion object {
        /**
         * The single-polygon case, which is still how most selections start.
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
     * cutting out of nothing leaves nothing.
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
