package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.VectorShape
import com.hereliesaz.graffitixr.common.model.ShapeKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthCompositeTest {
    private val shape = listOf(VectorShape(ShapeKind.RECTANGLE))
    private fun paint(id: String, parent: String? = null) = Layer(id, id, parentId = parent, shapes = shape)
    private val ink = Layer("ink", "3D strokes", spatial = SpatialLayerContent())

    @Test
    fun `a flat document has no depth pass, so the 2D path is untouched`() {
        assertTrue(DepthComposite.plan(listOf(paint("a"), paint("b")), SceneCamera()).isEmpty)
    }

    @Test
    fun `planes in 3D and 3D ink share one depth pass, anchored at the first`() {
        val layers = listOf(paint("flat"), paint("a").copy(rotationY = 30f), ink, paint("b").copy(positionZ = 50f))
        val plan = DepthComposite.plan(layers, SceneCamera())
        assertEquals(listOf("a", "ink", "b"), plan.memberIds)
        assertEquals("a", plan.anchorTopLevelId)
        // Under a moved camera every plain layer can cross another, so all of them join.
        assertEquals(listOf("flat", "a", "ink", "b"), DepthComposite.plan(layers, SceneCamera(yaw = 0.2f)).memberIds)
    }

    @Test
    fun `blend modes, clipping masks and clipped frames stay in the stack`() {
        val camera = SceneCamera(yaw = 0.3f)
        val layers = listOf(
            paint("multiply").copy(blendMode = BlendMode.Multiply),
            paint("clipped").copy(clipToLayerBelow = true),
            Layer("frame", "F", type = LayerType.FRAME, clipsContent = true, layoutWidth = 10f, layoutHeight = 10f),
            paint("inFrame", parent = "frame"),
            Layer("g", "G", type = LayerType.GROUP),
            paint("inGroup", parent = "g"),
        )
        assertEquals(listOf("inGroup"), DepthComposite.plan(layers, camera).memberIds)
    }

    @Test
    fun `3D ink in a group placed in 3D joins the pass at the group's placement`() {
        val layers = listOf(
            Layer("g", "G", type = LayerType.GROUP, positionZ = 400f, rotationY = 20f),
            ink.copy(parentId = "g"),
        )
        val plan = DepthComposite.plan(layers, SceneCamera())
        assertEquals(listOf("ink"), plan.memberIds)
        assertEquals("g", plan.anchorTopLevelId)
        // The ink's model is the whole chain: the group's depth moves it.
        val model = SceneMath.chainModel(planeChain(layers, "ink"), 1000f, 1000f)
        assertEquals(400f, model.apply(500f, 500f, 0f)[2], 1e-3f)
        assertEquals(0.5f * 0.5f, DepthComposite.effectiveOpacity(
            listOf(Layer("g", "G", type = LayerType.GROUP, opacity = 0.5f), paint("c", "g").copy(opacity = 0.5f)), "c",
        ))
    }

    @Test
    fun `hidden layers are not drawn`() {
        val layers = listOf(paint("a").copy(rotationX = 5f, isVisible = false), ink.copy(isVisible = false))
        assertTrue(DepthComposite.plan(layers, SceneCamera()).isEmpty)
    }

    @Test
    fun `the image sits in the box where the canvas draws it, and planes sort farthest first`() {
        assertArrayEquals(floatArrayOf(0f, 250f, 1000f, 750f), DepthComposite.imageRect(Layer("a", "A"), 200, 100, 1000f, 1000f), 1e-3f)
        assertEquals(listOf("far", "mid", "near"), DepthComposite.backToFront(mapOf("near" to 1f, "far" to 9f, "mid" to 5f), listOf("near", "far", "mid")))
    }
}
