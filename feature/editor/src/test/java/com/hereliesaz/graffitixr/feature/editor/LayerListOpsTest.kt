package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerListOpsTest {

    private fun lyr(id: String, name: String = id) = Layer(id = id, name = name)

    @Test
    fun `reorder arranges layers to match the id order`() {
        val layers = listOf(lyr("a"), lyr("b"), lyr("c"))
        assertEquals(listOf("c", "a", "b"), LayerListOps.reorder(layers, listOf("c", "a", "b")).map { it.id })
    }

    @Test
    fun `reorder drops ids not present in the layer list`() {
        val layers = listOf(lyr("a"), lyr("b"))
        assertEquals(listOf("b", "a"), LayerListOps.reorder(layers, listOf("ghost", "b", "a")).map { it.id })
    }

    @Test
    fun `reorder falls back to the original list when ids don't cover every layer`() {
        // Regression test: this is exactly what a caller-side id-scheme mismatch (e.g. a prefixed
        // rail item id like "layer.a" instead of "a") produces — every entry misses, and the old
        // behavior silently returned an empty list, which the caller then persisted, permanently
        // deleting every layer from a single drag.
        val layers = listOf(lyr("a"), lyr("b"), lyr("c"))
        assertEquals(layers, LayerListOps.reorder(layers, listOf("layer.c", "layer.a", "layer.b")))
    }

    @Test
    fun `reorder falls back to the original list when only some ids match`() {
        // A partial match (one real layer's id missing) is just as much data loss as none
        // matching, and just as much a sign of a mismatch rather than a deliberate 2-of-3 reorder.
        val layers = listOf(lyr("a"), lyr("b"), lyr("c"))
        assertEquals(layers, LayerListOps.reorder(layers, listOf("c", "a")))
    }

    @Test
    fun `reorder of an empty layer list is a no-op, not a fallback`() {
        assertEquals(emptyList<Layer>(), LayerListOps.reorder(emptyList(), listOf("a", "b")))
    }

    @Test
    fun `reorderSubset reorders only the named layers, leaving the rest in place`() {
        val layers = listOf(lyr("a"), lyr("b"), lyr("c"), lyr("d"))
        // Reorder just b and d (a group's children scattered through the flat list) to d, b.
        val out = LayerListOps.reorderSubset(layers, listOf("d", "b"))
        assertEquals(listOf("a", "d", "c", "b"), out.map { it.id })
    }

    @Test
    fun `reorderSubset covering the whole list behaves like a full reorder`() {
        val layers = listOf(lyr("a"), lyr("b"), lyr("c"))
        val out = LayerListOps.reorderSubset(layers, listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), out.map { it.id })
    }

    @Test
    fun `reorderSubset is a no-op on a duplicate or unknown id`() {
        val layers = listOf(lyr("a"), lyr("b"))
        assertEquals(layers, LayerListOps.reorderSubset(layers, listOf("a", "a")))
        assertEquals(layers, LayerListOps.reorderSubset(layers, listOf("a", "ghost")))
    }

    @Test
    fun `mapLayer transforms only the matching layer`() {
        val layers = listOf(lyr("a"), lyr("b"))
        val out = LayerListOps.mapLayer(layers, "a") { it.copy(opacity = 0.5f) }
        assertEquals(0.5f, out.first { it.id == "a" }.opacity)
        assertEquals(1.0f, out.first { it.id == "b" }.opacity)
    }

    @Test
    fun `mapLayer with an unknown id leaves the list unchanged`() {
        val layers = listOf(lyr("a"), lyr("b"))
        assertEquals(layers, LayerListOps.mapLayer(layers, "ghost") { it.copy(opacity = 0f) })
    }

    @Test
    fun `rename changes only the target layer's name`() {
        val layers = listOf(lyr("a", "Alpha"), lyr("b", "Beta"))
        val out = LayerListOps.rename(layers, "a", "Renamed")
        assertEquals("Renamed", out.first { it.id == "a" }.name)
        assertEquals("Beta", out.first { it.id == "b" }.name)
    }

    @Test
    fun `toggleVisibility flips only the target layer`() {
        val layers = listOf(lyr("a"), lyr("b")) // isVisible defaults true
        val out = LayerListOps.toggleVisibility(layers, "a")
        assertFalse(out.first { it.id == "a" }.isVisible)
        assertTrue(out.first { it.id == "b" }.isVisible)
    }

    @Test
    fun `group creates a group layer and reparents both members onto it`() {
        val layers = listOf(lyr("a"), lyr("b"), lyr("c"))
        val out = LayerListOps.group(layers, "a", "b", "g1", "Group")
        val group = out.first { it.id == "g1" }
        assertEquals(com.hereliesaz.graffitixr.common.model.LayerType.GROUP, group.type)
        assertEquals("g1", out.first { it.id == "a" }.parentId)
        assertEquals("g1", out.first { it.id == "b" }.parentId)
        assertEquals(null, out.first { it.id == "c" }.parentId)
        assertEquals(4, out.size)
    }

    @Test
    fun `group is a no-op when either id is missing or the group id collides`() {
        val layers = listOf(lyr("a"), lyr("b"))
        assertEquals(layers, LayerListOps.group(layers, "a", "ghost", "g1", "Group"))
        assertEquals(layers, LayerListOps.group(layers, "a", "b", "a", "Group"))
    }

    @Test
    fun `ungroup reparents children onto the group's own parent and removes the group`() {
        val layers = listOf(lyr("a"), lyr("b"), lyr("c"))
        val grouped = LayerListOps.group(layers, "a", "b", "g1", "Group")
        val out = LayerListOps.ungroup(grouped, "g1")
        assertEquals(null, out.first { it.id == "a" }.parentId)
        assertEquals(null, out.first { it.id == "b" }.parentId)
        assertEquals(3, out.size) // the group layer itself is gone
        assertTrue(out.none { it.id == "g1" })
    }

    @Test
    fun `ungroup is a no-op for an id that isn't a group`() {
        val layers = listOf(lyr("a"), lyr("b"))
        assertEquals(layers, LayerListOps.ungroup(layers, "a"))
    }
}
