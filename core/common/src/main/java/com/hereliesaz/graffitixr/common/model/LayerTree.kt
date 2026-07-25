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
    // `ancestry` guards against a cyclic parentId chain (e.g. A's parent is B and B's parent is A)
    // recursing forever. Not reachable from a normally-edited project today, but nothing upstream
    // validates parentId, so a malformed project file or an inconsistent co-op Op could produce one.
    fun build(parentId: String?, depth: Int, ancestry: Set<String>): List<LayerNode> {
        return (byParent[parentId] ?: emptyList()).mapNotNull { layer ->
            if (layer.id in ancestry) {
                null
            } else {
                LayerNode(layer, depth, build(layer.id, depth + 1, ancestry + layer.id))
            }
        }
    }
    return build(null, 0, emptySet())
}

/**
 * Flattens a layer tree back into a list, usually for UI display where nested
 * items need to be shown in a flat scrolling list.
 */
fun flattenTree(nodes: List<LayerNode>): List<LayerNode> {
    val result = mutableListOf<LayerNode>()
    for (node in nodes.reversed()) {
        result.add(node)
        result.addAll(flattenTree(node.children))
    }
    return result
}
