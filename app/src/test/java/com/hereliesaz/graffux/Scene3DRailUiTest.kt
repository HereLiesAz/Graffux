@file:Suppress("MaxLineLength")

package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
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
        // Only the Fit test uses this fake; the axis slot is tested against a real view-model.
        override fun onAxisSlotTapped() = Unit
    }

    /** A real view-model: the rail's axis slot runs its own onAxisSlotTapped, nothing faked. */
    private fun realViewModel(): com.hereliesaz.graffitixr.feature.editor.EditorViewModel {
        val projectRepository = io.mockk.mockk<com.hereliesaz.graffitixr.domain.repository.ProjectRepository>(relaxed = true)
        io.mockk.every { projectRepository.currentProject } returns
            kotlinx.coroutines.flow.MutableStateFlow(com.hereliesaz.graffitixr.common.model.GraffitiProject(id = "p"))
        val settings = io.mockk.mockk<com.hereliesaz.graffitixr.domain.repository.SettingsRepository>(relaxed = true)
        io.mockk.every { settings.backgroundColor } returns kotlinx.coroutines.flow.flowOf(0xFF000000.toInt())
        val figma = io.mockk.mockk<com.hereliesaz.graffitixr.data.figma.FigmaRepository>(relaxed = true)
        io.mockk.every { figma.isAuthenticated } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        val dispatchers = object : com.hereliesaz.graffitixr.common.DispatcherProvider {
            override val main = kotlinx.coroutines.Dispatchers.Main
            override val io = kotlinx.coroutines.Dispatchers.Main
            override val default = kotlinx.coroutines.Dispatchers.Main
            override val unconfined = kotlinx.coroutines.Dispatchers.Unconfined
        }
        return com.hereliesaz.graffitixr.feature.editor.EditorViewModel(
            projectRepository, settings, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
            rule.activity.applicationContext, io.mockk.mockk(relaxed = true),
            dispatchers, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true), figma, io.mockk.mockk(relaxed = true),
        )
    }

    @Test
    fun `the axis slot reads 3D while the layer's group is flat, the axis once it has a surface`() {
        val vm = realViewModel()
        // Let the project load first, as the editor does, so it does not replace these layers.
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        vm.setLayers(listOf(Layer("g", "G", type = LayerType.GROUP), Layer("a", "A", parentId = "g")))
        vm.onLayerActivated("a")
        vm.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("a", vm.uiState.value.activeLayerId)
        assertEquals(listOf("g", "a"), vm.uiState.value.layers.map { it.id })
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by vm.uiState.collectAsState()
                ToolRail(vm.toolRailActions(), ui, emptySet(), emptySet(), ToolRailCallbacks({}, {}, {}))
            }
        }
        val slot = rule.onNodeWithTag("rail.$AXIS_RAIL_ID")
        rule.onNodeWithText("3D", useUnmergedTree = true).assertExists()
        // A tap on a flat layer arms Draw surface on its group; the armed stroke gives it a surface.
        slot.performClick()
        rule.waitForIdle()
        assertEquals("g", vm.uiState.value.surfaceDrawGroupId)
        rule.runOnIdle { vm.onSurfaceProfileDrawn(listOf(Offset(100f, 900f), Offset(400f, 700f), Offset(700f, 900f))) }
        rule.waitForIdle()
        val layers = vm.uiState.value.layers
        assertTrue(layers.first { it.id == "a" }.is3DIn(layers))
        rule.onNodeWithText("3D", useUnmergedTree = true).assertDoesNotExist()
        val start = vm.uiState.value.activeRotationAxis
        val seen = (0 until 3).map {
            slot.performClick()
            rule.waitForIdle()
            rule.onNodeWithText(vm.uiState.value.activeRotationAxis.name, useUnmergedTree = true).assertExists()
            vm.uiState.value.activeRotationAxis
        }
        assertEquals((1..3).map { RotationAxis.entries[(start.ordinal + it) % 3] }, seen)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF39FF14), RotationAxis.Z.railColor())
    }

    @Test
    fun `the axis slot on an ungrouped layer groups it and arms Draw surface`() {
        val vm = realViewModel()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        vm.setLayers(listOf(Layer("a", "A")))
        vm.onLayerActivated("a")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by vm.uiState.collectAsState()
                ToolRail(vm.toolRailActions(), ui, emptySet(), emptySet(), ToolRailCallbacks({}, {}, {}))
            }
        }
        rule.onNodeWithTag("rail.$AXIS_RAIL_ID").performClick()
        rule.waitForIdle()
        val group = vm.uiState.value.layers.first { it.id == vm.uiState.value.layers.first { l -> l.id == "a" }.parentId }
        assertEquals(LayerType.GROUP, group.type)
        assertEquals(group.id, vm.uiState.value.surfaceDrawGroupId)
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
