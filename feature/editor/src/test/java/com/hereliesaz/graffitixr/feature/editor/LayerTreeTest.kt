package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Test

class LayerTreeTest {
    private fun l(id: String, parent: String? = null) = Layer(id = id, name = id, parentId = parent)

    @Test fun `collects descendants at every depth`() {
        val layers = listOf(l("g"), l("a", "g"), l("b", "a"), l("c", "b"), l("x"))
        assertEquals(setOf("a", "b", "c"), descendantLayerIds(layers, "g"))
    }

    @Test fun `a corrupt parent cycle ends instead of overflowing the stack`() {
        val layers = listOf(l("g", "b"), l("a", "g"), l("b", "a"))
        assertEquals(setOf("a", "b"), descendantLayerIds(layers, "g"))
    }
}
