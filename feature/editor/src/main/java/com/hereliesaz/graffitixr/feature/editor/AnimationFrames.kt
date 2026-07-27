package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerNode
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.buildLayerTree

/**
 * Procreate's Animation Assist model: each TOP-LEVEL layer (parentId == null) is one frame; a
 * frame that's a GROUP carries multiple sub-layers drawn together as that one frame. Frame order
 * is just the top-level layer order, so the rail's existing drag-reorder already reorders frames —
 * no separate frame list is stored anywhere.
 */
object AnimationFrames {

    fun topLevelFrames(layers: List<Layer>): List<Layer> = buildLayerTree(layers).map { it.layer }

    /** Every id in [frameId]'s own subtree (itself, plus descendants if it's a GROUP frame). */
    fun frameSubtreeIds(layers: List<Layer>, frameId: String): Set<String> {
        val node = findNode(buildLayerTree(layers), frameId) ?: return setOf(frameId)
        return subtreeIds(node)
    }

    /**
     * The layer a stroke should land on for the frame at [index]: the frame itself when it's a
     * plain layer, or its topmost child when it's a GROUP (a group can't receive paint). Null when
     * there's no such frame, or when a GROUP frame is empty.
     */
    fun drawableLayerForFrame(layers: List<Layer>, index: Int): String? {
        val frame = topLevelFrames(layers).getOrNull(index) ?: return null
        if (frame.type != LayerType.GROUP) return frame.id
        return layers.lastOrNull { it.parentId == frame.id }?.id
    }

    /** The frame index (into [topLevelFrames]) that [layerId] belongs to, walking up to its root. */
    fun frameIndexForLayer(layers: List<Layer>, layerId: String?): Int {
        if (layerId == null) return 0
        val byId = layers.associateBy { it.id }
        var current = byId[layerId] ?: return 0
        while (current.parentId != null) current = byId[current.parentId] ?: return 0
        val idx = topLevelFrames(layers).indexOfFirst { it.id == current.id }
        return idx.coerceAtLeast(0)
    }

    /**
     * Per-layer opacity multiplier for Animation Assist rendering: 1 for the active frame's own
     * subtree, a fading fraction for up to [onionSkinFrameCount] neighbouring frames on each side
     * when [onionSkinEnabled], 0 (hidden) for every other frame. A layer outside every frame's
     * subtree (shouldn't happen — every layer resolves up to some top-level root) is simply absent
     * from the result, so callers should default a missing id to 1 rather than 0.
     */
    fun effectiveOpacities(
        layers: List<Layer>,
        activeFrameIndex: Int,
        onionSkinEnabled: Boolean,
        onionSkinFrameCount: Int,
    ): Map<String, Float> {
        val tree = buildLayerTree(layers)
        if (tree.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Float>()
        tree.forEachIndexed { index, node ->
            val distance = kotlin.math.abs(index - activeFrameIndex)
            val alpha = when {
                distance == 0 -> 1f
                onionSkinEnabled && distance <= onionSkinFrameCount ->
                    (ONION_MAX_ALPHA * (1f - distance.toFloat() / (onionSkinFrameCount + 1))).coerceAtLeast(ONION_MIN_ALPHA)
                else -> 0f
            }
            subtreeIds(node).forEach { id -> result[id] = alpha }
        }
        return result
    }

    private fun findNode(nodes: List<LayerNode>, id: String): LayerNode? {
        for (node in nodes) {
            if (node.layer.id == id) return node
            findNode(node.children, id)?.let { return it }
        }
        return null
    }

    private fun subtreeIds(node: LayerNode): Set<String> =
        setOf(node.layer.id) + node.children.flatMap { subtreeIds(it) }

    private const val ONION_MAX_ALPHA = 0.35f
    private const val ONION_MIN_ALPHA = 0.08f
}
