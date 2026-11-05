@file:Suppress("MagicNumber", "ReturnCount")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.isAdjustment
import com.hereliesaz.graffitixr.common.model.isContainer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The one point everything that revolves revolves about (spec/3d-space.md, "The orbit point"): the
 * rotation slider on every axis, the axis cycler's two-finger turn, camera orbit, and the point a
 * newly adopted view plane passes through.
 *
 * It is the centre of the box that holds the content. Layer 1 (the first paint layer, the base) is
 * flat, so until other layers give the content extent in the other planes the box is assumed from
 * layer 1's own size: its width W, its height H, and a depth [assumedDepth]. The box's centre is
 * W/2 across, H/2 down, and half that depth BEHIND layer 1's plane, away from the viewer; never on
 * the plane and never far off.
 */
object OrbitPivot {
    /**
     * The assumed depth D of layer 1's box, for a layer [width] by [height]: its longer side, so the
     * assumed box is a cube on that side. The owner did not state D; this is the choice, kept in
     * one place so it can be changed in one place.
     */
    fun assumedDepth(width: Float, height: Float): Float = max(width, height)

    /** Layers that hold content: not groups and not adjustments. Layer 1 is the first of them. */
    private fun contentLayers(layers: List<Layer>): List<Layer> =
        layers.filter { !it.isContainer && !it.isAdjustment }

    /** The pivot for [layers] on a [width] by [height] canvas, in scene pixels (x, y, z). */
    fun of(layers: List<Layer>, width: Float, height: Float): FloatArray {
        val d = assumedDepth(width, height)
        val content = contentLayers(layers)
        val base = content.firstOrNull() ?: return floatArrayOf(width / 2f, height / 2f, d / 2f)
        val baseModel = SceneMath.chainModel(planeChain(layers, base.id), width, height)
        // Layer 1 alone: its own box, carried with it. Because the box rides the layer's pose, the
        // point does not move when that pose turns about it, so a drag cannot make it jump.
        if (content.size == 1) return baseModel.apply(width / 2f, height / 2f, d / 2f)

        val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (layer in content) {
            val m = SceneMath.chainModel(planeChain(layers, layer.id), width, height)
            for ((u, v) in listOf(0f to 0f, width to 0f, 0f to height, width to height)) {
                val p = m.apply(u, v, 0f)
                for (i in 0..2) { lo[i] = min(lo[i], p[i]); hi[i] = max(hi[i], p[i]) }
            }
        }
        // Along an axis the content is still flat on, fall back to layer 1's assumed box.
        val flat = d * 1e-3f
        val box = FloatArray(3) { Float.MAX_VALUE } to FloatArray(3) { -Float.MAX_VALUE }
        for (z in listOf(0f, d)) for ((u, v) in listOf(0f to 0f, width to 0f, 0f to height, width to height)) {
            val p = baseModel.apply(u, v, z)
            for (i in 0..2) { box.first[i] = min(box.first[i], p[i]); box.second[i] = max(box.second[i], p[i]) }
        }
        return FloatArray(3) { i ->
            if (abs(hi[i] - lo[i]) <= flat) (box.first[i] + box.second[i]) / 2f else (lo[i] + hi[i]) / 2f
        }
    }
}
