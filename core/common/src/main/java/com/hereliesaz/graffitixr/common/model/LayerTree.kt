package com.hereliesaz.graffitixr.common.model

data class LayerNode(
    val layer: Layer,
    val depth: Int,
    val children: List<LayerNode>
)

/**
 * Builds a hierarchical tree from a flat list of layers.
 */
fun buildLayerTree(layers: List<Layer>): List<LayerNode> {
    val byParent = layers.groupBy { it.parentId }
    val visitedIds = mutableSetOf<String>()

    fun build(parentId: String?, depth: Int, ancestry: Set<String>): List<LayerNode> {
        return (byParent[parentId] ?: emptyList()).mapNotNull { layer ->
            if (layer.id in ancestry) {
                null
            } else {
                visitedIds.add(layer.id)
                LayerNode(layer, depth, build(layer.id, depth + 1, ancestry + layer.id))
            }
        }
    }

    val rootNodes = build(null, 0, emptySet()).toMutableList()

    // Collect any orphaned subtrees so no layer is dropped and internal parent-child relations are
    // preserved. A layer whose parent id names nothing in the list is promoted by building from
    // that dangling id, which emits it together with its siblings and descendants; a layer whose
    // parent IS present is deferred, because it will be emitted as part of that parent's subtree.
    val layerMap = layers.associateBy { it.id }
    for (layer in layers) {
        if (layer.id !in visitedIds) {
            val parentId = layer.parentId
            if (parentId != null && parentId in layerMap && parentId !in visitedIds) {
                continue
            }
            rootNodes.addAll(build(layer.parentId, 0, emptySet()))
        }
    }

    // Whatever is still unreached is in a parent CYCLE: every member's parent is another member, so
    // the pass above defers each of them to the next and none is ever emitted. That silently deleted
    // the whole cycle from the tree — and the tree is what the layers panel lists, what Animation
    // Assist counts as frames, and what the exporter composites, so those layers disappeared from
    // the artwork rather than merely from a view of it. Break the cycle at its first member in list
    // order and build from there, repeating until nothing is left; `build`'s own ancestry guard cuts
    // the back edge when the walk returns to that member.
    for (layer in layers) {
        if (layer.id in visitedIds) continue
        visitedIds.add(layer.id)
        rootNodes += LayerNode(layer, 0, build(layer.id, 1, setOf(layer.id)))
    }

    return rootNodes
}

/**
 * Flattens a layer tree back into a list, usually for UI display where nested
 * items need to be shown in a flat scrolling list.
 */
fun flattenTree(nodes: List<LayerNode>): List<LayerNode> {
    val result = mutableListOf<LayerNode>()
    for (node in nodes) {
        result.add(node)
        result.addAll(flattenTree(node.children))
    }
    return result
}
