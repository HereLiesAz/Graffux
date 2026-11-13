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
        val auto = ui().drawPlane!!.factor
        vm.onDrawDepthDrag(-200f)
        val nearer = ui().drawPlane!!.factor
        vm.onDrawDepthDrag(400f)
        val farther = ui().drawPlane!!.factor
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
        vm.onTwoFingerGesture(Offset(5f, 0f), 1f, Offset(24f, 24f), 0f)
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
    fun `the next stroke makes a layer above the active one, art untouched, in one undo step`() = runTest(dispatcher) {
        setup()
        // A stroke first, so the reference is that stroke's plane (the layer's own, z = 0).
        vm.onSceneOrbit(0.5f, 0.1f)
        stroke()
        val undoAfterFirst = ui().undoCount
        val before = ui().layers.first { it.id == "L" }
        vm.onDrawDepthDrag(-150f)
        val depth = vm.effectiveDrawDepth()!!
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
                made.offset.x, made.offset.y, made.positionZ,
                made.rotationX, made.rotationY, made.rotationZ, made.scale,
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
        assertEquals(1, vm.recordedStrokesForTest(made.id).size)
        val camera = ui().sceneCamera
        vm.onUndoClicked()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("G", "L"), ui().layers.map { it.id })
        assertEquals("L", ui().activeLayerId)
        assertEquals("undo leaves the camera alone", camera, ui().sceneCamera)
        assertNull("and does not bring the depth back", ui().drawPlane)
        assertEquals(before.offset, ui().layers.first { it.id == "L" }.offset)
        assertEquals("the stroke is off the record", 0, vm.recordedStrokesForTest(made.id).size)
        vm.onRedoClicked()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(made.id, ui().activeLayerId)
        assertEquals(listOf("G", "L", made.id), ui().layers.map { it.id })
        val back = ui().layers.first { it.id == made.id }
        assertEquals(made.offset, back.offset)
        assertEquals(made.positionZ, back.positionZ, 1e-3f)
        assertEquals(made.scale, back.scale, 1e-5f)
        assertEquals("the stroke is back on the record", 1, vm.recordedStrokesForTest(made.id).size)
    }

    private fun setDepthAndAdopt(): Layer {
        vm.onDrawDepthDrag(-150f)
        assertTrue(vm.adoptViewPlaneForStroke())
        return ui().layers.first { it.id == ui().activeLayerId }
    }

    @Test
    fun `a stroke whose commit fails leaves no layer behind and no reference to it`() = runTest(dispatcher) {
        setup()
        val u = ui().undoCount
        val made = setDepthAndAdopt()
        stroke()
        // No GPU here: draining the queued commit fails it, which drops the stroke again.
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("G", "L"), ui().layers.map { it.id })
        assertEquals("L", ui().activeLayerId)
        assertEquals(u, ui().undoCount)
        assertNull("its pixel cache is released", vm.layerBaseForTest(made.id))
        assertNull(vm.drawPlaneReference())
    }

    @Test
    fun `a failed commit puts an empty layer's old plane back`() = runTest(dispatcher) {
        setup()
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers + Layer(id = "E", name = "E")))
        vm.onLayerActivated("E")
        setDepthAndAdopt()
        assertTrue(ui().layers.first { it.id == "E" }.isPlacedIn3D)
        stroke()
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(ui().layers.first { it.id == "E" }.isPlacedIn3D)
    }

    @Test
    fun `undoing the last stroke hands the reference back to the stroke before it`() = runTest(dispatcher) {
        setup()
        vm.onSceneOrbit(0.5f, 0.1f)
        stroke()
        val first = vm.drawPlaneReference()!!.column(3).toList()
        val made = setDepthAndAdopt()
        stroke()
        val second = vm.drawPlaneReference()!!.column(3).toList()
        assertTrue("the new layer's plane is the reference now", first != second)
        // Not drained: with no GPU a drained commit fails and drops the first stroke too.
        vm.onUndoClicked()
        assertEquals(first, vm.drawPlaneReference()!!.column(3).toList())
        // The undone layer's cache stays for redo, and goes once another stroke discards the redo.
        assertTrue(vm.layerBaseForTest(made.id) != null)
        vm.onLayerActivated("L")
        stroke()
        assertNull(vm.layerBaseForTest(made.id))
    }

    @Test
    fun `a stroke on a surface layer is no reference, the depth refuses it`() = runTest(dispatcher) {
        setup(withSurfacePaint = true)
        vm.onSceneOrbit(0.5f, 0.1f)
        vm.onLayerActivated("W")
        stroke()
        assertNull(vm.drawPlaneReference())
    }

    @Test
    fun `an Ink stroke commits onto the new layer, and a double-tap dot with a depth takes the layer back`() =
        runTest(dispatcher) {
            setup()
            vm.onDrawDepthDrag(-150f)
            val u = ui().undoCount
            vm.commitInkThroughPlane(
                io.mockk.mockk(relaxed = true), listOf(Offset(10f, 10f), Offset(30f, 10f)),
                listOf(1f, 1f), com.hereliesaz.graffitixr.common.model.InkUtensil.PEN, canvasSize, {},
            ) { st, _ -> st }
            val made = ui().layers.first { it.id == ui().activeLayerId }
            assertTrue("a new layer took the Ink stroke", made.id != "L")
            assertEquals(u + 1, ui().undoCount)
            assertEquals(1, vm.inkLedgerStrokes(made.id).size)
            vm.onUndoClicked()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("G", "L"), ui().layers.map { it.id })
            // The dot: a brush tap, its stroke, then the second tap of a view-reset double-tap.
            vm.onDrawDepthDrag(-150f)
            val at = Offset(30f, 30f)
            assertFalse(vm.onBrushTap(at, 1000L))
            assertTrue(vm.adoptViewPlaneForStroke())
            vm.onStrokeStart(BrushSample(30f, 30f, uptimeMillis = 1000L), canvasSize)
            vm.onStrokePoint(BrushSample(30f, 30f, uptimeMillis = 1001L))
            vm.onStrokeEnd()
            assertEquals(3, ui().layers.size)
            assertTrue(vm.onBrushTap(Offset(31f, 30f), 1120L))
            assertEquals(listOf("G", "L"), ui().layers.map { it.id })
            assertEquals("L", ui().activeLayerId)
        }

    @Test
    fun `a redo is gone once another edit follows, so a layer deleted since cannot be brought back`() =
        runTest(dispatcher) {
            setup()
            val made = setDepthAndAdopt()
            stroke()
            vm.onUndoClicked()
            dispatcher.scheduler.advanceUntilIdle()
            vm.onLayerRemoved("L")
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(0, ui().redoCount)
            vm.onRedoClicked()
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(ui().layers.none { it.id == made.id })
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
        val content = com.hereliesaz.graffitixr.common.scene.SpatialLayerContent()
        val spatial = Layer(id = "S", name = "S", spatial = content)
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
    fun `the menu steps and reset set and clear the depth, and the guide fades after a second`() = runTest(dispatcher) {
        setup()
        vm.onDrawDepthStep(nearer = false)
        val far = ui().drawPlane!!.factor
        vm.onDrawDepthStep(nearer = true)
        vm.onDrawDepthStep(nearer = true)
        assertTrue(ui().drawPlane!!.factor < far)
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

    @Test
    fun `a later stroke of a chunk that fails keeps the layer and the first stroke, and undo reverts the plane once`() =
        runTest(dispatcher) {
            setup()
            val made = setDepthAndAdopt()
            stroke()
            val first = vm.recordedStrokesForTest(made.id).single()
            stroke()
            val second = vm.recordedStrokesForTest(made.id).last()
            assertTrue(first !== second)
            vm.failCommitForTest(made.id, second)
            assertTrue("the layer stays", ui().layers.any { it.id == made.id })
            assertEquals(listOf(first), vm.recordedStrokesForTest(made.id))
            vm.onUndoClicked()
            assertEquals(listOf("G", "L"), ui().layers.map { it.id })
            assertEquals(0, vm.recordedStrokesForTest(made.id).size)
        }

    @Test
    fun `a failure that arrives after the entry was undone does not revert again`() = runTest(dispatcher) {
        setup()
        val made = setDepthAndAdopt()
        stroke()
        val only = vm.recordedStrokesForTest(made.id).single()
        vm.onUndoClicked()
        val layers = ui().layers
        io.mockk.verify(exactly = 1) { emitter.emit(Op.LayerRemove(made.id)) }
        vm.failCommitForTest(made.id, only)
        assertEquals("nothing else changed", layers, ui().layers)
        io.mockk.verify(exactly = 1) { emitter.emit(Op.LayerRemove(made.id)) }
    }

    @Test
    fun `reverting a made layer emits LayerRemove and deletes its first PNG, only after its save`() = runTest(dispatcher) {
        loadProject("P")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        io.mockk.coEvery { projectRepo.saveArtifact("P", any(), any()) } coAnswers { gate.await(); "path" }
        val made = setDepthAndAdopt()
        val file = made.uri!!.lastPathSegment!!
        stroke()
        vm.failCommitForTest(made.id, vm.recordedStrokesForTest(made.id).single())
        dispatcher.scheduler.advanceUntilIdle()
        io.mockk.verify(exactly = 1) { emitter.emit(Op.LayerRemove(made.id)) }
        io.mockk.coVerify(exactly = 0) { projectRepo.deleteArtifact("P", file) }
        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        io.mockk.coVerify(exactly = 1) { projectRepo.deleteArtifact("P", file) }
    }

    private fun loadProject(id: String) {
        vm.dispatchForTest(EditorIntent.LoadedProject(id, emptyList()))
        setup()
        vm.dispatchForTest(EditorIntent.LoadedProject(id, ui().layers))
        // Loading a project puts the tool down and the active layer away: take both up again.
        vm.onLayerActivated("L")
        vm.setActiveTool(Tool.BRUSH)
    }

    @Test
    fun `a project switch resets the reference and never deletes the old project's file`() = runTest(dispatcher) {
        loadProject("A")
        vm.onSceneOrbit(0.5f, 0.1f)
        val made = setDepthAndAdopt()
        val file = made.uri!!.lastPathSegment!!
        stroke()
        vm.onUndoClicked() // the layer is now held only by a redo entry
        assertTrue(vm.layerBaseForTest(made.id) != null)
        projectFlow.value = com.hereliesaz.graffitixr.common.model.GraffitiProject(id = "B")
        dispatcher.scheduler.advanceUntilIdle()
        // Project B has a layer, and the camera is as it was: only the reset can make the reference null.
        val bLayer = Layer(id = "B1", name = "B1", bitmap = RenderTestBase.filled(48, 48, Color.TRANSPARENT))
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(bLayer)))
        vm.putLayerBaseForTest("B1", bLayer.bitmap!!)
        vm.onLayerActivated("B1")
        vm.setActiveTool(Tool.BRUSH)
        assertNull(vm.drawPlaneReference())
        stroke() // a push: the sweep runs here
        dispatcher.scheduler.advanceUntilIdle()
        io.mockk.coVerify(exactly = 0) { projectRepo.deleteArtifact("A", file) }
    }

    @Test
    fun `redoing a stroke makes it the reference again, and a lapsed newer stroke does not hand over to an older`() =
        runTest(dispatcher) {
            setup()
            vm.onSceneOrbit(0.5f, 0.1f)
            stroke()
            val first = vm.drawPlaneReference()!!.column(3).toList()
            setDepthAndAdopt()
            stroke()
            val second = vm.drawPlaneReference()!!.column(3).toList()
            vm.onUndoClicked()
            assertEquals(first, vm.drawPlaneReference()!!.column(3).toList())
            vm.onRedoClicked()
            assertEquals(second, vm.drawPlaneReference()!!.column(3).toList())
            vm.onSceneOrbit(0.1f, 0f)
            // Any camera change since the newest stroke lapses it, and no older stroke stands in.
            assertNull(vm.drawPlaneReference())
        }

    @Test
    fun `a stroke that lands on the made layer as its own entry keeps the layer when the first one fails late`() =
        runTest(dispatcher) {
            setup()
            val made = setDepthAndAdopt()
            stroke()
            val first = vm.recordedStrokesForTest(made.id).single()
            vm.setActiveTool(Tool.ERASER)
            stroke()
            vm.failCommitForTest(made.id, first)
            assertTrue(ui().layers.any { it.id == made.id })
            io.mockk.verify(exactly = 0) { emitter.emit(Op.LayerRemove(made.id)) }
        }

    @Test
    fun `a failure after the made layer was deleted emits no second LayerRemove, and a double failure reverts once`() =
        runTest(dispatcher) {
            setup()
            val made = setDepthAndAdopt()
            stroke()
            val only = vm.recordedStrokesForTest(made.id).single()
            vm.onLayerRemoved(made.id)
            vm.failCommitForTest(made.id, only)
            io.mockk.verify(exactly = 1) { emitter.emit(Op.LayerRemove(made.id)) }
            vm.failCommitForTest(made.id, only)
            io.mockk.verify(exactly = 1) { emitter.emit(Op.LayerRemove(made.id)) }
        }

    @Test
    fun `the first stroke of a chunk failing keeps the layer for the second, which undo then takes with it`() =
        runTest(dispatcher) {
            setup()
            val made = setDepthAndAdopt()
            stroke()
            val first = vm.recordedStrokesForTest(made.id).single()
            stroke()
            vm.failCommitForTest(made.id, first)
            assertTrue(ui().layers.any { it.id == made.id })
            assertEquals(1, vm.recordedStrokesForTest(made.id).size)
            vm.onUndoClicked()
            assertEquals(listOf("G", "L"), ui().layers.map { it.id })
        }

    @Test
    fun `redo does not restamp the camera, so a stroke drawn before an orbit is not the reference after it`() =
        runTest(dispatcher) {
            setup()
            vm.onSceneOrbit(0.5f, 0.1f)
            stroke()
            assertNotNull(vm.drawPlaneReference())
            vm.onUndoClicked()
            vm.onSceneOrbit(0.2f, 0f)
            vm.onRedoClicked()
            assertNull(vm.drawPlaneReference())
        }

    @Test
    fun `undoing to an older stroke whose camera has lapsed gives no reference`() = runTest(dispatcher) {
        setup()
        vm.onSceneOrbit(0.5f, 0.1f)
        stroke()
        vm.onSceneOrbit(0.2f, 0f)
        vm.setActiveTool(Tool.ERASER)
        stroke()
        assertNotNull(vm.drawPlaneReference())
        vm.onUndoClicked()
        assertNull(vm.drawPlaneReference())
    }

    @Test
    fun `an older stroke whose camera matches now does not stand in for a newer one that does not`() =
        runTest(dispatcher) {
            setup()
            vm.onSceneOrbit(0.5f, 0.1f)
            val c1 = ui().sceneCamera
            stroke()
            vm.onSceneOrbit(0.2f, 0f)
            vm.setActiveTool(Tool.ERASER)
            stroke()
            vm.setSceneCameraForTest(c1)
            assertNull(vm.drawPlaneReference())
        }

    @Test
    fun `switching the active layer ends the last-stroke reference`() = runTest(dispatcher) {
        setup(withSurfacePaint = true)
        vm.onSceneOrbit(0.5f, 0.1f)
        stroke()
        assertNotNull(vm.drawPlaneReference())
        vm.onLayerActivated("W")
        vm.onLayerActivated("L")
        assertNull(vm.drawPlaneReference())
    }

    @Test
    fun `the depth is an offset from the automatic one, which follows the layers, so it is not pinned`() =
        runTest(dispatcher) {
            setup()
            vm.onDrawDepthDrag(-200f)
            val factor = ui().drawPlane!!.factor
            val auto = vm.automaticDrawDepth()!!.first
            assertEquals(auto * factor, vm.effectiveDrawDepth()!!, 0.01f)
            assertTrue("nearer than automatic", factor < 1f)
            // The automatic depth moves when what the pivot is made of moves; the factor stays, the plane follows.
            val moved = ui().layers.map { if (it.id == "L") it.copy(positionZ = 300f) else it }
            vm.dispatchForTest(EditorIntent.SetLayers(moved))
            val autoAfter = vm.automaticDrawDepth()!!.first
            assertEquals(factor, ui().drawPlane!!.factor, 0f)
            assertEquals(autoAfter * factor, vm.effectiveDrawDepth()!!, 0.01f)
            // Reset is automatic again: nothing is set, and the next stroke takes no plane.
            vm.onResetDrawDepth()
            assertNull(vm.effectiveDrawDepth())
            assertFalse(vm.adoptViewPlaneForStroke())
        }

    @Test
    fun `the slider fraction and the guide read the effective depth`() = runTest(dispatcher) {
        setup()
        vm.onSetDrawDepthFraction(0.25f)
        assertEquals(0.25f, vm.drawDepthFraction(), 0.01f)
        val world = vm.drawPlaneGuideWorld(ui())!!
        val size = ui().canvasSize
        val cam = com.hereliesaz.graffitixr.common.scene.SceneMath.frame(
            ui().sceneCamera, size.width.toFloat(), size.height.toFloat(),
        )
        val z = cam.toCamera(world.apply(size.width / 2f, size.height / 2f, 0f))[2]
        assertEquals(vm.effectiveDrawDepth()!!, z, 0.5f)
    }
}
