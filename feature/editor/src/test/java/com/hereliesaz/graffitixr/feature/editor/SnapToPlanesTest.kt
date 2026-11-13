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
import org.junit.Assert.assertNotSame
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

    /** The default dispatcher the view-model sees: the main one, unless a test splits it off to hold work back. */
    private var splitDefault: kotlinx.coroutines.test.TestDispatcher? = null

    /** "Lands exactly": a relative error of a millionth, float rounding only. */
    private val exactly = 1e-6f
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
            override val default get() = splitDefault ?: dispatcher
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

    private fun setVisible(id: String, visible: Boolean) = vm.dispatchForTest(
        EditorIntent.SetLayers(ui().layers.map { if (it.id == id) it.copy(isVisible = visible) else it }),
    )

    private fun edit(id: String, change: (Layer) -> Layer) = vm.dispatchForTest(
        EditorIntent.SetLayers(ui().layers.map { if (it.id == id) change(it) else it }),
    )

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
    private fun depthOfL(): Float = depthOf("L")

    private fun depthOf(id: String): Float {
        val s = ui()
        val cam = com.hereliesaz.graffitixr.common.scene.SceneMath.frame(s.sceneCamera, 48f, 48f)
        val model = com.hereliesaz.graffitixr.common.scene.SceneMath.chainModel(
            com.hereliesaz.graffitixr.common.scene.planeChain(s.layers, id), 48f, 48f,
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
    fun `dragging to a stroked plane's depth lands exactly on it, and touches no stroke or layer`() =
        runTest(dispatcher) {
        val made = twoStrokedPlanes()
        val dL = depthOfL()
        val layersBefore = ui().layers
        val strokesL = vm.recordedStrokesForTest("L")
        val strokesM = vm.recordedStrokesForTest(made)
        val undo = ui().undoCount
        val camera = ui().sceneCamera
        vm.onDrawDepthDragStart()
        dragRawTo(dL * 1.04f)
        assertEquals(dL, vm.effectiveDrawDepth()!!, dL * exactly)
        assertEquals("L", ui().snapPlaneLayerId)
        vm.onDrawDepthDragEnd()
        assertEquals("released on the snap: still exactly there", dL, vm.effectiveDrawDepth()!!, dL * exactly)
        assertEquals(layersBefore, ui().layers)
        assertEquals(strokesL, vm.recordedStrokesForTest("L"))
        assertEquals(strokesM, vm.recordedStrokesForTest(made))
        assertEquals("no history entry", undo, ui().undoCount)
        assertEquals(camera, ui().sceneCamera)
    }

    @Test
    fun `outside the capture range there is no snap, and leaving it is smooth and has no memory`() =
        runTest(dispatcher) {
        twoStrokedPlanes()
        val dL = depthOfL()
        vm.onDrawDepthDragStart()
        dragRawTo(dL * 1.2f)
        assertEquals(dL * 1.2f, vm.effectiveDrawDepth()!!, dL * 1e-3f)
        assertNull(ui().snapPlaneLayerId)
        dragRawTo(dL * 1.03f)
        assertEquals(dL, vm.effectiveDrawDepth()!!, dL * exactly)
        // Held while the raw drag is in range: 4 percent, then 5 percent, still on it.
        dragRawTo(dL * 1.05f)
        assertEquals(dL, vm.effectiveDrawDepth()!!, dL * exactly)
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
            assertEquals(dL, vm.effectiveDrawDepth()!!, dL * exactly)
            // 3 percent farther in raw terms is outside the range (1.05 * 1.03 > 1.06)
            // even though the plane sits at 1.00.
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
        setVisible("L", false)
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
    fun `a snapped depth lapses with the camera like any other, and a capture announces itself`() =
        runTest(dispatcher) {
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
        setVisible("L", false)
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

    // ---- the off-thread pixel scan ----

    private fun setupWithPaintedLayer() {
        setup()
        val painted = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.RED)
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers + Layer(id = "P", name = "Painted", bitmap = painted)))
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** The scan's own scheduler: nothing on it runs until a test advances it, whatever the main scheduler does. */
    private class Scans {
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(scheduler)
        var count = 0
        var failWith: RuntimeException? = null
    }

    private fun splitScans(): Scans {
        val scans = Scans()
        splitDefault = scans.dispatcher
        vm.paintScans.scan = { bitmap ->
            scans.count++
            scans.failWith?.let { throw it }
            !ArtScan.isEmpty(bitmap)
        }
        return scans
    }

    /** Runs the pending scans on their own scheduler, then lets their results land on the main one. */
    private fun landScans(scans: Scans) {
        scans.scheduler.advanceUntilIdle()
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `the pixel scan runs on its own dispatcher, not the main one, and the snap arrives when it lands`() =
        runTest(dispatcher) {
            setupWithPaintedLayer()
            val scans = splitScans()
            val dP = depthOf("P")
            vm.onDrawDepthDragStart()
            dragRawTo(dP * 1.03f)
            assertNull("the first frame never waits for the scan", ui().snapPlaneLayerId)
            dragRawTo(dP * 1.02f)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("advancing only the main scheduler runs no scan", 0, scans.count)
            assertNull(ui().snapPlaneLayerId)
            scans.scheduler.advanceUntilIdle()
            assertEquals("the scan ran on its own scheduler", 1, scans.count)
            assertNull("and has not landed on the main one yet", ui().snapPlaneLayerId)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("a held finger sees the snap without another move", "P", ui().snapPlaneLayerId)
            assertEquals(dP, vm.effectiveDrawDepth()!!, dP * exactly)
            vm.onDrawDepthDragEnd()
        }

    @Test
    fun `a scan that lands during a held orbit snaps it without another move`() = runTest(dispatcher) {
        setupWithPaintedLayer()
        val scans = splitScans()
        vm.onSceneOrbit(0.2f, 0f)
        vm.onTwoFingerStart()
        drag(pixelsFor(-0.13f))
        assertEquals(0.07f, ui().sceneCamera.yaw, 2e-3f)
        assertNull(ui().snapPlaneLayerId)
        landScans(scans)
        assertEquals("P", ui().snapPlaneLayerId)
        assertEquals(0f, ui().sceneCamera.yaw, 1e-4f)
        vm.onTwoFingerEnd()
        assertEquals(0f, ui().sceneCamera.yaw, 1e-4f)
    }

    @Test
    fun `a scan that lands with no gesture under way only refreshes the planes`() = runTest(dispatcher) {
        setupWithPaintedLayer()
        val scans = splitScans()
        assertTrue(vm.strokedPlanes()!!.ids.isEmpty())
        landScans(scans)
        assertEquals(listOf("P"), vm.strokedPlanes()!!.ids)
        assertNull(ui().snapPlaneLayerId)
        assertTrue(ui().sceneCamera.isDefault)
    }

    @Test
    fun `an empty layer is scanned exactly once per bitmap generation and never becomes a candidate`() =
        runTest(dispatcher) {
            setup()
            val empty = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
            vm.dispatchForTest(EditorIntent.SetLayers(ui().layers + Layer(id = "E", name = "Empty", bitmap = empty)))
            val scans = splitScans()
            repeat(3) { assertTrue(vm.strokedPlanes()!!.ids.isEmpty()) }
            landScans(scans)
            repeat(3) { assertTrue(vm.strokedPlanes()!!.ids.isEmpty()) }
            landScans(scans)
            assertEquals("one scan for the one generation", 1, scans.count)
            val next = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
            edit("E") { it.copy(bitmap = next) }
            repeat(3) { vm.strokedPlanes() }
            landScans(scans)
            repeat(3) { vm.strokedPlanes() }
            landScans(scans)
            assertEquals("and one more for the new bitmap", 2, scans.count)
            assertEquals(0, vm.paintScans.inFlight)
        }

    @Test
    fun `a bitmap swapped mid-scan drops the old result, and the new scan is not run twice`() = runTest(dispatcher) {
        setupWithPaintedLayer()
        val scans = splitScans()
        vm.strokedPlanes()
        scans.scheduler.advanceUntilIdle()
        assertEquals(1, scans.count)
        val swapped = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.BLUE)
        edit("P") { it.copy(bitmap = swapped) }
        val planes = vm.strokedPlanes()
        dispatcher.scheduler.advanceUntilIdle()
        assertSame("the stale result touched neither the planes nor the guard", planes, vm.strokedPlanes())
        assertTrue(planes!!.ids.isEmpty())
        assertEquals(1, vm.paintScans.inFlight)
        landScans(scans)
        assertEquals("one scan for each bitmap, none duplicated", 2, scans.count)
        assertEquals(listOf("P"), vm.strokedPlanes()!!.ids)
    }

    @Test
    fun `a layer deleted mid-scan drops the result`() = runTest(dispatcher) {
        setupWithPaintedLayer()
        val scans = splitScans()
        vm.strokedPlanes()
        scans.scheduler.advanceUntilIdle()
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers.filterNot { it.id == "P" }))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.paintScans.inFlight)
        val planes = vm.strokedPlanes()!!
        assertTrue(planes.ids.isEmpty())
        assertSame("the dropped result did not invalidate the planes", planes, vm.strokedPlanes())
        assertEquals(1, scans.count)
    }

    @Test
    fun `a project switch mid-scan drops the result and keeps nothing of the old project`() = runTest(dispatcher) {
        setupWithPaintedLayer()
        val scans = splitScans()
        vm.strokedPlanes()
        scans.scheduler.advanceUntilIdle()
        val bitmap = ui().layers.first { it.id == "P" }.bitmap
        vm.resetDrawPlaneSession()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.paintScans.inFlight)
        assertFalse("the late result was not kept", vm.paintScans.painted("P", bitmap))
        // A scan that has not run yet is cancelled before it starts.
        vm.resetDrawPlaneSession()
        scans.scheduler.advanceUntilIdle()
        assertEquals("the scan started after the reset was cancelled before it ran", 1, scans.count)
    }

    @Test
    fun `an unreadable bitmap counts as unpainted for its generation, and is tried again with the next`() =
        runTest(dispatcher) {
            setupWithPaintedLayer()
            val scans = splitScans()
            scans.failWith = IllegalStateException("recycled")
            repeat(2) { vm.strokedPlanes() }
            landScans(scans)
            repeat(2) { assertTrue(vm.strokedPlanes()!!.ids.isEmpty()) }
            landScans(scans)
            assertEquals("no retry on every frame", 1, scans.count)
            scans.failWith = null
            edit("P") { it.copy(bitmap = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.RED)) }
            vm.strokedPlanes()
            landScans(scans)
            assertEquals("the next generation is scanned afresh", 2, scans.count)
            assertEquals(listOf("P"), vm.strokedPlanes()!!.ids)
        }

    // ---- the plane cache ----

    @Test
    fun `the plane cache is reused until a stroke, an undo, a visibility or a lock change`() = runTest(dispatcher) {
        setup()
        val none = vm.strokedPlanes()!!
        assertTrue(none.ids.isEmpty())
        assertSame(none, vm.strokedPlanes())
        stroke()
        val one = vm.strokedPlanes()!!
        assertNotSame("a stroke invalidates", none, one)
        assertEquals(listOf("L"), one.ids)
        assertSame(one, vm.strokedPlanes())
        setVisible("L", false)
        val hidden = vm.strokedPlanes()!!
        assertNotSame("visibility invalidates", one, hidden)
        assertTrue(hidden.ids.isEmpty())
        setVisible("L", true)
        val shown = vm.strokedPlanes()!!
        assertEquals(listOf("L"), shown.ids)
        edit("L") { it.copy(isImageLocked = true) }
        val locked = vm.strokedPlanes()!!
        assertNotSame("a lock change replaces the layer list, so the cache is rebuilt", shown, locked)
        assertEquals(listOf("L"), locked.ids)
        vm.onUndoClicked()
        val undone = vm.strokedPlanes()!!
        assertNotSame("undo invalidates", locked, undone)
        assertTrue(undone.ids.isEmpty())
    }

    @Test
    fun `a layer with art stays a candidate once its strokes are baked, and through a curves adjustment`() =
        runTest(dispatcher) {
            setup()
            stroke()
            assertEquals(listOf("L"), vm.strokedPlanes()!!.ids)
            // The periodic bake's one effect on the stroke list is LayerStore.takeOldestStrokes (maybeBakeOldStrokes).
            vm.bakeOldestStrokeForTest("L")
            assertEquals(0, vm.recordedStrokesForTest("L").size)
            assertTrue(vm.hasBakedStrokesForTest("L"))
            assertEquals(listOf("L"), vm.strokedPlanes()!!.ids)
            // A curves adjustment resets the layer's strokes (initStrokes) but the baked paint stays in its pixels.
            vm.onCurvesApplied(listOf(Offset(0f, 0f), Offset(1f, 1f)))
            dispatcher.scheduler.runCurrent()
            assertTrue(vm.hasBakedStrokesForTest("L"))
            assertEquals(listOf("L"), vm.strokedPlanes()!!.ids)
        }

    @Test
    fun `removing a layer and undoing the removal keeps its candidacy`() = runTest(dispatcher) {
        setup()
        stroke()
        assertEquals(listOf("L"), vm.strokedPlanes()!!.ids)
        vm.onLayerRemoved("L")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.strokedPlanes()!!.ids.isEmpty())
        vm.onUndoClicked()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("its strokes are cached for the undo: a candidate again", listOf("L"), vm.strokedPlanes()!!.ids)
    }

    // ---- orbit: tilted planes ----

    /** The unit normal of [id]'s plane and the face-on camera angles nearest the home view (+z side). */
    private fun faceOn(id: String): Triple<FloatArray, Float, Float> {
        val s = ui()
        val model = com.hereliesaz.graffitixr.common.scene.SceneMath.chainModel(
            com.hereliesaz.graffitixr.common.scene.planeChain(s.layers, id), 48f, 48f,
        )
        val n = com.hereliesaz.graffitixr.common.scene.SceneMath.normalize(
            com.hereliesaz.graffitixr.common.scene.SceneMath.cross(model.column(0), model.column(1)),
        )
        val dir = if (n[2] >= 0f) n else FloatArray(3) { -n[it] }
        return Triple(n, kotlin.math.asin(dir[1]), kotlin.math.atan2(dir[0], dir[2]))
    }

    private fun facing(n: FloatArray): Float {
        val c = ui().sceneCamera
        val f = com.hereliesaz.graffitixr.common.scene.PlaneSnap.forwardOf(c.yaw, c.pitch)
        return kotlin.math.abs(com.hereliesaz.graffitixr.common.scene.SceneMath.dot(f, n))
    }

    private fun tiltAndApproach(rotationX: Float, rotationY: Float) {
        setup()
        stroke()
        edit("L") { it.copy(rotationX = rotationX, rotationY = rotationY) }
        val (_, pitch, yaw) = faceOn("L")
        // Put the view about two degrees off that face-on view (not through a snap), then nudge it with a drag.
        vm.setSceneCameraForTest(
            com.hereliesaz.graffitixr.common.scene.SceneCamera(yaw = yaw + 0.03f, pitch = pitch - 0.02f),
        )
    }

    @Test
    fun `a tilted plane snaps to exactly face-on at a pitch that is not zero`() = runTest(dispatcher) {
        tiltAndApproach(rotationX = 20f, rotationY = 0f)
        val (n, pitch, yaw) = faceOn("L")
        assertTrue("the plane really is tilted", kotlin.math.abs(pitch) > 0.3f)
        vm.onTwoFingerStart()
        drag(pixelsFor(0.01f))
        assertEquals("L", ui().snapPlaneLayerId)
        assertEquals(pitch, ui().sceneCamera.pitch, 1e-4f)
        assertEquals(yaw, ui().sceneCamera.yaw, 1e-4f)
        assertEquals(1f, facing(n), 1e-5f)
        vm.onTwoFingerEnd()
        assertEquals(1f, facing(n), 1e-5f)
    }

    @Test
    fun `a plane turned away, with its normal pointing from the viewer, snaps to the nearer face-on view`() =
        runTest(dispatcher) {
            tiltAndApproach(rotationX = 0f, rotationY = 180f)
            val (n, _, yaw) = faceOn("L")
            assertTrue("the normal points away from +z", n[2] < 0f)
            vm.onTwoFingerStart()
            drag(pixelsFor(0.01f))
            assertEquals("L", ui().snapPlaneLayerId)
            assertEquals(yaw, ui().sceneCamera.yaw, 1e-4f)
            assertEquals(1f, facing(n), 1e-5f)
            vm.onTwoFingerEnd()
        }

    @Test
    fun `a plane whose face-on view the pitch limit forbids is not offered by the orbit`() = runTest(dispatcher) {
        setup()
        stroke()
        edit("L") { it.copy(rotationX = 89.5f) }
        // 1.5 rad is inside the pitch limit (1.5533) and about 3.5 degrees from that plane's face-on view.
        vm.setSceneCameraForTest(com.hereliesaz.graffitixr.common.scene.SceneCamera(yaw = 0f, pitch = 1.5f))
        vm.onTwoFingerStart()
        drag(pixelsFor(0.005f))
        assertNull(ui().snapPlaneLayerId)
        assertTrue(ui().sceneCamera.pitch <= com.hereliesaz.graffitixr.common.scene.SceneCamera.MAX_PITCH)
        assertTrue("the raw view is untouched", ui().sceneCamera.pitch > 1.49f)
        vm.onTwoFingerEnd()
    }

    @Test
    fun `a snapped orbit keeps the orbit point where it is on the screen`() = runTest(dispatcher) {
        orbitSetup()
        fun pivotOnScreen(): Pair<Float, Float> {
            val cam = com.hereliesaz.graffitixr.common.scene.SceneMath.frame(ui().sceneCamera, 48f, 48f)
            return cam.project(vm.orbitPivot()!!)!!
        }
        val before = pivotOnScreen()
        vm.onTwoFingerStart()
        drag(pixelsFor(-0.13f))
        assertEquals("L", ui().snapPlaneLayerId)
        val after = pivotOnScreen()
        assertEquals(before.first, after.first, 1e-2f)
        assertEquals(before.second, after.second, 1e-2f)
        vm.onTwoFingerEnd()
        val released = pivotOnScreen()
        assertEquals(before.first, released.first, 1e-2f)
        assertEquals(before.second, released.second, 1e-2f)
    }
}
