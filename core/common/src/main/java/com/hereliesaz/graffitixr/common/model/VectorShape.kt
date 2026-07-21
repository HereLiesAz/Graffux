// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/model/VectorShape.kt
package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.Serializable

/** The primitive shapes a vector layer can hold. */
@Serializable
enum class ShapeKind { RECTANGLE, ELLIPSE, LINE, POLYGON, PATH }

/**
 * A single vector primitive living on a vector [Layer]. Geometry is defined in the layer's local
 * pixel space, centered on the layer origin (the layer's offset/scale/rotation then position it on
 * the canvas, exactly like a bitmap layer). Colours are packed ARGB longs; a fill alpha of 0 means
 * "no fill" and a [strokeWidth] of 0 means "no stroke", so a shape can be filled, outlined, or both.
 *
 * For [ShapeKind.LINE], [width] is the length of a horizontal line centered on the origin and
 * [height] is ignored; the line is drawn with the stroke colour/width.
 *
 * For [ShapeKind.POLYGON], a regular [sides]-gon is inscribed in the [width]×[height] box (so it
 * resizes with the shape), with a vertex pointing up.
 *
 * For [ShapeKind.PATH], the geometry is an arbitrary poly-line given by [points] — interleaved
 * `[x0, y0, x1, y1, …]` in the shape's local pixel space, centered on the origin like every other
 * kind. [width]/[height] are the point cloud's bounding-box size (used for hit-testing and resize).
 * A [closed] path joins its last point back to the first and can be filled; an open one is stroked
 * only. This backs both the pen tool and imported vector paths (PSD vector masks, Illustrator art).
 */
@Serializable
data class VectorShape(
    val kind: ShapeKind,
    val width: Float = 400f,
    val height: Float = 400f,
    /** Corner radius (px) for [ShapeKind.RECTANGLE]; ignored otherwise. */
    val cornerRadius: Float = 0f,
    val fillArgb: Long = 0xFF888888L,
    val strokeArgb: Long = 0xFFFFFFFFL,
    val strokeWidth: Float = 0f,
    /** Vertex count for [ShapeKind.POLYGON] (3 = triangle, 6 = hexagon…); ignored otherwise. */
    val sides: Int = 6,
    /** Interleaved `[x0,y0,x1,y1,…]` local points for [ShapeKind.PATH]; ignored otherwise. */
    val points: List<Float> = emptyList(),
    /** Whether a [ShapeKind.PATH] is closed (joins end→start, fillable); ignored otherwise. */
    val closed: Boolean = false,
) {
    val hasFill: Boolean
        get() = (fillArgb ushr 24) != 0L && kind != ShapeKind.LINE &&
            (kind != ShapeKind.PATH || closed)
    val hasStroke: Boolean get() = strokeWidth > 0f && (strokeArgb ushr 24) != 0L
}
