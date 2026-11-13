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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.performScrollTo
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
    fun `the axis slot reads 3D while the layer's group is flat, and on a surface opens the views chooser instead of cycling`() {
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
        // On a surface the slot no longer shows or cycles an axis: it stays "3D" and a tap opens the chooser.
        rule.onNodeWithText("3D", useUnmergedTree = true).assertExists()
        val axis = vm.uiState.value.activeRotationAxis
        repeat(3) {
            slot.performClick()
            rule.waitForIdle()
            assertEquals(axis, vm.uiState.value.activeRotationAxis)
            assertEquals(it % 2 == 0, vm.uiState.value.surfaceViewsMenuOpen)
        }
        assertEquals(androidx.compose.ui.graphics.Color(0xFF39FF14), RotationAxis.Z.railColor())
    }

    @Test
    fun `the wire mesh toggle is on the rail in a 3D project, selected while the mesh shows`() {
        val vm = realViewModel()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val floor = com.hereliesaz.graffitixr.common.scene.SurfaceProfile(listOf(0f, 800f, 0f, 600f, 800f, 0f))
        vm.setLayers(listOf(Layer("g", "G", type = LayerType.GROUP, surface = floor), Layer("a", "A", parentId = "g"), Layer("x", "X")))
        vm.onLayerActivated("a")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by vm.uiState.collectAsState()
                ToolRail(vm.toolRailActions(), ui, emptySet(), emptySet(), ToolRailCallbacks({}, {}, {}))
            }
        }
        val toggle = rule.onNodeWithTag("rail.$SURFACE_MESH_RAIL_ID")
        toggle.assertExists()
        // On by default (a saved choice is not kept: the toggle is view state, never stored).
        assertEquals(true, vm.uiState.value.surfaceGridVisible)
        toggle.performClick()
        rule.waitForIdle()
        assertEquals(false, vm.uiState.value.surfaceGridVisible)
        rule.onNodeWithTag("rail.$SURFACE_MESH_RAIL_ID").assertExists()
        // In a project with a 3D group the button is there on any layer: its drag sets the draw distance.
        rule.runOnIdle { vm.onLayerActivated("x") }
        rule.waitForIdle()
        rule.onNodeWithTag("rail.$SURFACE_MESH_RAIL_ID").assertExists()
        // With no 3D group at all there is nothing for it to do.
        rule.runOnIdle { vm.setLayers(listOf(Layer("x", "X"))); vm.onLayerActivated("x") }
        rule.waitForIdle()
        rule.onNodeWithTag("rail.$SURFACE_MESH_RAIL_ID").assertDoesNotExist()
        // A placed rotation point must stay reachable (its Reset lives on this button's menu) even
        // with no surface under the active layer; once reset to automatic the button goes again.
        rule.runOnIdle { vm.setManualPivotForTest(listOf(1f, 2f, 3f)) }
        rule.waitForIdle()
        rule.onNodeWithTag("rail.$SURFACE_MESH_RAIL_ID").assertExists()
        rule.runOnIdle { vm.onResetPivot() }
        rule.waitForIdle()
        rule.onNodeWithTag("rail.$SURFACE_MESH_RAIL_ID").assertDoesNotExist()
    }

    // ── The draw distance on the wire-mesh button (spec/3d-space.md, "Draw plane depth") ──

    private fun threeDProject(): com.hereliesaz.graffitixr.feature.editor.EditorViewModel {
        val vm = realViewModel()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val floor = com.hereliesaz.graffitixr.common.scene.SurfaceProfile(listOf(0f, 800f, 0f, 600f, 800f, 0f))
        vm.setLayers(listOf(Layer("g", "G", surface = floor, type = LayerType.GROUP), Layer("x", "X")))
        vm.onLayerActivated("x")
        vm.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        return vm
    }

    private fun showRail(vm: com.hereliesaz.graffitixr.feature.editor.EditorViewModel, onMenu: () -> Unit = {}) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by vm.uiState.collectAsState()
                ToolRail(vm.toolRailActions(), ui, emptySet(), emptySet(), ToolRailCallbacks({}, {}, {}, onMeshMenu = onMenu))
            }
        }
    }

    @Test
    fun `dragging the wire mesh button up sets a nearer draw plane and down a farther one, and a drag is not a tap`() {
        val vm = threeDProject()
        showRail(vm)
        val grid = vm.uiState.value.surfaceGridVisible
        rule.onNodeWithTag("rail.$SURFACE_MESH_RAIL_ID").performTouchInput {
            down(center); moveBy(Offset(0f, -160f)); moveBy(Offset(0f, -160f)); up()
        }
        rule.waitForIdle()
        val nearer = vm.uiState.value.drawPlane!!.depth
        assertEquals("a drag does not toggle the grid", grid, vm.uiState.value.surfaceGridVisible)
        assertTrue(vm.uiState.value.drawPlaneGuideShown)
        rule.onNodeWithTag("rail.$SURFACE_MESH_RAIL_ID").performTouchInput {
            down(center); moveBy(Offset(0f, 200f)); moveBy(Offset(0f, 200f)); moveBy(Offset(0f, 200f)); up()
        }
        rule.waitForIdle()
        assertTrue("down is farther", vm.uiState.value.drawPlane!!.depth > nearer)
    }

    @Test
    fun `a tap still toggles the grid and a long press still opens the menu`() {
        val vm = threeDProject()
        var menus = 0
        showRail(vm) { menus++ }
        rule.onNodeWithTag("rail.$SURFACE_MESH_RAIL_ID").performClick()
        rule.waitForIdle()
        assertEquals(false, vm.uiState.value.surfaceGridVisible)
        assertEquals(null, vm.uiState.value.drawPlane)
        rule.onNodeWithTag("rail.$SURFACE_MESH_RAIL_ID").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(1, menus)
        assertEquals(null, vm.uiState.value.drawPlane)
    }

    @Test
    fun `TalkBack gets the draw distance as a slider`() {
        val vm = threeDProject()
        showRail(vm)
        val node = rule.onNodeWithTag("rail.$SURFACE_MESH_RAIL_ID")
        node.assert(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.9f) }
        rule.waitForIdle()
        assertEquals(0.9f, vm.drawDepthFraction(), 0.02f)
        assertTrue(vm.uiState.value.drawPlane != null)
    }

    @Test
    fun `the menu holds nearer, farther and, once set, reset, each acting on the draw distance`() {
        val vm = threeDProject()
        val strings = com.hereliesaz.graffitixr.design.theme.DrawDepthStrings("Nearer", "Farther", "Reset")
        fun rows() = buildMenu { meshMenu(vm, vm.uiState.value, strings) {} }.filterIsInstance<MenuRow.Action>()
            .filter { it.text in setOf("Nearer", "Farther", "Reset") }
        assertEquals(listOf("Nearer", "Farther"), rows().map { it.text })
        rows().first { it.text == "Farther" }.onClick()
        assertTrue(vm.uiState.value.drawPlane != null)
        assertEquals(listOf("Nearer", "Farther", "Reset"), rows().map { it.text })
        rows().first { it.text == "Reset" }.onClick()
        assertEquals(null, vm.uiState.value.drawPlane)
    }

    @Test
    fun `the axis slot on an ungrouped layer only arms, the layers rail is unchanged and the slot reads Draw`() {
        val vm = realViewModel()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        vm.setLayers(listOf(Layer("a", "A")))
        vm.onLayerActivated("a")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val before = vm.uiState.value.layers
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by vm.uiState.collectAsState()
                ToolRail(vm.toolRailActions(), ui, emptySet(), emptySet(), ToolRailCallbacks({}, {}, {}))
                LayersRail(
                    layers = ui.layers, activeLayerId = ui.activeLayerId, secondary = emptySet(), expansion = emptyMap(),
                    callbacks = LayersRailCallbacks({}, {}, { _, _ -> }, { _, _ -> }, {}),
                )
            }
        }
        rule.onNodeWithTag("rail.layer.a").assertExists()
        rule.onNodeWithTag("rail.$AXIS_RAIL_ID").performClick()
        rule.waitForIdle()
        // Nothing in the document moved; the same one tile is on the rail.
        assertEquals(before, vm.uiState.value.layers)
        assertEquals("a", vm.uiState.value.surfaceDrawWrapLayerId)
        rule.onNodeWithTag("rail.layer.a").assertExists()
        rule.onNodeWithText("Draw", useUnmergedTree = true).assertExists()
        // The capture makes the group: its tile joins the rail, open, with the layer's tile under it.
        rule.runOnIdle { vm.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600)) }
        rule.runOnIdle { vm.onSurfaceProfileDrawn(listOf(Offset(100f, 900f), Offset(400f, 700f), Offset(700f, 900f))) }
        rule.waitForIdle()
        assertEquals(LayerType.GROUP, vm.uiState.value.layers.first { it.id == vm.uiState.value.layers.first { l -> l.id == "a" }.parentId }.type)
        rule.onNodeWithTag("rail.layer.a").assertExists()
        val groupId = vm.uiState.value.layers.first { it.id == "a" }.parentId!!
        rule.onNodeWithTag("rail.layer.$groupId").assertExists()
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

/** The 3D-group view buttons (spec/3d-space.md, "Non-gesture alternatives"): shown only there, labelled from resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class ViewControlButtonsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(EditorUiState(activeTool = Tool.BRUSH))
    private val steps = mutableListOf<com.hereliesaz.graffitixr.common.scene.OrbitStep>()
    private var resets = 0

    private val actions = object : ToolRailActions {
        override fun setActiveTool(tool: Tool) = Unit
        override fun onColorClicked() = Unit
        override fun resetViewport() { resets++ }
        override fun onUndoClicked() = Unit
        override fun onRedoClicked() = Unit
        override fun setBrushSize(size: Float) = Unit
        override fun setBrushFeathering(feathering: Float) = Unit
        override fun onOrbitStep(step: com.hereliesaz.graffitixr.common.scene.OrbitStep) { steps += step }
    }

    private fun show() = rule.setContent {
        MaterialTheme(colorScheme = darkColorScheme()) {
            ToolRail(actions, state, emptySet(), emptySet(), ToolRailCallbacks({}, {}, {}))
        }
    }

    private val tags = listOf("left", "right", "up", "down").map { "rail.view.orbit.$it" }

    private fun threeD() {
        val surface = com.hereliesaz.graffitixr.common.scene.SurfaceProfile(listOf(0f, 800f, 0f, 600f, 800f, 0f))
        state = state.copy(
            layers = listOf(Layer("g", "G", type = LayerType.GROUP, surface = surface), Layer("a", "A", parentId = "g")),
            viewportZoom = 2f,
        )
    }

    @Test
    fun `a 3D group shows one View button, never Fit, and the pad is closed until it is tapped`() {
        threeD()
        show()
        rule.onNodeWithTag("rail.view.fit").assertDoesNotExist()
        rule.onNodeWithTag("rail.view").assertExists()
        rule.onNodeWithContentDescription(rule.activity.resources.getString(com.hereliesaz.graffitixr.design.R.string.rail_view))
            .assertExists()
        rule.onNodeWithTag("rail.view").assert(
            androidx.compose.ui.test.SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected),
        )
        rule.onNodeWithTag("rail.view.reset").assertDoesNotExist()
        tags.forEach { rule.onNodeWithTag(it).assertDoesNotExist() }
    }

    @Test
    fun `the View pad holds four labelled orbit steps and Reset, orbit repeats without closing, Reset closes`() {
        threeD()
        show()
        rule.onNodeWithTag("rail.view").performScrollTo().performClick()
        val res = rule.activity.resources
        listOf(
            com.hereliesaz.graffitixr.design.R.string.rail_reset_view,
            com.hereliesaz.graffitixr.design.R.string.rail_orbit_left,
            com.hereliesaz.graffitixr.design.R.string.rail_orbit_right,
            com.hereliesaz.graffitixr.design.R.string.rail_orbit_up,
            com.hereliesaz.graffitixr.design.R.string.rail_orbit_down,
        ).forEach { rule.onNodeWithContentDescription(res.getString(it)).assertExists() }
        // Each control is a 48dp target.
        val px = 48f * res.displayMetrics.density
        (tags + "rail.view.reset").forEach {
            val b = rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
            assertTrue("$it is at least 48dp", b.width >= px - 1f && b.height >= px - 1f)
        }
        // Repeatable: two taps on one step, the pad stays open.
        rule.onNodeWithTag("rail.view.orbit.left").performClick()
        rule.onNodeWithTag("rail.view.orbit.left").performClick()
        tags.drop(1).forEach { rule.onNodeWithTag(it).performClick() }
        assertEquals(
            listOf(com.hereliesaz.graffitixr.common.scene.OrbitStep.LEFT, com.hereliesaz.graffitixr.common.scene.OrbitStep.LEFT) +
                com.hereliesaz.graffitixr.common.scene.OrbitStep.values().drop(1),
            steps,
        )
        rule.onNodeWithTag("rail.view.reset").assertExists().performClick()
        assertEquals(1, resets)
        rule.onNodeWithTag("rail.view.reset").assertDoesNotExist()
    }

    /** A real Back key (down then up) into the popup's own window root, as the system delivers it. */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun pressBackInPopup() {
        val root = rule.onNodeWithTag("rail.view.reset").fetchSemanticsNode().root
        val view = (root as androidx.compose.ui.platform.ViewRootForTest).view.rootView
        rule.runOnUiThread {
            view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        rule.waitForIdle()
    }

    @Test
    fun `Back in the popup window dismisses the View pad without acting and focus returns to the View button`() {
        threeD()
        show()
        rule.onNodeWithTag("rail.view").performScrollTo().performClick()
        rule.onNodeWithTag("rail.view.reset").assertExists()
        pressBackInPopup()
        rule.onNodeWithTag("rail.view.reset").assertDoesNotExist()
        assertEquals(0, resets)
        assertTrue(steps.isEmpty())
        rule.onNodeWithTag("rail.view").assertIsFocused()
    }

    @Test
    fun `opening the pad moves focus into it and announces it as a View controls pane`() {
        threeD()
        show()
        rule.onNodeWithTag("rail.view").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("rail.view.orbit.up").assertIsFocused()
        val title = rule.activity.resources.getString(com.hereliesaz.graffitixr.design.R.string.rail_view_controls)
        rule.onNode(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.PaneTitle, title),
        ).assertExists()
    }

    @Test
    fun `an outside tap dismisses the pad and does not reach what is behind it`() {
        threeD()
        var behind = 0
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxSize().testTag("canvas.behind").clickable { behind++ },
                    )
                    ToolRail(actions, state, emptySet(), emptySet(), ToolRailCallbacks({}, {}, {}))
                }
            }
        }
        rule.onNodeWithTag("rail.view").performScrollTo().performClick()
        rule.onNodeWithTag("rail.view.reset").assertExists()
        // performTouchInput on a main-window node bypasses the popup window altogether, so it cannot show what
        // the popup does with a tap. Deliver the tap the way the system does: as touch events to the popup's own
        // window root, at a point well away from the rail and the pad (the far side, mid-height).
        val root = rule.onNodeWithTag("rail.view.reset").fetchSemanticsNode().root
        @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
        val view = (root as androidx.compose.ui.platform.ViewRootForTest).view.rootView
        val screen = rule.onNodeWithTag("canvas.behind").fetchSemanticsNode().size
        assertTrue("the layer covers the whole window", view.width >= screen.width && view.height >= screen.height)
        val x = screen.width - 20f
        val y = screen.height / 2f
        rule.runOnUiThread {
            val t = android.os.SystemClock.uptimeMillis()
            view.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
            view.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 40, android.view.MotionEvent.ACTION_UP, x, y, 0))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("rail.view.reset").assertDoesNotExist()
        assertEquals("the tap that dismissed the pad did not fall through", 0, behind)
        assertTrue(steps.isEmpty() && resets == 0)
    }

    @Test
    fun `a different project closes an open pad, and losing the 3D group removes it`() {
        threeD()
        state = state.copy(projectId = "p1")
        show()
        rule.onNodeWithTag("rail.view").performScrollTo().performClick()
        rule.onNodeWithTag("rail.view.reset").assertExists()
        state = state.copy(projectId = "p2")
        rule.waitForIdle()
        rule.onNodeWithTag("rail.view.reset").assertDoesNotExist()
        rule.onNodeWithTag("rail.view").assertExists()
        rule.onNodeWithTag("rail.view").performScrollTo().performClick()
        rule.onNodeWithTag("rail.view.reset").assertExists()
        state = state.copy(layers = listOf(Layer("a", "A")))
        rule.waitForIdle()
        rule.onNodeWithTag("rail.view").assertDoesNotExist()
        rule.onNodeWithTag("rail.view.reset").assertDoesNotExist()
    }

    @Test
    fun `a project with no 3D group is unchanged, Fit while the view is moved and no view buttons`() {
        state = state.copy(layers = listOf(Layer("a", "A")), viewportZoom = 2f)
        show()
        rule.onNodeWithTag("rail.view.fit").assertExists()
        rule.onNodeWithTag("rail.view.reset").assertDoesNotExist()
        rule.onNodeWithTag("rail.view").assertDoesNotExist()
        tags.forEach { rule.onNodeWithTag(it).assertDoesNotExist() }
    }
}

/**
 * The rail on a short screen with a 3D project's full item set: it is held to the space MainActivity gives
 * it (below the top inset, above the bottom strip), and scrolls far enough that the last item is reachable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", application = Application::class)
class ViewControlShortScreenUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val steps = mutableListOf<com.hereliesaz.graffitixr.common.scene.OrbitStep>()

    private val actions = object : ToolRailActions {
        override fun setActiveTool(tool: Tool) = Unit
        override fun onColorClicked() = Unit
        override fun resetViewport() = Unit
        override fun onUndoClicked() = Unit
        override fun onRedoClicked() = Unit
        override fun setBrushSize(size: Float) = Unit
        override fun setBrushFeathering(feathering: Float) = Unit
        override fun onOrbitStep(step: com.hereliesaz.graffitixr.common.scene.OrbitStep) { steps += step }
    }

    @Test
    fun `on a 360x640 screen the rail stays inside its space and its last item can be scrolled to and clicked`() {
        val surface = com.hereliesaz.graffitixr.common.scene.SurfaceProfile(listOf(0f, 800f, 0f, 600f, 800f, 0f))
        val ui = EditorUiState(
            activeTool = Tool.BRUSH,
            layers = listOf(Layer("g", "G", type = LayerType.GROUP, surface = surface), Layer("a", "A", parentId = "g")),
            activeLayerId = "a",
        )
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // The padding MainActivity gives the rail (toolRailPadding).
                ToolRail(
                    actions, ui, emptySet(), emptySet(), ToolRailCallbacks({}, {}, {}),
                    Modifier.withRailPadding(),
                )
            }
        }
        val rail = rule.onNodeWithTag("rail.tools").fetchSemanticsNode().boundsInRoot
        val screenH = 640f * rule.activity.resources.displayMetrics.density
        assertTrue("the rail is held below the screen's bottom strip", rail.bottom <= screenH * RAIL_MAX_BOTTOM_FRACTION)
        assertTrue("the rail takes at most ${(RAIL_MAX_HEIGHT_FRACTION * 100).toInt()}% of the height", rail.height <= screenH * RAIL_MAX_HEIGHT_FRACTION)
        val last = rule.onNodeWithTag("rail.view").performScrollTo()
        last.assertIsDisplayed()
        val b = last.fetchSemanticsNode().boundsInRoot
        assertTrue("the last item is fully inside the rail, not clipped", b.bottom <= rail.bottom && b.top >= rail.top)
        last.performClick()
        // The pad opens in its own window, inside the screen, and its far corner is reachable.
        val down = rule.onNodeWithTag("rail.view.orbit.down").assertIsDisplayed()
        val pb = down.fetchSemanticsNode().boundsInWindow
        val screenW = 360f * rule.activity.resources.displayMetrics.density
        assertTrue(
            "the pad is inside the screen",
            pb.bottom <= screenH && pb.top >= 0f && pb.right <= screenW && pb.left >= 0f,
        )
        down.performClick()
        assertEquals(listOf(com.hereliesaz.graffitixr.common.scene.OrbitStep.DOWN), steps)
    }

    private fun Modifier.withRailPadding(): Modifier =
        padding(horizontal = RailSideInset).padding(top = RailTopInset, bottom = RailWidth + RailEdgeGap * 2)

    private companion object {
        const val RAIL_MAX_HEIGHT_FRACTION = 0.9f
        const val RAIL_MAX_BOTTOM_FRACTION = 0.95f
    }
}
