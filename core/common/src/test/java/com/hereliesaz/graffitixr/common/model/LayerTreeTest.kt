package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LayerTreeTest {

    @Test
    fun `buildLayerTree preserves child subtrees of orphaned branches`() {
        // C and D form a parent-child branch, but parent X is missing from the project
        val layerC = Layer(id = "C", name = "C", parentId = "X")
        val layerD = Layer(id = "D", name = "D", parentId = "C")

        val tree = buildLayerTree(listOf(layerC, layerD))

        assertEquals(1, tree.size)
        val rootC = tree[0]
        assertEquals("C", rootC.layer.id)
        assertEquals(1, rootC.children.size)
        assertEquals("D", rootC.children[0].layer.id)
    }

    @Test
    fun `buildLayerTree preserves child subtrees when child precedes parent in input list`() {
        val layerC = Layer(id = "C", name = "C", parentId = "X")
        val layerD = Layer(id = "D", name = "D", parentId = "C")

        val tree = buildLayerTree(listOf(layerD, layerC))

        assertEquals(1, tree.size)
        val rootC = tree[0]
        assertEquals("C", rootC.layer.id)
        assertEquals(1, rootC.children.size)
        assertEquals("D", rootC.children[0].layer.id)
    }
}
