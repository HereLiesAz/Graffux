package com.hereliesaz.graffitixr.common.scene

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The draw plane's depth (spec/3d-space.md, "Draw plane depth"): where the next stroke lands when the user
 * has dragged the wire-mesh button. Pure Kotlin; nothing here moves any art.
 *
 * The plane is parallel to a reference plane (the plane of the last stroke drawn) and offset along its
 * normal until its anchor, the reference layer's box centre, is [depth] in front of the eye. With no
 * reference it is the camera-facing plane through the orbit pivot. [depth] is a camera-space distance, the
 * same unit as [CameraFrame.toCamera]'s z, so the drag maps it multiplicatively (see [depthAfterDrag]).
 */
@Suppress("MagicNumber", "TooManyFunctions")
object DrawPlane {
    /** The drag length, in dp, that changes the depth by [DRAG_FACTOR]. */
    const val DRAG_DP_PER_CHANGE = 400f

    /** The depth change a drag of [DRAG_DP_PER_CHANGE] makes, as the Move-tool pinch does: multiplicative. */
    const val DRAG_FACTOR = 4f

    /** The depth change of one menu step ("Draw distance nearer" / "farther"). */
    const val STEP_FACTOR = 1.25f

    /** How long the guide stays after a drag ends (ms). */
    const val GUIDE_LINGER_MS = 1_000L

    /** A reference plane seen edge-on closer than this (|normal . view|) cannot be offset along its normal. */
    private const val MIN_FACING = 0.1f
    private const val NEAR_MULTIPLE = 2f
    private const val FAR_DIVISOR = 10f

    /** Guide cells each side of the anchor, and their size in scene pixels (the surface guide grid's). */
    private const val GUIDE_CELLS = 16
    private const val GUIDE_SPACING = SurfaceMath.GRID_SPACING

    /** The eye-to-plane distances a depth may take: twice the near plane to a tenth of the far plane. */
    fun depthRange(focal: Float): ClosedFloatingPointRange<Float> =
        (SceneMath.nearPlane(focal) * NEAR_MULTIPLE)..(SceneMath.farPlane(focal) / FAR_DIVISOR)

    /** [current] after a drag of [dragDownDp]: down is farther, up is nearer, clamped to [depthRange]. */
    fun depthAfterDrag(current: Float, dragDownDp: Float, focal: Float): Float =
        (current * DRAG_FACTOR.pow(dragDownDp / DRAG_DP_PER_CHANGE)).coerceIn(depthRange(focal))

    /** The effective eye-to-plane distance: the [automatic] one times the user's [factor], within [depthRange]. */
    fun effectiveDepth(automatic: Float, factor: Float, focal: Float): Float =
        (automatic * factor).coerceIn(depthRange(focal))

    /** The scene point the depth is measured at: the reference's box centre, else the pivot, else the target. */
    fun anchor(reference: Affine3?, cam: CameraFrame, pivot: FloatArray?): FloatArray =
        reference?.takeIf { isUsable(cam, it) }?.apply(cam.width / 2f, cam.height / 2f, 0f) ?: pivot ?: cam.target

    /**
     * Whether [reference] can be offset along its normal: not seen nearly edge-on, and its centre in front of the eye.
     * [world] falls back to the camera-facing plane through the pivot otherwise, and [anchor] measures there too, so a
     * factor of 1 is the automatic plane whichever is used.
     */
    private fun isUsable(cam: CameraFrame, reference: Affine3): Boolean {
        val facing = kotlin.math.abs(SceneMath.dot(SceneMath.normalize(reference.column(2)), cam.forward))
        val z0 = cam.toCamera(reference.apply(cam.width / 2f, cam.height / 2f, 0f))[2]
        return facing >= MIN_FACING && facing.isFinite() && z0 > 0f
    }

    /** The depth of the automatic plane: the anchor's own distance, which a drag starts from. */
    fun anchorDepth(reference: Affine3?, cam: CameraFrame, pivot: FloatArray?): Float =
        cam.toCamera(anchor(reference, cam, pivot))[2]

    /**
     * The draw plane's model (box-local to scene) at [depth], or null when the geometry is degenerate. A
     * [reference] seen nearly edge-on falls back to the camera-facing plane. The box keeps the pixel size it
     * had on screen at the reference (its scale grows with the distance), so a stroke is as wide as before.
     */
    fun world(cam: CameraFrame, reference: Affine3?, pivot: FloatArray?, depth: Float): Affine3? {
        if (!depth.isFinite() || depth <= 0f) return null
        val parallel = reference?.let { parallelWorld(cam, it, depth) }
        return parallel ?: facingWorld(cam, pivot, depth)
    }

    /** [world] as the pose of a layer whose enclosing groups are [parentChain] (outermost first). */
    @Suppress("ReturnCount")
    fun pose(
        cam: CameraFrame,
        parentChain: List<PlanePose>,
        reference: Affine3?,
        pivot: FloatArray?,
        depth: Float,
    ): PlanePose? {
        val world = world(cam, reference, pivot, depth) ?: return null
        val local = (SceneMath.chainModel(parentChain, cam.width, cam.height).inverse() ?: return null) * world
        return SceneMath.poseOf(local, cam.width, cam.height)
    }

    /** The camera-facing plane through [pivot] (or the target), [depth] from the eye, 1:1 pixels at that depth. */
    private fun facingWorld(cam: CameraFrame, pivot: FloatArray?, depth: Float): Affine3 {
        val through = pivot ?: cam.target
        val s = depth / cam.focal
        val moved = FloatArray(3) { through[it] + cam.forward[it] * (depth - cam.toCamera(through)[2]) }
        return boxModel(arrayOf(cam.right, cam.down, cam.forward), s, moved, cam)
    }

    @Suppress("ReturnCount")
    private fun parallelWorld(cam: CameraFrame, reference: Affine3, depth: Float): Affine3? {
        if (!isUsable(cam, reference)) return null
        val raw = SceneMath.normalize(reference.column(2))
        val toward = if (SceneMath.dot(raw, cam.forward) > 0f) FloatArray(3) { -raw[it] } else raw
        val facing = -SceneMath.dot(toward, cam.forward)
        val at = reference.apply(cam.width / 2f, cam.height / 2f, 0f)
        val z0 = cam.toCamera(at)[2]
        val shift = (z0 - depth) / facing
        val centre = FloatArray(3) { at[it] + toward[it] * shift }
        val k = depth / z0
        val axes = Array(3) { c -> reference.column(c) }
        return boxModel(axes, k, centre, cam)
    }

    /** A model with columns [axes] scaled by [k], its box centre at [centre]. */
    private fun boxModel(axes: Array<FloatArray>, k: Float, centre: FloatArray, cam: CameraFrame): Affine3 {
        val m = FloatArray(12)
        for (row in 0..2) {
            for (col in 0..2) m[row * 4 + col] = axes[col][row] * k
        }
        val cx = cam.width / 2f
        val cy = cam.height / 2f
        for (row in 0..2) m[row * 4 + 3] = centre[row] - (m[row * 4] * cx + m[row * 4 + 1] * cy)
        return Affine3(m)
    }

    /**
     * The guide: canvas-space segments `x0, y0, x1, y1` of a square grid on the plane [world], centred on its box
     * centre, [GUIDE_SPACING] scene pixels a cell, so it shrinks with the distance as a plane in perspective does.
     * Clipped to the near plane. Empty when [world] has no usable scale.
     */
    fun guideSegments(world: Affine3, cam: CameraFrame): List<FloatArray> {
        val c0 = world.column(0)
        val scale = sqrt(SceneMath.dot(c0, c0))
        if (scale < 1e-9f || !scale.isFinite()) return emptyList()
        val step = GUIDE_SPACING / scale
        val cx = cam.width / 2f
        val cy = cam.height / 2f
        val reach = GUIDE_CELLS * step
        val out = ArrayList<FloatArray>()
        for (i in -GUIDE_CELLS..GUIDE_CELLS) {
            val d = i * step
            val top = world.apply(cx + d, cy - reach, 0f)
            val bottom = world.apply(cx + d, cy + reach, 0f)
            val left = world.apply(cx - reach, cy + d, 0f)
            val right = world.apply(cx + reach, cy + d, 0f)
            val down = SurfaceMath.segment(top, bottom, cam)
            val across = SurfaceMath.segment(left, right, cam)
            down?.let(out::add)
            across?.let(out::add)
        }
        return out
    }
}
