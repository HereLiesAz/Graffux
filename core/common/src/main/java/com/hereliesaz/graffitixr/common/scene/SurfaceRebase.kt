@file:Suppress("MagicNumber", "ReturnCount")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Makes a surface's rest geometry equal what the user sees when it is made (spec/3d-space.md, "The orbit point").
 *
 * [OrbitPivot] reads a surface profile in its group's box-local space with every pose taken as the identity, and a
 * profile is stored through the inverse of the group's chain at the moment it is captured. For a group that is
 * posed (itself, or through a parent) that would put the rest box somewhere the sheet never was. So, before a
 * surface is first captured on such a group, its chain is baked away: the group takes the inverse of its
 * ancestors' chain as its own pose (ancestors times group is then the identity, and the profile can be captured
 * straight in scene coordinates), and each direct child takes the pose that keeps it exactly where it was seen
 * (`A * G' * C' = A * G * C`). Poses are similarity maps about the canvas centre, so every product and inverse is
 * again a [PlanePose]. Nothing new is stored.
 */
object SurfaceRebase {
    /**
     * [layers] with group [groupId] rebased as described above, or [layers] itself when its chain is already the
     * identity, or null when the chain cannot be inverted (a zero scale), in which case the caller captures as before.
     */
    fun forCreation(layers: List<Layer>, groupId: String, width: Float, height: Float): List<Layer>? {
        val byId = layers.associateBy { it.id }
        val group = byId[groupId] ?: return null
        val chain = planeChain(layers, groupId)
        if (chain.all { it == PlanePose() }) return layers
        val above = SceneMath.chainModel(chain.dropLast(1), width, height)
        val groupOld = SceneMath.model(group.planePose, width, height)
        val groupPose = poseOf(above.inverse() ?: return null, width, height) ?: return null
        val groupNew = SceneMath.model(groupPose, width, height)
        val groupNewInverse = groupNew.inverse() ?: return null
        val children = HashMap<String, PlanePose>()
        for (child in layers.filter { it.parentId == groupId }) {
            val m = groupNewInverse * groupOld * SceneMath.model(child.planePose, width, height)
            children[child.id] = poseOf(m, width, height) ?: return null
        }
        return layers.map { l ->
            when {
                l.id == groupId -> l.withPose(groupPose)
                l.id in children -> l.withPose(children.getValue(l.id))
                else -> l
            }
        }
    }

    private fun Layer.withPose(p: PlanePose) = copy(
        offset = Offset(p.offsetX, p.offsetY), positionZ = p.positionZ,
        rotationX = p.rotationX, rotationY = p.rotationY, rotationZ = p.rotationZ, scale = p.scale,
    )

    /** The pose whose [SceneMath.model] is [m]; null unless [m] is such a similarity map (checked by rebuilding it). */
    fun poseOf(m: Affine3, width: Float, height: Float): PlanePose? {
        val a = m.m
        val s = sqrt(a[0] * a[0] + a[4] * a[4] + a[8] * a[8])
        if (!(s > 1e-6f) || !s.isFinite()) return null
        val r = FloatArray(9) { a[(it / 3) * 4 + it % 3] / s }
        val e = SceneMath.eulerOf(r)
        val cx = width / 2f
        val cy = height / 2f
        val o = floatArrayOf(
            a[3] - cx + (a[0] * cx + a[1] * cy),
            a[7] - cy + (a[4] * cx + a[5] * cy),
            a[11] + (a[8] * cx + a[9] * cy),
        )
        val pose = PlanePose(o[0], o[1], o[2], e[0], e[1], e[2], s)
        val back = SceneMath.model(pose, width, height).m
        val finite = listOf(o[0], o[1], o[2], e[0], e[1], e[2]).all(Float::isFinite)
        val ok = finite && a.indices.all { abs(a[it] - back[it]) <= 1e-3f * (1f + abs(a[it])) }
        return pose.takeIf { ok }
    }
}
