package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.mesh.SpatialGuide
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.scene.Scene3DTool
import com.hereliesaz.graffitixr.common.scene.SpatialLayerContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** With 3D on, a one-finger drag drives the tool picked in the 3D strip, never the brush. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class Scene3DCanvasTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val strokes = mutableListOf<SpatialStroke>()
    private val actions = object : Scene3DActions {
        override fun setTool(tool: Scene3DTool) { calls += "tool:$tool" }
        override fun orbit(delta: Offset) { calls += "orbit" }
        override fun cameraSettled() { calls += "settled" }
        override fun resetCamera() { calls += "resetCamera" }
        override fun planeStart() { calls += "start" }
        override fun planeDrag(delta: Offset) { calls += "drag" }
        override fun planeEnd() { calls += "end" }
        override fun resetPlane() { calls += "resetPlane" }
        override fun spatialStroke(layerId: String, stroke: SpatialStroke) { strokes += stroke }
        override fun spatialErase(layerId: String, strokeId: String) { calls += "erase" }
        override fun guideChanged(layerId: String, guide: SpatialGuide) { calls += "guide" }
    }

    private fun drag(state: EditorUiState) {
        rule.setContent { Scene3DCanvas(state, actions, mutableStateOf(null), Modifier.fillMaxSize()) }
        rule.onNodeWithTag("scene3d.canvas").performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 30f))
            up()
        }
        rule.waitForIdle()
    }

    private val base = EditorUiState(
        layers = listOf(Layer("a", "A")),
        activeLayerId = "a",
        is3DMode = true,
        canvasSize = IntSize(1000, 2000),
    )

    @Test
    fun `orbit turns the camera and saves the view at the end`() {
        drag(base.copy(scene3DTool = Scene3DTool.ORBIT))
        assertTrue(calls.count { it == "orbit" } >= 1)
        assertEquals("settled", calls.last())
        assertTrue("start" !in calls)
    }

    @Test
    fun `move brackets one plane drag for undo`() {
        drag(base.copy(scene3DTool = Scene3DTool.MOVE))
        assertEquals("start", calls.first())
        assertTrue("drag" in calls)
        assertEquals("end", calls.last())
        assertTrue("orbit" !in calls)
    }

    @Test
    fun `draw on a 3D strokes layer commits one curve on its guide`() {
        val ink = Layer("ink", "3D strokes", spatial = SpatialLayerContent())
        drag(base.copy(layers = listOf(ink), activeLayerId = "ink", scene3DTool = Scene3DTool.DRAW))
        assertEquals(1, strokes.size)
        assertTrue(strokes.single().points.size >= 2)
        assertTrue(strokes.single().width > 0f)
    }
}
