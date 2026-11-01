package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.isPlacedIn3D
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneRenderPathTest {
    private val flat = listOf(
        Layer("a", "A", offset = Offset(20f, 5f), rotationZ = 45f, scale = 2f),
        Layer("g", "G", type = LayerType.GROUP),
        Layer("c", "C", parentId = "g"),
    )

    @Test
    fun `nothing in 3D and the default camera keep every layer on the identity fast path`() {
        assertEquals(emptySet<String>(), SceneRenderPath.sceneLayerIds(flat, SceneCamera()))
        assertTrue(SceneRenderPath.isFlat(flat, SceneCamera()))
        assertFalse(flat.any { it.isPlacedIn3D })
    }

    @Test
    fun `a layer moved in 3D takes its whole top-level subtree to the scene path, nothing else`() {
        val layers = flat.map { if (it.id == "c") it.copy(positionZ = 10f) else it }
        assertEquals(setOf("g", "c"), SceneRenderPath.sceneLayerIds(layers, SceneCamera()))
        assertTrue(SceneRenderPath.isLayerFlat(layers, "a", SceneCamera()))
        assertFalse(SceneRenderPath.isFlat(layers, SceneCamera()))
    }

    @Test
    fun `a moved camera sees every layer in perspective`() {
        assertEquals(setOf("a", "g", "c"), SceneRenderPath.sceneLayerIds(flat, SceneCamera(yaw = 0.1f)))
    }

    @Test
    fun `becoming 3D needs no toggle, only a placement`() {
        val tilted = Layer("t", "T", rotationX = 5f)
        assertTrue(tilted.isPlacedIn3D)
        assertFalse(Layer("t", "T").isPlacedIn3D)
    }

    @Test
    fun `a layer from an older build that still carries the retired is3D key decodes, flat`() {
        val json = kotlinx.serialization.json.Json
        val old = """{"id":"x","name":"X","is3D":true}"""
        val layer = json.decodeFromString(Layer.serializer(), old)
        assertEquals("x", layer.id)
        assertFalse(layer.isPlacedIn3D)
    }

    @Test
    fun `the plane chain runs from the outermost group to the layer`() {
        val layers = listOf(
            Layer("g", "G", type = LayerType.GROUP, positionZ = 3f),
            Layer("c", "C", parentId = "g", rotationY = 7f),
        )
        assertEquals(listOf(3f, 0f), planeChain(layers, "c").map { it.positionZ })
        assertEquals(listOf(0f, 7f), planeChain(layers, "c").map { it.rotationY })
    }
}
