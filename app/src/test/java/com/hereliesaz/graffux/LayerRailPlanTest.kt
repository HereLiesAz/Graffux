package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Test

/** [layerRailRows]: group layers are rail hosts; their children are sub-items of that host. */
class LayerRailPlanTest {

    private fun lyr(id: String, parent: String? = null) = Layer(id = id, name = id, parentId = parent)
    private fun grp(id: String, parent: String? = null) =
        Layer(id = id, name = id, type = LayerType.GROUP, parentId = parent)

    @Test
    fun `flat stack is declared top-first under the layers host`() {
        val rows = layerRailRows(listOf(lyr("a"), lyr("b"), lyr("c")))
        assertEquals(listOf("c", "b", "a"), rows.map { it.layer.id })
        assertEquals(setOf(LAYERS_HOST_ID), rows.map { it.hostId }.toSet())
    }

    @Test
    fun `a group's children are sub-items of the group's own host id`() {
        val layers = listOf(lyr("x"), grp("g"), lyr("g1", "g"), lyr("g2", "g"), lyr("y"))
        val rows = layerRailRows(layers)
        // Every top-level row first (so the parent's reloc run isn't split by foreign items),
        // then the group's children, top-first.
        assertEquals(listOf("y", "g", "x", "g2", "g1"), rows.map { it.layer.id })
        assertEquals(
            listOf(LAYERS_HOST_ID, LAYERS_HOST_ID, LAYERS_HOST_ID, "layer.g", "layer.g"),
            rows.map { it.hostId },
        )
        assertEquals(listOf(false, true, false, false, false), rows.map { it.isGroup })
    }

    @Test
    fun `nested groups host their own children`() {
        val layers = listOf(grp("g"), grp("h", "g"), lyr("h1", "h"), lyr("g1", "g"))
        val rows = layerRailRows(layers)
        assertEquals(listOf("g", "g1", "h", "h1"), rows.map { it.layer.id })
        assertEquals(listOf(LAYERS_HOST_ID, "layer.g", "layer.g", "layer.h"), rows.map { it.hostId })
    }
}
