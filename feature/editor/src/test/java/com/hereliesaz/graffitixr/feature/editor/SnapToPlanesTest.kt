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
import com.hereliesaz.graffitixr.common.model.Op
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.isPlacedIn3D
import org.junit.Assert.assertNotEquals
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
 * Snap to stroked planes (spec/3d-space.md, "Snap to stroked planes") in the view-model: the depth drag and the
 * two-finger orbit pull to planes that carry brush strokes, never move a stroke or a layer, and leave history, the
 * camera lapse rules and the saved project alone.
 *
 * Limits: a JVM test has no GPU, so strokes are recorded but never rendered; haptics, the highlight's look and the
 * spoken line need a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@Suppress("LargeClass")
class SnapToPlanesTest {

    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val projectFlow = MutableStateFlow<com.hereliesaz.graffitixr.common.model.GraffitiProject?>(null)
    private val projectRepo = mockk<ProjectRepository>(relaxed = true)
    private val emitter = mockk<com.hereliesaz.graffitixr.common.coop.OpEmitter>(relaxed = true)
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
        val projects = projectRepo.also {
            every { it.currentProject } returns projectFlow
            every { it.projects } returns MutableStateFlow(emptyList())
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
            opEmitter = emitter,
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

    private fun stroke(at: Long = 0L) {
        vm.onStrokeStart(BrushSample(10f, 10f, uptimeMillis = at), canvasSize)
        vm.onStrokePoint(BrushSample(30f, 10f, uptimeMillis = at + 16L))
        vm.onStrokeEnd()
    }

    private fun ui() = vm.uiState.value

    /** A stroke on L, then a second stroked plane M nearer than it (a draw-plane stroke), M active. */
    private fun twoStrokedPlanes(): String {
        setup()
        vm.onSceneOrbit(0.5f, 0.1f)
        stroke()
        vm.onDrawDepthDrag(-150f)
        assertTrue(vm.adoptViewPlaneForStroke())
        val made = ui().activeLayerId!!
        stroke()
        vm.onDrawDepthDragEnd()
        return made
    }

    /** Where L's plane is along the draw plane's axis now, from the pure function the view-model uses. */
    private fun depthOfL(): Float {
        val s = ui()
        val cam = com.hereliesaz.graffitixr.common.scene.SceneMath.frame(s.sceneCamera, 48f, 48f)
        val model = com.hereliesaz.graffitixr.common.scene.SceneMath.chainModel(
            com.hereliesaz.graffitixr.common.scene.planeChain(s.layers, "L"), 48f, 48f,
        )
        return com.hereliesaz.graffitixr.common.scene.PlaneSnap.planeDepth(
            vm.drawPlaneReference(), cam, vm.orbitPivot(), model.apply(24f, 24f, 0f),
        )!!
    }

    /** Drags the draw plane from where it is to a raw depth [target] in one step, as a drag of that length would. */
    private var rawNow: Float? = null

    private fun dragRawTo(target: Float) {
        val from = rawNow ?: vm.effectiveDrawDepth() ?: vm.automaticDrawDepth()!!.first
        rawNow = target
        vm.onDrawDepthDrag(400f * kotlin.math.ln(target / from) / kotlin.math.ln(4f))
    }

    @Test
    fun `dragging to a stroked plane's depth lands exactly on it, and touches no stroke or layer`() = runTest(dispatcher) {
        val made = twoStrokedPlanes()
        val dL = depthOfL()
        val layersBefore = ui().layers
        val strokesL = vm.recordedStrokesForTest("L")
        val strokesM = vm.recordedStrokesForTest(made)
        val undo = ui().undoCount
        val camera = ui().sceneCamera
        vm.onDrawDepthDragStart()
        dragRawTo(dL * 1.04f)
        assertEquals(dL, vm.effectiveDrawDepth()!!, dL * 1e-3f)
        assertEquals("L", ui().snapPlaneLayerId)
        vm.onDrawDepthDragEnd()
        assertEquals("released on the snap: still exactly there", dL, vm.effectiveDrawDepth()!!, dL * 1e-3f)
        assertEquals(layersBefore, ui().layers)
        assertEquals(strokesL, vm.recordedStrokesForTest("L"))
        assertEquals(strokesM, vm.recordedStrokesForTest(made))
        assertEquals("no history entry", undo, ui().undoCount)
        assertEquals(camera, ui().sceneCamera)
    }

    @Test
    fun `outside the capture range there is no snap, and leaving it is smooth and has no memory`() = runTest(dispatcher) {
        twoStrokedPlanes()
        val dL = depthOfL()
        vm.onDrawDepthDragStart()
        dragRawTo(dL * 1.2f)
        assertEquals(dL * 1.2f, vm.effectiveDrawDepth()!!, dL * 1e-3f)
        assertNull(ui().snapPlaneLayerId)
        dragRawTo(dL * 1.03f)
        assertEquals(dL, vm.effectiveDrawDepth()!!, dL * 1e-3f)
        // Held while the raw drag is in range: 4 percent, then 5 percent, still on it.
        dragRawTo(dL * 1.05f)
        assertEquals(dL, vm.effectiveDrawDepth()!!, dL * 1e-3f)
        // Past the range the depth is the raw one again, not where the snap was: no sticking.
        dragRawTo(dL * 1.09f)
        assertEquals("raw " + dL * 1.09f, dL * 1.09f, vm.effectiveDrawDepth()!!, dL * 3e-3f)
        assertNull(ui().snapPlaneLayerId)
        vm.onDrawDepthDragEnd()
    }

    @Test
    fun `the raw drag keeps its own value while snapped, so a small drag back leaves the snap at once`() =
        runTest(dispatcher) {
            twoStrokedPlanes()
            val dL = depthOfL()
            vm.onDrawDepthDragStart()
            dragRawTo(dL * 1.05f)
            assertEquals(dL, vm.effectiveDrawDepth()!!, dL * 1e-3f)
            // 3 percent farther in raw terms is outside the range (1.05 * 1.03 > 1.06) even though the plane sits at 1.00.
            vm.onDrawDepthDrag(400f * kotlin.math.ln(1.03f) / kotlin.math.ln(4f))
            assertEquals(dL * 1.05f * 1.03f, vm.effectiveDrawDepth()!!, dL * 3e-3f)
            vm.onDrawDepthDragEnd()
        }

    @Test
    fun `with Snap to planes off the drag is exactly as before`() = runTest(dispatcher) {
        twoStrokedPlanes()
        val dL = depthOfL()
        vm.onSetSnapToPlanes(false)
        assertFalse(ui().snapToPlanes)
        vm.onDrawDepthDragStart()
        dragRawTo(dL * 1.03f)
        assertEquals(dL * 1.03f, vm.effectiveDrawDepth()!!, dL * 1e-3f)
        assertNull(ui().snapPlaneLayerId)
        vm.onSetSnapToPlanes(true)
        assertTrue(ui().snapToPlanes)
    }

    @Test
    fun `it is on by default`() {
        assertTrue(ui().snapToPlanes)
    }

    @Test
    fun `no stroked plane, no snap`() = runTest(dispatcher) {
        setup()
        val auto = vm.automaticDrawDepth()!!.first
        vm.onDrawDepthDragStart()
        dragRawTo(auto * 1.01f)
        assertEquals(auto * 1.01f, vm.effectiveDrawDepth()!!, auto * 1e-3f)
        assertNull(ui().snapPlaneLayerId)
        vm.onDrawDepthDragEnd()
    }

    @Test
    fun `a hidden layer is not a candidate`() = runTest(dispatcher) {
        twoStrokedPlanes()
        val dL = depthOfL()
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers.map { if (it.id == "L") it.copy(isVisible = false) else it }))
        vm.onDrawDepthDragStart()
        dragRawTo(dL * 1.03f)
        assertEquals(dL * 1.03f, vm.effectiveDrawDepth()!!, dL * 1e-3f)
        assertNull(ui().snapPlaneLayerId)
    }

    @Test
    fun `the layer being edited is a candidate like any other`() = runTest(dispatcher) {
        val made = twoStrokedPlanes()
        assertEquals(made, ui().activeLayerId)
        val auto = vm.automaticDrawDepth()!!.first
        vm.onDrawDepthDragStart()
        dragRawTo(auto * 1.04f)
        assertEquals(auto, vm.effectiveDrawDepth()!!, auto * 1e-3f)
        assertEquals(made, ui().snapPlaneLayerId)
    }

    @Test
    fun `the snap highlight fades out after the drag ends`() = runTest(dispatcher) {
        twoStrokedPlanes()
        val dL = depthOfL()
        vm.onDrawDepthDragStart()
        dragRawTo(dL * 1.02f)
        assertEquals("L", ui().snapPlaneLayerId)
        vm.onDrawDepthDragEnd()
        assertEquals("L", ui().snapPlaneLayerId)
        dispatcher.scheduler.advanceTimeBy(1_100L)
        dispatcher.scheduler.runCurrent()
        assertNull(ui().snapPlaneLayerId)
    }

    @Test
    fun `a snapped depth lapses with the camera like any other, and a capture announces itself`() = runTest(dispatcher) {
        twoStrokedPlanes()
        val dL = depthOfL()
        val ticks = mutableListOf<Unit>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            vm.snapTicks.collect { ticks += it }
        }
        vm.onDrawDepthDragStart()
        dragRawTo(dL * 1.03f)
        assertEquals(1, ticks.size)
        assertNotNull(ui().hudMessage)
        dragRawTo(dL * 1.02f)
        assertEquals("still the same capture: no second tick", 1, ticks.size)
        vm.onSceneOrbit(0.1f, 0f)
        assertNull(ui().drawPlane)
        job.cancel()
    }

    // ---- orbit ----

    private fun orbitSetup(): Layer {
        setup()
        // L is stroked on the identity plane; the view starts 0.2 rad (11 degrees) off face-on.
        stroke()
        vm.onSceneOrbit(0.2f, 0f)
        return ui().layers.first { it.id == "L" }
    }

    private fun drag(dxPixels: Float) {
        vm.onTwoFingerGesture(Offset(dxPixels, 0f), 1f, Offset(24f, 24f), 0f)
    }

    private fun pixelsFor(radians: Float) = radians / com.hereliesaz.graffitixr.common.scene
        .orbitAnglesForDrag(1f, 0f, 48f).first

    @Test
    fun `a two-finger orbit near face-on to a stroked plane snaps to exactly face-on and releases there`() =
        runTest(dispatcher) {
            val before = orbitSetup()
            val undo = ui().undoCount
            vm.onTwoFingerStart()
            drag(pixelsFor(-0.13f)) // raw yaw 0.07 rad = 4 degrees from face-on
            assertEquals(0f, ui().sceneCamera.yaw, 1e-4f)
            assertEquals(0f, ui().sceneCamera.pitch, 1e-4f)
            assertEquals("L", ui().snapPlaneLayerId)
            drag(pixelsFor(-0.06f)) // raw 0.01: still held
            assertEquals(0f, ui().sceneCamera.yaw, 1e-4f)
            vm.onTwoFingerEnd()
            assertEquals(0f, ui().sceneCamera.yaw, 1e-4f)
            assertEquals("no history entry", undo, ui().undoCount)
            assertEquals("the layer is where it was", before, ui().layers.first { it.id == "L" })
            assertEquals(1, vm.recordedStrokesForTest("L").size)
        }

    @Test
    fun `dragging past the range leaves the orbit snap with no stickiness`() = runTest(dispatcher) {
        orbitSetup()
        vm.onTwoFingerStart()
        drag(pixelsFor(-0.13f))
        assertEquals(0f, ui().sceneCamera.yaw, 1e-4f)
        // Raw is 0.07 now; 0.07 + 0.1 = 0.17 rad (9.7 degrees): out of range, the view is the raw one.
        drag(pixelsFor(0.1f))
        assertEquals(0.17f, ui().sceneCamera.yaw, 2e-3f)
        assertNull(ui().snapPlaneLayerId)
        vm.onTwoFingerEnd()
    }

    @Test
    fun `an orbit that starts outside the range and never enters it is untouched`() = runTest(dispatcher) {
        orbitSetup()
        vm.onTwoFingerStart()
        drag(pixelsFor(0.05f))
        assertEquals(0.25f, ui().sceneCamera.yaw, 2e-3f)
        assertNull(ui().snapPlaneLayerId)
        vm.onTwoFingerEnd()
    }

    @Test
    fun `the orbit step buttons stay exactly fifteen degrees and never snap`() = runTest(dispatcher) {
        orbitSetup()
        vm.onOrbitStep(com.hereliesaz.graffitixr.common.scene.OrbitStep.LEFT)
        // 0.2 - 15 degrees = -0.0618 rad, 3.5 degrees from face-on: inside the range, and still not snapped.
        assertEquals(0.2f - com.hereliesaz.graffitixr.common.scene.ORBIT_STEP_RADIANS, ui().sceneCamera.yaw, 1e-4f)
        assertNull(ui().snapPlaneLayerId)
    }

    @Test
    fun `orbit snap is off with the toggle off, and with no stroked plane`() = runTest(dispatcher) {
        orbitSetup()
        vm.onSetSnapToPlanes(false)
        vm.onTwoFingerStart()
        drag(pixelsFor(-0.13f))
        assertEquals(0.07f, ui().sceneCamera.yaw, 2e-3f)
        vm.onTwoFingerEnd()
        vm.onSetSnapToPlanes(true)
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers.map { if (it.id == "L") it.copy(isVisible = false) else it }))
        vm.onTwoFingerStart()
        drag(pixelsFor(-0.03f))
        assertEquals(0.04f, ui().sceneCamera.yaw, 2e-3f)
        assertNull(ui().snapPlaneLayerId)
        vm.onTwoFingerEnd()
    }

    @Test
    fun `a project with no 3D group never snaps`() = runTest(dispatcher) {
        val original = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(Layer(id = "L", name = "Layer", bitmap = original))))
        vm.putLayerBaseForTest("L", original)
        vm.onLayerActivated("L")
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.selectCustomBrush("solid")
        vm.setActiveTool(Tool.BRUSH)
        dispatcher.scheduler.advanceUntilIdle()
        stroke()
        vm.onTwoFingerStart()
        drag(pixelsFor(-0.05f))
        vm.onTwoFingerEnd()
        assertTrue(ui().sceneCamera.isDefault)
        assertNull(ui().snapPlaneLayerId)
    }

    @Test
    fun `a stroke made after a snap leaves the earlier stroke as it was, and undo leaves the snapped view alone`() =
        runTest(dispatcher) {
            orbitSetup()
            val before = vm.recordedStrokesForTest("L")
            vm.onTwoFingerStart()
            drag(pixelsFor(-0.13f))
            vm.onTwoFingerEnd()
            assertEquals("the snap recorded and changed no stroke", before, vm.recordedStrokesForTest("L"))
            val camera = ui().sceneCamera
            stroke(at = 60_000L)
            assertEquals(2, vm.recordedStrokesForTest("L").size)
            assertTrue(vm.recordedStrokesForTest("L").first() === before.first())
            vm.onUndoClicked()
            assertEquals("undo leaves the snapped view alone", camera, ui().sceneCamera)
        }
}
