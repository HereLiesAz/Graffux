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

    // Collect any orphaned or cyclic subtrees so no layer is dropped and internal parent-child relations are preserved
    for (layer in layers) {
        if (layer.id !in visitedIds) {
            visitedIds.add(layer.id)
            rootNodes.add(LayerNode(layer, 0, build(layer.id, 1, setOf(layer.id))))
        }
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
