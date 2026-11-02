@file:Suppress("ReturnCount")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.isContainer
import com.hereliesaz.graffitixr.common.model.isPlacedIn3D

/** A group with a surface profile (spec/3d-space.md, "Group surfaces"). */
val Layer.hasSurface: Boolean
    get() = isContainer && surface?.isValid == true

/** Placed in 3D, or a group whose layers are painted on a surface: drawn through the scene. */
private val Layer.inScene: Boolean
    get() = isPlacedIn3D || hasSurface

/**
 * Whether the canvas must draw through the scene (perspective) rather than the affine-only bitmap
 * path animation playback buffers: a layer placed in 3D, a group with a surface, or a moved camera.
 */
fun needsPerspectiveRender(layers: List<Layer>, camera: SceneCamera): Boolean =
    layers.any { it.inScene } || !camera.isDefault

/** The layer's plane placement. */
val Layer.planePose: PlanePose
    get() = PlanePose(offset.x, offset.y, positionZ, rotationX, rotationY, rotationZ, scale)

/** [layerId]'s poses from its outermost group down to itself; empty when it is not in [layers]. */
fun planeChain(layers: List<Layer>, layerId: String): List<PlanePose> {
    val byId = layers.associateBy { it.id }
    val chain = ArrayList<PlanePose>()
    var current = byId[layerId]
    val seen = HashSet<String>()
    while (current != null && seen.add(current.id)) {
        chain += current.planePose
        current = current.parentId?.let(byId::get)
    }
    return chain.asReversed()
}

/**
 * Which layers draw through the 3D scene path (spec/3d-space.md, "Render path"). Everything else
 * keeps the original 2D `graphicsLayer` path, untouched.
 *
 * With the camera at its default, a top-level subtree goes 3D only if one of its layers is placed
 * in 3D; then the whole subtree does, so each node's homography composes with its parent's. With
 * the camera moved, every layer is seen in perspective, so every layer does.
 */
object SceneRenderPath {
    fun sceneLayerIds(layers: List<Layer>, camera: SceneCamera): Set<String> {
        if (layers.isEmpty()) return emptySet()
        if (!camera.isDefault) return layers.mapTo(HashSet()) { it.id }
        if (layers.none { it.inScene }) return emptySet()
        val ids = layers.mapTo(HashSet()) { it.id }
        val byId = layers.associateBy { it.id }
        fun root(layer: Layer): String {
            var current = layer
            val seen = HashSet<String>()
            while (seen.add(current.id)) {
                current = current.parentId?.takeIf { it in ids }?.let(byId::get) ?: return current.id
            }
            return current.id
        }
        val roots = layers.groupBy(::root)
        return roots.values.filter { subtree -> subtree.any { it.inScene } }
            .flatMapTo(HashSet()) { subtree -> subtree.map { it.id } }
    }

    /** True when the 2D canvas is exactly what is on screen: the identity fast path everywhere. */
    fun isFlat(layers: List<Layer>, camera: SceneCamera): Boolean =
        camera.isDefault && layers.none { it.inScene }

    /** True when [layerId] draws exactly as in 2D (its subtree is flat and the camera at default). */
    fun isLayerFlat(layers: List<Layer>, layerId: String, camera: SceneCamera): Boolean =
        layerId !in sceneLayerIds(layers, camera)
}
