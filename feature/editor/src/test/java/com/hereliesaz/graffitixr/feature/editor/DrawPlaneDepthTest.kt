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
import kotlinx.coroutines.test.TestScope
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
@Suppress("LargeClass")
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
        // The view turned, so the stroke gets a plane facing it on a new layer (see the view-plane tests below).
        assertTrue(vm.adoptViewPlaneForStroke())
        assertEquals(3, ui().layers.size)
        val u = ui().undoCount
        stroke()
        assertEquals(u + 1, ui().undoCount)
    }

    @Test
    fun `drawing again after the layer or the view was turned takes a new plane facing the view, the old one keeps its pose`() =
        runTest(dispatcher) {
            setup()
            val old = ui().layers.first { it.id == "L" }
            // The layer itself was rotated in 3D, the view at home: it used to take paint on its edge-on plane or none.
            vm.dispatchForTest(
                EditorIntent.SetLayers(ui().layers.map { if (it.id == "L") it.copy(rotationY = 80f) else it }),
            )
            assertTrue(vm.adoptViewPlaneForStroke())
            val made = ui().layers.first { it.id == ui().activeLayerId }
            assertNotEquals("L", made.id)
            assertEquals(0f, made.rotationY, 0.01f)
            val kept = ui().layers.first { it.id == "L" }
            assertEquals(80f, kept.rotationY, 0f)
            assertEquals(old.offset, kept.offset)
            // The same plane takes the next stroke: no second layer.
            vm.onStrokeCancel()
        }

    @Test
    fun `paint on a surface that does not face the view goes onto a new plane facing the view, outside the group`() =
        runTest(dispatcher) {
            setup(withSurfacePaint = true)
            val seen = mutableListOf<EditorNotice>()
            val job = launch(dispatcher) { vm.notices.collect { seen += it } }
            dispatcher.scheduler.runCurrent()
            vm.onLayerActivated("W")
            // Within the face-on angle (5 degrees): the surface keeps taking the stroke.
            vm.onSceneOrbit(0.05f, 0f)
            assertFalse(vm.adoptViewPlaneForStroke())
            vm.onSceneOrbit(0.4f, 0f)
            val before = ui().layers.first { it.id == "W" }
            assertTrue(vm.adoptViewPlaneForStroke())
            val made = ui().layers.first { it.id == ui().activeLayerId }
            assertNull("beside the group, not on its surface", made.parentId)
            dispatcher.scheduler.runCurrent()
            assertTrue("no notice before the stroke commits", planeNotices(seen).isEmpty())
            assertFalse("a second ask for the same stroke is silent", vm.adoptViewPlaneForStroke())
            job.cancel()
            assertEquals(before, ui().layers.first { it.id == "W" })
            assertEquals("after the group and its paint", ui().layers.size - 1, ui().layers.indexOf(made))
            // On that plane the touches map to distinct points (the old plane collapsed them to one line).
            val plane = ActivePlaneMapping.of(ui())
            val a = plane.toFlat(Offset(10f, 10f))!!
            val b = plane.toFlat(Offset(30f, 30f))!!
            assertTrue(kotlin.math.abs(a.x - b.x) > 1f && kotlin.math.abs(a.y - b.y) > 1f)
        }

    @Test
    fun `choosing a layer after rotating does not disarm it, the stroke still goes on the facing plane`() =
        runTest(dispatcher) {
            setup()
            vm.onSceneOrbit(0.4f, 0f)
            vm.onLayerActivated("L")
            assertTrue(vm.adoptViewPlaneForStroke())
            assertEquals(3, ui().layers.size)
        }

    @Test
    fun `a tilted layer faces the view again after an orbit back, and then keeps taking the stroke`() =
        runTest(dispatcher) {
            setup()
            vm.onSceneOrbit(0.4f, 0f)
            vm.onSceneOrbit(-0.4f, 0f)
            assertFalse(vm.adoptViewPlaneForStroke())
            assertEquals(2, ui().layers.size)
        }

    private fun planeNotices(seen: List<EditorNotice>): List<EditorNotice> {
        val text = org.robolectric.RuntimeEnvironment.getApplication()
            .getString(com.hereliesaz.graffitixr.design.R.string.notice_stroke_new_plane)
        return seen.filter { it.text == text }
    }

    private fun TestScope.surfaceNotices(): MutableList<EditorNotice> {
        setup(withSurfacePaint = true)
        val seen = mutableListOf<EditorNotice>()
        backgroundScope.launch(dispatcher) { vm.notices.collect { seen += it } }
        dispatcher.scheduler.runCurrent()
        vm.onLayerActivated("W")
        vm.onSceneOrbit(0.4f, 0f)
        return seen
    }

    @Test
    fun `the new-plane notice shows once when the stroke commits`() = runTest(dispatcher) {
        val seen = surfaceNotices()
        assertTrue(vm.adoptViewPlaneForStroke())
        stroke()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, planeNotices(seen).size)
    }

    @Test
    fun `a cancelled stroke or an empty Ink stroke leaves no new-plane notice`() = runTest(dispatcher) {
        val seen = surfaceNotices()
        assertTrue(vm.adoptViewPlaneForStroke())
        vm.onStrokeCancel()
        dispatcher.scheduler.runCurrent()
        assertEquals(3, ui().layers.size)
        assertTrue(planeNotices(seen).isEmpty())
        assertTrue(vm.adoptViewPlaneForStroke())
        vm.commitInkThroughPlane(
            io.mockk.mockk(relaxed = true), emptyList(), emptyList(),
            com.hereliesaz.graffitixr.common.model.InkUtensil.PEN, canvasSize, {},
        ) { st, _ -> st }
        dispatcher.scheduler.runCurrent()
        assertTrue(planeNotices(seen).isEmpty())
        // The plane was put back with the stroke: the next stroke asks for it again, and says so once it commits.
        assertTrue(vm.adoptViewPlaneForStroke())
        stroke()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, planeNotices(seen).size)
    }

    @Test
    fun `a tap dot on a surface after a rotation takes a plane, and taking it back leaves no notice or orphan layer`() =
        runTest(dispatcher) {
            val seen = surfaceNotices()
            val before = ui().layers.map { it.id }
            assertFalse(vm.onBrushTap(Offset(30f, 30f), 1000L))
            assertTrue(vm.adoptViewPlaneForStroke())
            vm.onStrokeStart(BrushSample(30f, 30f, uptimeMillis = 1000L), canvasSize)
            vm.onStrokePoint(BrushSample(30f, 30f, uptimeMillis = 1001L))
            vm.onStrokeEnd()
            assertEquals(before.size + 1, ui().layers.size)
            assertTrue(vm.onBrushTap(Offset(31f, 30f), 1120L))
            dispatcher.scheduler.runCurrent()
            assertEquals(before, ui().layers.map { it.id })
            assertEquals("W", ui().activeLayerId)
            assertTrue(planeNotices(seen).isEmpty())
        }

    @Test
    fun `a tap dot on a plain layer after a rotation takes a plane and the double-tap removes it`() =
        runTest(dispatcher) {
            setup()
            vm.onSceneOrbit(0.4f, 0f)
            assertFalse(vm.onBrushTap(Offset(30f, 30f), 1000L))
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
    fun `with the grid off nothing is attributed to a camera-facing plane, surface art and placed layers stay as they are`() =
        runTest(dispatcher) {
            val seen = surfaceNotices()
            vm.onToggleSurfaceGrid()
            assertFalse(ui().surfaceGridVisible)
            assertFalse("surface paint", vm.adoptViewPlaneForStroke())
            vm.onLayerActivated("L")
            assertFalse("a layer with art", vm.adoptViewPlaneForStroke())
            vm.dispatchForTest(
                EditorIntent.SetLayers(ui().layers.map { if (it.id == "L") it.copy(rotationY = 80f) else it }),
            )
            assertFalse("a layer placed in 3D", vm.adoptViewPlaneForStroke())
            assertEquals(listOf("G", "L", "W"), ui().layers.map { it.id })
            stroke()
            dispatcher.scheduler.runCurrent()
            assertTrue("no notice", planeNotices(seen).isEmpty())
        }

    @Test
    fun `with the grid off an empty layer still takes the view plane, as it always did`() = runTest(dispatcher) {
        setup()
        vm.onToggleSurfaceGrid()
        val empty = Layer(id = "E", name = "E")
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers + empty))
        vm.onLayerActivated("E")
        vm.onSceneOrbit(0.4f, 0f)
        assertTrue(vm.adoptViewPlaneForStroke())
        assertTrue(ui().layers.first { it.id == "E" }.isPlacedIn3D)
        assertEquals(3, ui().layers.size)
    }

    private fun noPaintKinds(parentId: String?): Map<String, Layer> = mapOf(
        "text" to Layer(
            id = "X", name = "X", parentId = parentId,
            textParams = com.hereliesaz.graffitixr.common.model.TextLayerParams(text = "hi"),
        ),
        "adjustment" to Layer(
            id = "X", name = "X", parentId = parentId,
            adjustment = com.hereliesaz.graffitixr.common.photo.PhotoAdjustment(
                com.hereliesaz.graffitixr.common.photo.PhotoTool.LIGHT,
            ),
        ),
        "container" to Layer(
            id = "X", name = "X", parentId = parentId, type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP,
        ),
        "shapes" to Layer(
            id = "X", name = "X", parentId = parentId,
            shapes = listOf(
                com.hereliesaz.graffitixr.common.model.VectorShape(
                    kind = com.hereliesaz.graffitixr.common.model.ShapeKind.RECTANGLE, width = 10f, height = 10f,
                ),
            ),
        ),
    )

    private fun isLayerAdd(op: Op) = op is Op.LayerAdd

    @Test
    fun `a layer type that takes no paint creates no plane, no layer and no op when it does not face the view`() =
        runTest(dispatcher) {
            setup()
            val groupAndArt = ui().layers
            vm.onSceneOrbit(0.4f, 0f)
            for ((kind, layer) in noPaintKinds(parentId = null)) {
                vm.dispatchForTest(EditorIntent.SetLayers(listOf(ui().layers.first(), layer)))
                vm.onLayerActivated("X")
                val before = ui().layers
                assertFalse(kind, vm.adoptViewPlaneForStroke())
                vm.onStrokeCancel()
                assertEquals(kind, before, ui().layers)
                assertEquals(kind, "X", ui().activeLayerId)
            }
            io.mockk.verify(exactly = 0) { emitter.emit(match { isLayerAdd(it) }) }
            // Positive control: the same setup with a plain image layer does get its plane, and says so to the op log.
            vm.dispatchForTest(EditorIntent.SetLayers(groupAndArt))
            vm.onLayerActivated("L")
            assertTrue(vm.adoptViewPlaneForStroke())
            io.mockk.verify(exactly = 1) { emitter.emit(match { isLayerAdd(it) }) }
        }

    @Test
    fun `a text, adjustment or shape layer inside a surface group keeps its stroke, no plane and no op`() =
        runTest(dispatcher) {
            setup(withSurfacePaint = true)
            vm.onSceneOrbit(0.4f, 0f)
            for ((kind, layer) in noPaintKinds(parentId = "G")) {
                vm.dispatchForTest(EditorIntent.SetLayers(listOf(ui().layers.first { it.id == "G" }, layer)))
                vm.onLayerActivated("X")
                val before = ui().layers
                assertFalse(kind, vm.adoptViewPlaneForStroke())
                vm.onStrokeCancel()
                assertEquals(kind, before, ui().layers)
            }
            io.mockk.verify(exactly = 0) { emitter.emit(match { isLayerAdd(it) }) }
        }

    @Test
    fun `a plain paint layer inside a surface group still gets its plane outside the group, and grid off none`() =
        runTest(dispatcher) {
            setup(withSurfacePaint = true)
            vm.onLayerActivated("W")
            vm.onSceneOrbit(0.4f, 0f)
            vm.onToggleSurfaceGrid()
            assertFalse("grid off", vm.adoptViewPlaneForStroke())
            assertEquals(3, ui().layers.size)
            vm.onToggleSurfaceGrid()
            assertTrue("grid on", vm.adoptViewPlaneForStroke())
            val made = ui().layers.first { it.id == ui().activeLayerId }
            assertNull(made.parentId)
            io.mockk.verify(exactly = 1) { emitter.emit(match { isLayerAdd(it) }) }
        }

    @Test
    fun `a plane that already faces the view takes the next stroke, no second layer`() = runTest(dispatcher) {
        setup()
        vm.onSceneOrbit(0.4f, 0f)
        assertTrue(vm.adoptViewPlaneForStroke())
        // Asked again for the same stroke (the commit asks too): no second layer.
        assertFalse(vm.adoptViewPlaneForStroke())
        assertEquals(3, ui().layers.size)
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
    fun `reverting a made layer emits LayerRemove and deletes its PNG, only after its save`() = runTest(dispatcher) {
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

    private fun moveSurface(dz: Float) {
        val moved = com.hereliesaz.graffitixr.common.scene.SurfaceProfile(
            listOf(0f, 0f, dz, 24f, 0f, dz, 48f, 0f, dz + 20f),
        )
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers.map { if (it.id == "G") it.copy(surface = moved) else it }))
    }

    @Test
    fun `the depth is an offset from the automatic one, so it follows the planes and is not pinned`() =
        runTest(dispatcher) {
            setup()
            vm.onDrawDepthDrag(-200f)
            val factor = ui().drawPlane!!.factor
            val auto = vm.automaticDrawDepth()!!.first
            val eff = vm.effectiveDrawDepth()!!
            assertEquals(auto * factor, eff, 0.01f)
            assertTrue("nearer than automatic", factor < 1f)
            // Move what defines the nearest edge (the plane with art): the automatic depth changes, the factor stays.
            vm.dispatchForTest(EditorIntent.SetLayers(ui().layers.map { if (it.id == "L") it.copy(positionZ = 300f) else it }))
            val autoAfter = vm.automaticDrawDepth()!!.first
            assertNotEquals(auto, autoAfter, 1f)
            assertEquals(factor, ui().drawPlane!!.factor, 0f)
            assertEquals(autoAfter * factor, vm.effectiveDrawDepth()!!, 0.01f)
            assertNotEquals("an absolute depth would not have moved", eff, vm.effectiveDrawDepth()!!, 1f)
            // Reset is automatic again: nothing is set, and the next stroke takes no plane.
            vm.onResetDrawDepth()
            assertNull(vm.effectiveDrawDepth())
            assertFalse(vm.adoptViewPlaneForStroke())
        }

    @Test
    fun `the factor is kept while the reference plane itself moves`() = runTest(dispatcher) {
        setup()
        vm.onSceneOrbit(0.5f, 0.1f)
        stroke()
        vm.onDrawDepthDrag(150f)
        val factor = ui().drawPlane!!.factor
        val auto = vm.automaticDrawDepth()!!.first
        val moved = ui().layers.map { if (it.id == "L") it.copy(positionZ = 300f) else it }
        vm.dispatchForTest(EditorIntent.SetLayers(moved))
        val autoAfter = vm.automaticDrawDepth()!!.first
        assertNotEquals(auto, autoAfter, 1f)
        assertEquals(factor, ui().drawPlane!!.factor, 0f)
        assertEquals(autoAfter * factor, vm.effectiveDrawDepth()!!, 0.01f)
    }

    @Test
    fun `a drag past the clamp does not remember the overshoot`() = runTest(dispatcher) {
        setup()
        vm.onDrawDepthDrag(1_000_000f)
        val focal = vm.automaticDrawDepth()!!.second
        val range = com.hereliesaz.graffitixr.common.scene.DrawPlane.depthRange(focal)
        assertEquals(range.endInclusive, vm.effectiveDrawDepth()!!, 0.01f)
        vm.onDrawDepthDrag(-400f)
        assertEquals(range.endInclusive / 4f, vm.effectiveDrawDepth()!!, 0.01f)
    }

    @Test
    fun `the slider reaches both ends of the range`() = runTest(dispatcher) {
        setup()
        val focal = vm.automaticDrawDepth()!!.second
        val range = com.hereliesaz.graffitixr.common.scene.DrawPlane.depthRange(focal)
        vm.onSetDrawDepthFraction(0f)
        assertEquals(0f, vm.drawDepthFraction(), 0.001f)
        assertEquals(range.start, vm.effectiveDrawDepth()!!, 0.01f)
        vm.onSetDrawDepthFraction(1f)
        assertEquals(1f, vm.drawDepthFraction(), 0.001f)
        assertEquals(range.endInclusive, vm.effectiveDrawDepth()!!, 0.01f)
    }

    @Test
    fun `a pivot behind the camera says so, stays automatic and leaves no dead control`() = runTest(dispatcher) {
        setup()
        val seen = mutableListOf<EditorNotice>()
        val job = launch(dispatcher) { vm.notices.collect { seen += it } }
        dispatcher.scheduler.runCurrent()
        moveSurface(-50_000f)
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers.map { if (it.id == "L") it.copy(positionZ = -50_000f) else it }))
        assertNull("the automatic depth is not usable", vm.automaticDrawDepth())
        vm.onDrawDepthDragStart()
        vm.onDrawDepthDrag(-100f)
        vm.onDrawDepthDrag(-100f)
        dispatcher.scheduler.runCurrent()
        assertEquals("said once", 1, seen.size)
        assertNull(ui().drawPlane)
        assertNull(vm.effectiveDrawDepth())
        assertFalse(vm.adoptViewPlaneForStroke())
        job.cancel()
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
