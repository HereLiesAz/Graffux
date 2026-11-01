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
import androidx.compose.ui.test.performClick
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.feature.editor.CanvasGestureMode
import com.hereliesaz.graffitixr.feature.editor.canvasGestureMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rail's 3D button switches what the canvas's gestures do, and turning it off moves nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class Scene3DRailUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(EditorUiState(activeTool = Tool.BRUSH))

    private val actions = object : ToolRailActions {
        override fun setActiveTool(tool: Tool) = Unit
        override fun onColorClicked() = Unit
        override fun resetViewport() = Unit
        override fun onUndoClicked() = Unit
        override fun onRedoClicked() = Unit
        override fun setBrushSize(size: Float) = Unit
        override fun setBrushFeathering(feathering: Float) = Unit
        // What EditorViewModel.toggleScene3D writes.
        override fun toggleScene3D() { state = state.copy(is3DMode = !state.is3DMode) }
    }

    @Test
    fun `the 3D button switches the canvas between painting and the 3D camera`() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ToolRail(actions, state, emptySet(), emptySet(), ToolRailCallbacks({}, {}, {}))
            }
        }
        assertEquals(CanvasGestureMode.PAINT, state.canvasGestureMode)

        rule.onNodeWithTag("rail.$SCENE_3D_RAIL_ID").performClick()
        rule.waitForIdle()
        assertEquals(CanvasGestureMode.SCENE_3D, state.canvasGestureMode)

        // The user orbits; leaving 3D keeps the view where it was.
        state = state.copy(sceneCamera = SceneCamera(yaw = 0.5f))
        rule.onNodeWithTag("rail.$SCENE_3D_RAIL_ID").performClick()
        rule.waitForIdle()
        assertEquals(CanvasGestureMode.PAINT, state.canvasGestureMode)
        assertEquals(0.5f, state.sceneCamera.yaw)
        assertEquals(Tool.BRUSH, state.activeTool)
    }
}
