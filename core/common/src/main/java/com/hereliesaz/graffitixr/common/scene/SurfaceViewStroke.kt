package com.hereliesaz.graffitixr.common.scene

/**
 * A stroke that crosses from one view's paint image to another's (spec/3d-space.md, "Paint per
 * view"). The touches either side of the crease land on different images, so the stroke is cut
 * there; this finds where, so that each image gets its half of the stroke right up to the crease
 * and the cut leaves no gap between the two.
 */
object SurfaceViewStroke {
    /** Bisection steps: the screen interval shrinks to 2^-[STEPS] of the step between two touches. */
    const val STEPS = 24

    /**
     * The two sides of a crease: [last] is the last point in the starting view (at screen
     * ([lastX], [lastY])) and [first] the first in the next (at ([firstX], [firstY])).
     */
    class Crossing(
        val lastX: Float,
        val lastY: Float,
        val last: SurfaceMath.ViewFlat,
        val firstX: Float,
        val firstY: Float,
        val first: SurfaceMath.ViewFlat,
    )

    /**
     * Where the straight screen step from ([ax], [ay]) to ([bx], [by]) changes view. [from] is the
     * mapped touch at the start and [to] the one at the end, in different views; [map] maps any
     * screen point (null off the surface). Returns the last point still in `from`'s view and the
     * first beyond it, found by bisection, so that the pair brackets the crease as tightly as
     * [STEPS] allows. When the step passes through a third view or across a gap before it reaches
     * `to`'s view, the bracket is not trustworthy: the touches themselves are returned (the stroke
     * is cut between them, with the gap the surface really has there).
     */
    fun crossing(
        ax: Float,
        ay: Float,
        from: SurfaceMath.ViewFlat,
        bx: Float,
        by: Float,
        to: SurfaceMath.ViewFlat,
        map: (Float, Float) -> SurfaceMath.ViewFlat?,
    ): Crossing {
        var lo = 0f
        var hi = 1f
        var last = from
        var lastAt = 0f
        var first = to
        var firstAt = 1f
        repeat(STEPS) {
            val mid = (lo + hi) / 2f
            val m = map(ax + (bx - ax) * mid, ay + (by - ay) * mid)
            when {
                m == null -> hi = mid
                m.view == from.view -> {
                    lo = mid
                    lastAt = mid
                    last = m
                }
                m.view == to.view -> {
                    hi = mid
                    firstAt = mid
                    first = m
                }
                else -> hi = mid
            }
        }
        // The far side must really be `to`'s view at the bracket; else keep the touches.
        val farOk = map(ax + (bx - ax) * hi, ay + (by - ay) * hi)?.view == to.view
        if (!farOk) return Crossing(ax, ay, from, bx, by, to)
        return Crossing(
            ax + (bx - ax) * lastAt, ay + (by - ay) * lastAt, last,
            ax + (bx - ax) * firstAt, ay + (by - ay) * firstAt, first,
        )
    }
}
