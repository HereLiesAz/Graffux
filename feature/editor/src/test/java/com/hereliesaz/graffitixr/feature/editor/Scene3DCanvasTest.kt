package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.mesh.SpatialGuide
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.scene.SpatialLayerContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/**
 * The canvas's gestures with no 3D mode (spec/3d-space.md): two fingers flow continuously into
 * one gesture, and one finger on a 3D strokes layer draws its curves with the brush.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class Scene3DCanvasTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val strokes = mutableListOf<SpatialStroke>()
    private val actions = object : Scene3DActions {
        override fun spatialStroke(layerId: String, stroke: SpatialStroke) { strokes += stroke }
        override fun spatialErase(layerId: String, strokeId: String) { calls += "erase" }
        override fun guideChanged(layerId: String, guide: SpatialGuide) { calls += "guide" }
    }

    private val ink = Layer("ink", "3D strokes", spatial = SpatialLayerContent())
    private val base = EditorUiState(
        layers = listOf(ink),
        activeLayerId = "ink",
        canvasSize = IntSize(1000, 2000),
    )

    @Test
    fun `the brush on a 3D strokes layer draws one curve on its guide`() {
        val state = base.copy(activeTool = Tool.BRUSH)
        assertEquals(SpatialStrokeTool.DRAW, state.spatialStrokeTool)
        rule.setContent { Scene3DCanvas(state, actions, mutableStateOf(null), Modifier.fillMaxSize()) }
        rule.onNodeWithTag("scene3d.canvas").performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 30f))
            up()
        }
        rule.waitForIdle()
        assertEquals(1, strokes.size)
        assertTrue(strokes.single().points.size >= 2)
    }

    @Test
    fun `only the brush or eraser on a 3D strokes layer takes one finger`() {
        assertEquals(SpatialStrokeTool.ERASE, base.copy(activeTool = Tool.ERASER).spatialStrokeTool)
        assertNull(base.copy(activeTool = Tool.NONE).spatialStrokeTool)
        val flat = base.copy(layers = listOf(Layer("a", "A")), activeLayerId = "a", activeTool = Tool.BRUSH)
        assertNull(flat.spatialStrokeTool)
    }

    @Test
    fun `the 3D mode strip is gone`() {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                Scene3DToolbar(base.copy(activeTool = Tool.NONE), actions)
                Text("host", Modifier.testTag("host"))
            }
        }
        listOf("orbit", "move", "depth", "tilt", "spin").forEach {
            rule.onNodeWithTag("scene3d.tool.$it").assertDoesNotExist()
        }
        rule.onNodeWithTag("scene3d.toolbar").assertDoesNotExist()
        rule.onNodeWithTag("scene3d.resetView").assertDoesNotExist()
        rule.onNodeWithTag("scene3d.guide").assertDoesNotExist()
    }

    /**
     * Regression for the stepped 3D gestures: the gesture is keyed so that a state write on every
     * step (as the camera's is) recomposes without restarting it, so every move arrives, the
     * total follows the fingers, and the gesture opens and closes exactly once.
     */
    private val total = mutableFloatStateOf(0f)
    private val seen = mutableListOf<Float>()
    private val lifecycle = mutableListOf<String>()

    private fun host() {
        rule.setContent {
            // Reads the state the handler writes, so every step recomposes this host.
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("nav")
                    .canvasNavigation(
                        onStart = { lifecycle += "start" },
                        onEnd = { lifecycle += "end" },
                    ) { _, _, _, rotation ->
                        total.floatValue += rotation
                        seen += total.floatValue
                    },
            ) { Text("turned ${total.floatValue}") }
        }
    }

    /** A two-finger twist of [steps] moves, [degreesPerStep] each; returns the total turn. */
    private fun twist(steps: Int, degreesPerStep: Double): Float {
        total.floatValue = 0f
        seen.clear()
        lifecycle.clear()
        val node = rule.onNodeWithTag("nav")
        var c = Offset.Zero
        node.performTouchInput { c = center; down(0, c - Offset(100f, 0f)); down(1, c + Offset(100f, 0f)) }
        rule.waitForIdle()
        repeat(steps) { i ->
            val a = Math.toRadians(degreesPerStep * (i + 1))
            val d = Offset((100 * cos(a)).toFloat(), (100 * sin(a)).toFloat())
            node.performTouchInput { updatePointerTo(0, c - d); updatePointerTo(1, c + d); move() }
            rule.waitForIdle()
        }
        node.performTouchInput { up(0); up(1) }
        rule.waitForIdle()
        return total.floatValue
    }

    @Test
    fun `a two-finger twist moves progressively through the whole gesture as one gesture`() {
        host()
        val turned = twist(steps = 8, degreesPerStep = 5.0)
        assertTrue("every move should arrive, got ${seen.size}", seen.size >= 8)
        assertTrue("the turn should grow step by step: $seen", seen.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(40f, turned, 1f)
        assertEquals(listOf("start", "end"), lifecycle)
    }

    @Test
    fun `a longer twist turns further than a short one`() {
        host()
        val short = twist(steps = 2, degreesPerStep = 5.0)
        val long = twist(steps = 10, degreesPerStep = 5.0)
        assertTrue("long $long should exceed short $short", long > short * 3)
    }
}
