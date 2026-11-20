@file:Suppress("MagicNumber", "ReturnCount")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.isContainer
import kotlin.math.abs

/**
 * Makes a surface's rest geometry equal where its sheet is seen when it is made (spec/3d-space.md, "The orbit point").
 *
 * [OrbitPivot] reads a surface profile in its group's box-local space with every pose taken as the identity, and a
 * profile is stored through the inverse of the group's chain at the moment it is captured. For a group that is posed
 * (itself, or through a parent) that would put the rest box somewhere the sheet never was. So, before a surface is
 * first captured on such a group, the group's own pose becomes the inverse of its ancestors' chain: ancestors times
 * group is then the identity, and the profile is captured straight in scene coordinates (the sheet is where it is
 * seen, and so is its rest box).
 *
 * Paint on a surface is placed by [SurfaceMath.flatSurfaceModel]: the owner's whole chain (full poses) times the
 * surface, times the paint's inner chain with depth and X/Y tilt dropped. With the profile stored through the old
 * chain's inverse, that product is the sheet as seen times a uniform scale `s` (the old chain's total scale) of the
 * unrolled coordinates, about their origin, times the paint's flat pose; the rotation and tilt of the old chain
 * cancel. After the rebase the chain is the identity, so the same placement needs the paint's own flat pose followed by
 * that scale. Each DIRECT child therefore takes `scale * s` and `offset * s + (s - 1) * canvasCentre` (what a scale by
 * `s` about the origin does to a pose about the canvas centre); its depth, tilt and Z turn are left alone, and so are
 * deeper layers. Measured through [SurfaceMath.flatSurfaceModel] (the real placement), paint then lands where it did
 * before, to float rounding, for offset, scale, Z turn, X and Y tilt and depth of the group and of its parents.
 * What is NOT claimed: that the paint layers' own full-pose plane (the picture they had before any surface existed)
 * is unchanged; once a surface exists that plane is not drawn.
 *
 * Declined (null, so the caller captures as it always did; the pivot of such a sheet is then NOT the rule for the sheet
 * as seen, and nothing else changes): when the group holds another surface group below it (that one's whole chain runs
 * through this group's pose, which would move it), when its ancestors' chain cannot be inverted (a zero scale or a
 * non-finite pose), or when the inverse cannot be written back as a [PlanePose] to within [REBUILD_TOLERANCE].
 */
object SurfaceRebase {
    /**
     * How far, in canvas pixels, the rebased group's chain may be from the identity: the pose is rebuilt and
     * measured on points 1000 to 1600 px from the origin (float32 spacing there is about 1e-4 px, a few operations
     * deep), so a quarter of a pixel is two to three orders above the rounding and below anything seen.
     */
    const val REBUILD_TOLERANCE = 0.25f

    /**
     * [layers] with group [groupId] rebased as described above, [layers] itself when its chain is already the
     * identity, or null when declined (see the class comment).
     */
    fun forCreation(layers: List<Layer>, groupId: String, width: Float, height: Float): List<Layer>? {
        val group = layers.firstOrNull { it.id == groupId } ?: return null
        val chain = planeChain(layers, groupId)
        if (chain.all { it == PlanePose() }) return layers
        if (holdsSurfaceBelow(layers, groupId)) return null
        val above = SceneMath.chainModel(chain.dropLast(1), width, height)
        val pose = SceneMath.poseOf(above.inverse() ?: return null, width, height) ?: return null
        if (!rebuilds(above, pose, width, height)) return null
        val c0 = SceneMath.chainModel(chain, width, height).column(0)
        val k = kotlin.math.sqrt(c0[0] * c0[0] + c0[1] * c0[1] + c0[2] * c0[2])
        if (!k.isFinite() || k <= 0f) return null
        val cx = width / 2f
        val cy = height / 2f
        return layers.map {
            when {
                it.id == group.id -> it.withPose(pose)
                it.parentId == group.id && k != 1f ->
                    it.copy(
                        scale = it.scale * k,
                        offset = Offset(it.offset.x * k + (k - 1f) * cx, it.offset.y * k + (k - 1f) * cy),
                    )
                else -> it
            }
        }
    }

    private fun holdsSurfaceBelow(layers: List<Layer>, groupId: String): Boolean {
        val children = layers.groupBy { it.parentId }
        val seen = HashSet<String>()
        val stack = ArrayDeque(children[groupId].orEmpty())
        while (stack.isNotEmpty()) {
            val l = stack.removeLast()
            if (!seen.add(l.id)) continue
            if (l.isContainer && l.surface?.isValid == true) return true
            stack.addAll(children[l.id].orEmpty())
        }
        return false
    }

    /** Whether [above] followed by [pose] is the identity on the canvas corners and a point off the plane. */
    private fun rebuilds(above: Affine3, pose: PlanePose, width: Float, height: Float): Boolean {
        val both = above * SceneMath.model(pose, width, height)
        val probes = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(width, height, 0f), floatArrayOf(0f, height, 500f))
        return probes.all { p ->
            val q = both.apply(p[0], p[1], p[2])
            (0..2).all { i -> q[i].isFinite() && abs(q[i] - p[i]) <= REBUILD_TOLERANCE }
        }
    }

    private fun Layer.withPose(p: PlanePose) = copy(
        offset = Offset(p.offsetX, p.offsetY), positionZ = p.positionZ,
        rotationX = p.rotationX, rotationY = p.rotationY, rotationZ = p.rotationZ, scale = p.scale,
    )
}
