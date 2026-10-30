package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer

/**
 * Every layer under [parentId], at any depth. Iterative with a visited set, so a corrupt `parentId`
 * cycle (a group that is its own ancestor) ends instead of overflowing the stack.
 */
internal fun descendantLayerIds(layers: List<Layer>, parentId: String): Set<String> {
    val children = layers.groupBy({ it.parentId }, { it.id })
    val found = LinkedHashSet<String>()
    val queue = ArrayDeque(children[parentId].orEmpty())
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (id == parentId || !found.add(id)) continue
        queue.addAll(children[id].orEmpty())
    }
    return found
}
