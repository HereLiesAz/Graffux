@file:Suppress("MagicNumber", "LongParameterList")

package com.hereliesaz.graffitixr.common.scene

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot

/**
 * The invariant behind "a stroke lands under the finger" on a surface (spec/3d-space.md, "Painting by
 * footprint"): a surface point accepted for a touch must show on the canvas within the brush footprint of
 * that touch, with the camera the touch was mapped with, and the stretch of stroke joining two accepted
 * points must too (its midpoint shows between the two touches). A ray that skims a silhouette hits a near
 * face on one sample and a far one on the next; both are "on the surface", but the straight stroke joining
 * them is painted across the part of the surface in between, nowhere near the finger. Such a sample or join
 * is dropped (the stroke is cut there), never painted. All distances are in one space (the viewport's
 * "world"); no allocation here, so it is safe per sample.
 */
object SurfaceStrokeGuard {
    /** Pixels of slack beyond the footprint: float error and the footprint's ring sampling. */
    const val SLACK = 2f

    private val droppedSamples = AtomicLong()
    private val cutJoins = AtomicLong()
    private val hiddenPoints = AtomicLong()

    /** Samples dropped for not showing near their touch, since the process started (debug counter). */
    val dropped: Long get() = droppedSamples.get()

    /** Joins cut for bridging a gap in the surface, since the process started (debug counter). */
    val cuts: Long get() = cutJoins.get()

    /** Points refused for being out of the camera's direct view (behind a fold or another surface), since start. */
    val hidden: Long get() = hiddenPoints.get()

    /**
     * Passes [visible] (the camera sees the surface point; see [SurfaceVisibility]) through, counting a refusal.
     * An occluded sample is dropped exactly like a miss: it is not a gap in the surface, so it is never
     * counted as a [cuts] join cut, and the stroke resumes as a new segment where it is visible again.
     */
    fun visibleOk(visible: Boolean): Boolean {
        if (!visible) hiddenPoints.incrementAndGet()
        return visible
    }

    /** Whether a point accepted for the touch ([tx], [ty]) shows at ([bx], [by]), within [radius] of it. */
    fun sampleOk(tx: Float, ty: Float, bx: Float, by: Float, radius: Float): Boolean {
        val ok = bx.isFinite() && by.isFinite() && hypot(bx - tx, by - ty) <= radius + SLACK
        if (!ok) droppedSamples.incrementAndGet()
        return ok
    }

    /**
     * Whether the stroke from touch ([ax], [ay]) to ([bx], [by]) stays on the surface between them: the
     * midpoint of the joining flat stroke shows at ([mx], [my]), which must lie within [radius] of the
     * touch segment. Perspective does not preserve midpoints, so the distance is to the segment, not to
     * the touches' midpoint: a valid stroke on one planar face projects onto the segment exactly.
     */
    fun joinOk(ax: Float, ay: Float, bx: Float, by: Float, mx: Float, my: Float, radius: Float): Boolean {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val s = if (len2 < 1e-12f) 0f else (((mx - ax) * dx + (my - ay) * dy) / len2).coerceIn(0f, 1f)
        val ok = mx.isFinite() && my.isFinite() && hypot(mx - (ax + dx * s), my - (ay + dy * s)) <= radius + SLACK
        if (!ok) cutJoins.incrementAndGet()
        return ok
    }
}
