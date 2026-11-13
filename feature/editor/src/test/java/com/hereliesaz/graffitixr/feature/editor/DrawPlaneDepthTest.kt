@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import com.hereliesaz.graffitixr.data.brush.CustomBrushRepository
import com.hereliesaz.graffitixr.data.figma.FigmaRepository
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import com.hereliesaz.graffitixr.common.model.isPlacedIn3D
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import kotlinx.coroutines.launch
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.runner.RunWith

/**
 * The draw plane's depth (spec/3d-space.md, "Draw plane depth") in the view-model: it lapses with the view, the next
 * stroke goes onto a new layer on the offset plane (parallel to the last stroke's) in one undo step, art is not
 * moved, and a surface or 3D strokes layer says no.
 *
 * Limits: a JVM test has no GPU, so a stroke's pixels are not rendered (see DoubleTapDotTest); these tests check
 * the layer list, planes, undo and redo counts and notices. Pixels landing on the plane need a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrawPlaneDepthTest {

    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val canvasSize = IntSize(48, 48)
    private val brush = AzphaltBrush(name = "Solid", hardness = 1f, opacity = 1f)
    private val exportManager = mockk<com.hereliesaz.graffitixr.feature.editor.export.ExportManager>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        RenderTestBase.stubNativeLibs()

        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { backgroundColor } returns MutableStateFlow(0)
            every { inputSampleRateHz } returns MutableStateFlow(0)
            every { canvasRenderScale } returns MutableStateFlow(1f)
            every { isRightHanded } returns MutableStateFlow(true)
            every { isImperialUnits } returns MutableStateFlow(false)
            every { gestureMapping } returns MutableStateFlow(emptyMap())
            every { savedPalette } returns MutableStateFlow(emptyList())
        }
        val projects = mockk<ProjectRepository>(relaxed = true) {
            every { currentProject } returns MutableStateFlow(null)
            every { this@mockk.projects } returns MutableStateFlow(emptyList())
        }
        val extensions = mockk<ExtensionRepository>(relaxed = true) {
            every { installed } returns MutableStateFlow(emptyList())
        }
        val brushes = mockk<CustomBrushRepository>(relaxed = true) {
            every { this@mockk.brushes } returns MutableStateFlow(emptyList())
            every { load("solid") } returns brush
        }
        val figma = mockk<FigmaRepository>(relaxed = true) {
            every { isAuthenticated } returns MutableStateFlow(false)
        }
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val io = dispatcher
            override val default = dispatcher
            override val unconfined = dispatcher
        }

        vm = EditorViewModel(
            projectRepository = projects,
            settingsRepository = settings,
            projectManager = mockk(relaxed = true),
            exportManager = exportManager,
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            liquifyEngine = mockk(relaxed = true),
            dispatchers = dispatchers,
            opEmitter = mockk(relaxed = true),
            extensionRepository = extensions,
            repositoryApiClient = mockk(relaxed = true),
            customBrushRepository = brushes,
            figmaRepository = figma,
            projectFileScanner = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private val wall = com.hereliesaz.graffitixr.common.scene.SurfaceProfile(
        listOf(0f, 0f, 0f, 24f, 0f, 0f, 48f, 0f, 20f),
    )

    private fun setup(withSurfacePaint: Boolean = false) {
        val original = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
        val group = Layer(
            id = "G", name = "G", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP, surface = wall,
        )
        // A free paint layer with art (a source image), beside the surface group that makes this a 3D project.
        val layer = Layer(id = "L", name = "Layer", bitmap = original, uri = android.net.Uri.parse("file:///art.png"))
        val onWall = Layer(id = "W", name = "Wall", parentId = "G", bitmap = original)
        vm.dispatchForTest(EditorIntent.SetLayers(listOfNotNull(group, layer, onWall.takeIf { withSurfacePaint })))
        vm.putLayerBaseForTest("L", original)
        vm.onLayerActivated("L")
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.selectCustomBrush("solid")
        vm.setActiveTool(Tool.BRUSH)
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun stroke() {
        vm.onStrokeStart(BrushSample(10f, 10f, uptimeMillis = 0L), canvasSize)
        vm.onStrokePoint(BrushSample(30f, 10f, uptimeMillis = 16L))
        vm.onStrokeEnd()
    }

    private fun ui() = vm.uiState.value

    @Test
    fun `a drag sets the depth, up nearer and down farther, and it lapses with the camera`() = runTest(dispatcher) {
        setup()
        vm.onDrawDepthDragStart()
        vm.onDrawDepthDrag(0f)
        val auto = ui().drawPlane!!.depth
        vm.onDrawDepthDrag(-200f)
        val nearer = ui().drawPlane!!.depth
        vm.onDrawDepthDrag(400f)
        val farther = ui().drawPlane!!.depth
        assertTrue("up is nearer", nearer < auto)
        assertTrue("down is farther", farther > nearer)
        assertTrue(ui().drawPlaneGuideShown)
        vm.onSceneOrbit(0.3f, 0f)
        assertNull("an orbit makes it automatic again", ui().drawPlane)
        assertFalse(ui().drawPlaneGuideShown)
    }

    @Test
    fun `a pan, a zoom, a layer switch and a project switch each reset it`() = runTest(dispatcher) {
        setup(withSurfacePaint = true)
        fun set() { vm.onDrawDepthDrag(-50f); assertNotNull(ui().drawPlane) }
        set()
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize)) // not a view change: the depth stays
        assertNotNull(ui().drawPlane)
        vm.onTwoFingerStart()
        vm.onTwoFingerGesture(androidx.compose.ui.geometry.Offset(5f, 0f), 1f, androidx.compose.ui.geometry.Offset(24f, 24f), 0f)
        vm.onTwoFingerEnd()
        assertNull("a pan", ui().drawPlane)
        set()
        vm.resetViewport()
        // Already at the default view the reset changes nothing; move it, then reset.
        vm.onSceneOrbit(0.2f, 0f)
        assertNull(ui().drawPlane)
        set()
        vm.resetViewport()
        assertNull("Reset view", ui().drawPlane)
        set()
        vm.onLayerActivated("W")
        assertNull("another layer", ui().drawPlane)
        vm.onLayerActivated("L")
        set()
        vm.dispatchForTest(EditorIntent.LoadedProject("other", ui().layers))
        assertNull("another project", ui().drawPlane)
    }

    @Test
    fun `the next stroke goes onto a new layer above the active one, art untouched, in one undo step`() = runTest(dispatcher) {
        setup()
        // A stroke first, so the reference is that stroke's plane (the layer's own, z = 0).
        vm.onSceneOrbit(0.5f, 0.1f)
        stroke()
        val undoAfterFirst = ui().undoCount
        val before = ui().layers.first { it.id == "L" }
        vm.onDrawDepthDrag(-150f)
        val depth = ui().drawPlane!!.depth
        assertTrue(vm.adoptViewPlaneForStroke())
        val layers = ui().layers
        val made = layers.first { it.id == ui().activeLayerId }
        assertEquals("directly above the active layer", layers.indexOfFirst { it.id == "L" } + 1, layers.indexOf(made))
        assertTrue(made.isSketch)
        assertNull("the depth is spent", ui().drawPlane)
        // Parallel to the last stroke's plane (the layer's flat plane): no tilt, only a nearer position.
        assertEquals(0f, made.rotationX, 0.01f)
        assertEquals(0f, made.rotationY, 0.01f)
        val cam = com.hereliesaz.graffitixr.common.scene.SceneMath.frame(ui().sceneCamera, 48f, 48f)
        val centre = com.hereliesaz.graffitixr.common.scene.SceneMath.model(
            com.hereliesaz.graffitixr.common.scene.PlanePose(
                made.offset.x, made.offset.y, made.positionZ, made.rotationX, made.rotationY, made.rotationZ, made.scale,
            ),
            48f, 48f,
        ).apply(24f, 24f, 0f)
        assertEquals(depth, cam.toCamera(centre)[2], 0.5f)
        // The art on the other layer is where it was.
        val after = ui().layers.first { it.id == "L" }
        assertEquals(before.offset, after.offset)
        assertEquals(before.positionZ, after.positionZ, 0f)
        assertSame(before.bitmap, after.bitmap)
        stroke()
        assertEquals("the layer and its stroke are one step", undoAfterFirst + 1, ui().undoCount)
        vm.onUndoClicked()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("G", "L"), ui().layers.map { it.id })
        assertEquals("L", ui().activeLayerId)
        vm.onRedoClicked()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(made.id, ui().activeLayerId)
        assertEquals(listOf("G", "L", made.id), ui().layers.map { it.id })
    }

    @Test
    fun `a cancelled first stroke takes the layer back and an empty layer is only moved`() = runTest(dispatcher) {
        setup()
        vm.onDrawDepthDrag(-100f)
        assertTrue(vm.adoptViewPlaneForStroke())
        assertEquals(3, ui().layers.size)
        vm.onStrokeCancel()
        assertEquals(listOf("G", "L"), ui().layers.map { it.id })
        assertEquals("L", ui().activeLayerId)
        // An empty layer takes the plane itself: nothing is added.
        val empty = Layer(id = "E", name = "E")
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(ui().layers.first(), empty)))
        vm.onLayerActivated("E")
        vm.onDrawDepthDrag(-100f)
        assertTrue(vm.adoptViewPlaneForStroke())
        assertEquals(2, ui().layers.size)
        assertTrue(ui().layers.first { it.id == "E" }.isPlacedIn3D)
        vm.onStrokeCancel()
        assertFalse(ui().layers.first { it.id == "E" }.isPlacedIn3D)
    }

    @Test
    fun `a layer on a surface and the 3D strokes layer ignore it, once, with a notice`() = runTest(dispatcher) {
        setup(withSurfacePaint = true)
        val seen = mutableListOf<EditorNotice>()
        val job = launch(dispatcher) { vm.notices.collect { seen += it } }
        dispatcher.scheduler.runCurrent()
        vm.onLayerActivated("W")
        vm.onDrawDepthDragStart()
        vm.onDrawDepthDrag(-50f)
        vm.onDrawDepthDrag(-50f)
        dispatcher.scheduler.runCurrent()
        assertNull(ui().drawPlane)
        assertEquals("said once per drag", 1, seen.size)
        assertFalse(vm.adoptViewPlaneForStroke())
        val spatial = Layer(id = "S", name = "S", spatial = com.hereliesaz.graffitixr.common.scene.SpatialLayerContent())
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers + spatial))
        vm.onLayerActivated("S")
        vm.onDrawDepthDragStart()
        vm.onDrawDepthDrag(-50f)
        dispatcher.scheduler.runCurrent()
        assertNull(ui().drawPlane)
        assertEquals(2, seen.size)
        job.cancel()
    }

    @Test
    fun `with no depth set a stroke behaves as it always did`() = runTest(dispatcher) {
        setup()
        assertNull(ui().drawPlane)
        assertFalse(vm.adoptViewPlaneForStroke())
        vm.onSceneOrbit(0.4f, 0f)
        // The art layer is not empty, so it keeps its plane; no layer is made.
        assertFalse(vm.adoptViewPlaneForStroke())
        assertEquals(listOf("G", "L"), ui().layers.map { it.id })
        val u = ui().undoCount
        stroke()
        assertEquals(u + 1, ui().undoCount)
    }

    @Test
    fun `the menu steps and the reset set and clear the depth, and the guide fades after a second`() = runTest(dispatcher) {
        setup()
        vm.onDrawDepthStep(nearer = false)
        val far = ui().drawPlane!!.depth
        vm.onDrawDepthStep(nearer = true)
        vm.onDrawDepthStep(nearer = true)
        assertTrue(ui().drawPlane!!.depth < far)
        assertTrue(ui().drawPlaneGuideShown)
        dispatcher.scheduler.advanceTimeBy(com.hereliesaz.graffitixr.common.scene.DrawPlane.GUIDE_LINGER_MS + 10)
        dispatcher.scheduler.runCurrent()
        assertFalse(ui().drawPlaneGuideShown)
        assertNotNull("the depth outlives the guide", ui().drawPlane)
        vm.onSetDrawDepthFraction(1f)
        assertEquals(1f, vm.drawDepthFraction(), 0.01f)
        vm.onResetDrawDepth()
        assertNull(ui().drawPlane)
    }
}
