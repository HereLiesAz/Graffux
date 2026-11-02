@file:Suppress("MaxLineLength")

package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.is3DIn
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * No 3D mode (spec/3d-space.md): the rail's old 3D slot is the axis slot ("3D" on a 2D layer,
 * the axis on a 3D one), and Fit is there whenever the 3D camera
 * has moved, even with the 2D view untouched, and puts it back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class Scene3DRailUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(EditorUiState(activeTool = Tool.BRUSH))

    private val actions = object : ToolRailActions {
        override fun setActiveTool(tool: Tool) = Unit
        override fun onColorClicked() = Unit
        // What EditorViewModel.resetViewport writes for the camera.
        override fun resetViewport() { state = state.copy(sceneCamera = SceneCamera()) }
        override fun onUndoClicked() = Unit
        override fun onRedoClicked() = Unit
        override fun setBrushSize(size: Float) = Unit
        override fun setBrushFeathering(feathering: Float) = Unit
        // What EditorViewModel.onAxisSlotTapped leads to: on a 2D layer, its group gets a surface (once
        // the armed stroke is drawn); on a 3D one the axis cycles.
        override fun onAxisSlotTapped() {
            val layer = state.layers.first { it.id == state.activeLayerId }
            state = if (!layer.is3DIn(state.layers)) {
                state.copy(
                    layers = state.layers.map { if (it.id == layer.parentId) it.copy(surface = SurfaceProfile(listOf(0f, 0f, 0f, 1f, 0f, 0f))) else it },
                    activeRotationAxis = RotationAxis.Z,
                )
            } else {
                val next = RotationAxis.entries[(state.activeRotationAxis.ordinal + 1) % RotationAxis.entries.size]
                state.copy(activeRotationAxis = next)
            }
        }
    }

    @Test
    fun `the axis slot reads 3D while the layer's group is flat, the axis once it has a surface`() {
        state = state.copy(
            layers = listOf(Layer("g", "G", type = LayerType.GROUP), Layer("a", "A", parentId = "g")),
            activeLayerId = "a", activeRotationAxis = RotationAxis.Y,
        )
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ToolRail(actions, state, emptySet(), emptySet(), ToolRailCallbacks({}, {}, {}))
            }
        }
        val slot = rule.onNodeWithTag("rail.$AXIS_RAIL_ID")
        rule.onNodeWithText("3D", useUnmergedTree = true).assertExists()
        slot.performClick()
        rule.waitForIdle()
        assertTrue(state.layers.first { it.id == "a" }.is3DIn(state.layers))
        rule.onNodeWithText("Z", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("3D", useUnmergedTree = true).assertDoesNotExist()
        val seen = (0 until 3).map {
            slot.performClick()
            rule.waitForIdle()
            rule.onNodeWithText(state.activeRotationAxis.name, useUnmergedTree = true).assertExists()
            state.activeRotationAxis
        }
        assertEquals(listOf(RotationAxis.X, RotationAxis.Y, RotationAxis.Z), seen)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF39FF14), RotationAxis.Z.railColor())
    }

    @Test
    fun `there is no 3D button, and Fit resets a moved camera`() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ToolRail(actions, state, emptySet(), emptySet(), ToolRailCallbacks({}, {}, {}))
            }
        }
        rule.onNodeWithTag("rail.view.3d").assertDoesNotExist()
        rule.onNodeWithTag("rail.view.fit").assertDoesNotExist()

        state = state.copy(sceneCamera = SceneCamera(yaw = 0.5f))
        rule.waitForIdle()
        rule.onNodeWithTag("rail.view.fit").performClick()
        rule.waitForIdle()
        assertEquals(SceneCamera(), state.sceneCamera)
        rule.onNodeWithTag("rail.view.fit").assertDoesNotExist()
        assertEquals(Tool.BRUSH, state.activeTool)
    }
}
