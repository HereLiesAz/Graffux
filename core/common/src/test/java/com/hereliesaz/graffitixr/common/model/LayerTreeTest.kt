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

    @Test
    fun `buildLayerTree keeps every layer of a parent cycle instead of dropping them`() {
        // A and B name each other as parent. Neither is reachable from a real root, and neither can
        // be reached through the other, so the orphan pass defers both forever — which used to mean
        // the tree came back EMPTY and both layers vanished from the panel, the frame count and the
        // export.
        val a = Layer(id = "A", name = "A", parentId = "B")
        val b = Layer(id = "B", name = "B", parentId = "A")

        val tree = buildLayerTree(listOf(a, b))

        assertEquals(listOf("A", "B"), flattenTree(tree).map { it.layer.id }.sorted())
        // The cycle is broken at its first member in list order, which becomes the root.
        assertEquals(1, tree.size)
        assertEquals("A", tree[0].layer.id)
        assertEquals("B", tree[0].children.single().layer.id)
    }

    @Test
    fun `buildLayerTree keeps a self-parented layer`() {
        val self = Layer(id = "S", name = "S", parentId = "S")

        val tree = buildLayerTree(listOf(self))

        assertEquals(1, tree.size)
        assertEquals("S", tree[0].layer.id)
        assertEquals(0, tree[0].children.size)
    }

    @Test
    fun `buildLayerTree keeps a cycle alongside a healthy root`() {
        val root = Layer(id = "R", name = "R")
        val a = Layer(id = "A", name = "A", parentId = "B")
        val b = Layer(id = "B", name = "B", parentId = "A")

        val tree = buildLayerTree(listOf(root, a, b))

        assertEquals(listOf("A", "B", "R"), flattenTree(tree).map { it.layer.id }.sorted())
    }
}
