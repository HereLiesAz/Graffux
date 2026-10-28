package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.photo.PhotoAdjustment
import com.hereliesaz.graffitixr.common.photo.PhotoSliders
import com.hereliesaz.graffitixr.common.photo.PhotoTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What an adjustment layer is rendered from, and when it has to be rendered again. */
class AdjustmentLayerRendererTest {

    private val light = PhotoAdjustment(PhotoTool.LIGHT)

    @Test
    fun `an adjustment layer adjusts the layers before it in its own parent`() {
        val layers = listOf(
            Layer(id = "photo", name = "photo"),
            Layer(id = "g", name = "g", type = LayerType.GROUP),
            Layer(id = "g1", name = "g1", parentId = "g"),
            Layer(id = "inner", name = "inner", parentId = "g", adjustment = light),
            Layer(id = "g2", name = "g2", parentId = "g"),
            Layer(id = "outer", name = "outer", adjustment = light),
            Layer(id = "hidden", name = "hidden", adjustment = light, isVisible = false),
            Layer(id = "top", name = "top"),
        )
        val plan = AdjustmentLayerRenderer.plan(layers).associate { (l, below) -> l.id to below.map { it.id } }
        assertEquals(setOf("inner", "outer"), plan.keys)
        // Inside the group: only its siblings below, lifted to the top level of the composite.
        assertEquals(listOf("g1"), plan["inner"])
        // At the top level: everything before it, the group with all it holds.
        assertEquals(listOf("photo", "g", "g1", "inner", "g2"), plan["outer"])
        val inner = AdjustmentLayerRenderer.plan(layers).first { it.first.id == "inner" }.second
        assertEquals(null, inner.single().parentId)
    }

    @Test
    fun `the source changes with what is below, the render with the settings too`() {
        val below = listOf(Layer(id = "photo", name = "photo"))
        val key = AdjustmentLayerRenderer.sourceKey(below)
        assertEquals(key, AdjustmentLayerRenderer.sourceKey(listOf(Layer(id = "photo", name = "photo"))))
        assertNotEquals(key, AdjustmentLayerRenderer.sourceKey(listOf(below[0].copy(opacity = 0.5f))))
        assertNotEquals(key, AdjustmentLayerRenderer.sourceKey(listOf(below[0].copy(isVisible = false))))
        val layer = Layer(id = "adj", name = "Light", adjustment = light)
        val r = AdjustmentLayerRenderer.renderKey(layer, key)
        assertEquals(r, AdjustmentLayerRenderer.renderKey(layer.copy(name = "renamed").copy(adjustment = light), key))
        val brighter = layer.copy(adjustment = light.with(PhotoSliders.EXPOSURE, 1f))
        assertNotEquals(r, AdjustmentLayerRenderer.renderKey(brighter, key))
        assertNotEquals(r, AdjustmentLayerRenderer.renderKey(layer, key + 1))
    }
}
