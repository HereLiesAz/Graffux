// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/model/VectorPaths.kt
package com.hereliesaz.graffitixr.common.model

/**
 * Pure geometry helpers for [ShapeKind.PATH] vector shapes — building a normalized path shape from a
 * cloud of points and measuring its bounds. Kept free of Android/Compose types so both the pen tool
 * and the document importers (PSD vector masks, Illustrator art) share one source of truth, unit-
 * testable on a plain JVM.
 */
object VectorPaths {

    /**
     * The axis-aligned bounds of the interleaved `[x0,y0,x1,y1,…]` [points] as
     * `[minX, minY, maxX, maxY]`, or null when there isn't at least one full point.
     */
    fun bounds(points: List<Float>): FloatArray? {
        if (points.size < 2) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var i = 0
        while (i + 1 < points.size) {
            val x = points[i]; val y = points[i + 1]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            i += 2
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /**
     * Builds a [ShapeKind.PATH] [VectorShape] from raw [points] (any coordinate space), re-centering
     * them on the origin so the shape follows the editor's centered-on-origin convention and setting
     * [VectorShape.width]/[VectorShape.height] to the bounding box. Returns null if [points] has no
     * complete point. [closed] fills-and-joins the path; an open path is stroked only.
     */
    fun pathShape(
        points: List<Float>,
        closed: Boolean = false,
        strokeArgb: Long = 0xFFFFFFFFL,
        strokeWidth: Float = 6f,
        fillArgb: Long = 0x00000000L,
    ): VectorShape? {
        val b = bounds(points) ?: return null
        val cx = (b[0] + b[2]) / 2f
        val cy = (b[1] + b[3]) / 2f
        val centered = ArrayList<Float>(points.size)
        var i = 0
        while (i + 1 < points.size) {
            centered.add(points[i] - cx)
            centered.add(points[i + 1] - cy)
            i += 2
        }
        return VectorShape(
            kind = ShapeKind.PATH,
            width = (b[2] - b[0]).coerceAtLeast(1f),
            height = (b[3] - b[1]).coerceAtLeast(1f),
            fillArgb = fillArgb,
            strokeArgb = strokeArgb,
            strokeWidth = strokeWidth,
            points = centered,
            closed = closed,
        )
    }
}
