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
import com.hereliesaz.graffitixr.common.scene.DrawPlane
import com.hereliesaz.graffitixr.common.scene.NearestEdge
import com.hereliesaz.graffitixr.common.scene.OrbitPivot
import com.hereliesaz.graffitixr.common.scene.PlanePose
import com.hereliesaz.graffitixr.common.scene.ReliefSurface
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SceneMath
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
 * The automatic plane meets the nearest edge of the surfaces of every plane that has content, and only with no
 * such plane the orbit pivot (spec/3d-space.md, "Draw plane depth"). Pixels are not rendered on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NearestEdgePlaneTest {

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


    private val cx = 24f

    private fun setup() {
        val original = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
        val layer = Layer(id = "L", name = "Layer", bitmap = original)
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(layer)))
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

    private fun cam() = SceneMath.frame(ui().sceneCamera, 48f, 48f)

    private fun centreDepth(layer: Layer): Float {
        val pose = PlanePose(
            layer.offset.x, layer.offset.y, layer.positionZ, layer.rotationX, layer.rotationY, layer.rotationZ, layer.scale,
        )
        return cam().toCamera(SceneMath.model(pose, 48f, 48f).apply(cx, cx, 0f))[2]
    }

    private fun pivotDepth() = cam().toCamera(OrbitPivot.of(ui().layers, 48f, 48f))[2]

    /**
     * The depth the rule asks for now, from geometry alone: the nearest edge of the planes the test says have content
     * ([counted], by id). It does not ask the view model what has content.
     */
    private fun expectedEdge(counted: Set<String>): Float = expectedEdgeOrNull(counted)!!

    private fun expectedEdgeOrNull(counted: Set<String>): Float? =
        NearestEdge.depth(NearestEdge.outlines(ui().layers, 48f, 48f) { l -> l.id in counted }, cam())

    private fun drawFirst() {
        vm.onSceneOrbit(0.5f, 0.1f)
        assertTrue(vm.adoptViewPlaneForStroke())
        stroke()
        assertTrue("the stroke is on record", vm.recordedStrokesForTest("L").isNotEmpty())
    }

    @Test
    fun `the first plane is placed at the orbit pivot`() = runTest(dispatcher) {
        setup()
        vm.onSceneOrbit(0.5f, 0.1f)
        assertTrue(vm.adoptViewPlaneForStroke())
        assertEquals(pivotDepth(), centreDepth(ui().layers.first { it.id == "L" }), 0.5f)
    }

    @Test
    fun `the second plane meets the nearest edge of the first, not the pivot`() = runTest(dispatcher) {
        setup()
        drawFirst()
        vm.onSceneOrbit(0.9f, 0.3f)
        val edge = expectedEdge(setOf("L"))
        assertTrue("the edge and the pivot differ here", kotlin.math.abs(edge - pivotDepth()) > 1f)
        assertTrue(vm.adoptViewPlaneForStroke())
        val made = ui().layers.first { it.id == ui().activeLayerId }
        assertTrue(made.id != "L")
        assertEquals(edge, centreDepth(made), 0.5f)
        assertTrue(kotlin.math.abs(centreDepth(made) - pivotDepth()) > 1f)
    }

    private val views = listOf(
        -1.4f to -0.2f, 1.2f to 0.4f, 0.1f to -0.6f, -0.5f to 0.9f, 0.8f to -0.3f, -1.0f to 0.5f, 0.4f to 0.2f, -0.2f to -0.4f,
    )

    /** Two stroked planes, both in the project, and the second one's id. Fresh for each caller: nothing is undone. */
    private fun twoPlanes(): String {
        setup()
        drawFirst()
        vm.onSceneOrbit(0.9f, 0.3f)
        assertTrue(vm.adoptViewPlaneForStroke())
        stroke()
        val second = ui().activeLayerId!!
        assertTrue(second != "L")
        assertTrue(vm.recordedStrokesForTest(second).isNotEmpty())
        return second
    }

    /** A view in which [owner]'s plane has the nearest edge of the two, found by measuring, not assumed. */
    private fun viewWhereNearest(owner: String, second: String): SceneCamera {
        val found = views.map { (yaw, pitch) -> SceneCamera(yaw = yaw, pitch = pitch) }.firstOrNull { camera ->
            vm.setSceneCameraForTest(camera)
            val first = expectedEdgeOrNull(setOf("L"))
            val last = expectedEdgeOrNull(setOf(second))
            first != null && last != null && kotlin.math.abs(first - last) >= 1f &&
                (if (first < last) "L" else second) == owner
        }
        return found ?: error("no view where $owner is nearest")
    }

    private fun assertThirdMeetsUnion(owner: String) {
        val second = twoPlanes()
        vm.setSceneCameraForTest(viewWhereNearest(if (owner == "first") "L" else second, second))
        val both = expectedEdge(setOf("L", second))
        val firstOnly = expectedEdge(setOf("L"))
        val lastOnly = expectedEdge(setOf(second))
        assertEquals(minOf(firstOnly, lastOnly), both, 0.01f)
        assertTrue(vm.adoptViewPlaneForStroke())
        val third = ui().layers.first { it.id == ui().activeLayerId }
        assertTrue(third.id != "L" && third.id != second)
        assertEquals("the union of both planes", both, centreDepth(third), 0.5f)
        val other = if (owner == "first") lastOnly else firstOnly
        assertTrue("not the other plane's edge alone", kotlin.math.abs(centreDepth(third) - other) > 0.5f)
    }

    @Test
    fun `the third plane meets the first plane's edge when that is the nearest of the two`() = runTest(dispatcher) {
        assertThirdMeetsUnion("first")
    }

    @Test
    fun `the third plane meets the second plane's edge when that is the nearest of the two`() = runTest(dispatcher) {
        assertThirdMeetsUnion("second")
    }

    @Test
    fun `with the grid off an empty layer still takes the view plane through the pivot`() = runTest(dispatcher) {
        setup()
        drawFirst()
        val blank = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers + Layer(id = "E", name = "E", bitmap = blank)))
        vm.putLayerBaseForTest("E", blank)
        vm.onLayerActivated("E")
        vm.onToggleSurfaceGrid()
        vm.onSceneOrbit(0.9f, 0.3f)
        assertTrue("the planes have an edge that is not the pivot's depth", kotlin.math.abs(expectedEdge(setOf("L")) - pivotDepth()) > 1f)
        assertTrue(vm.adoptViewPlaneForStroke())
        assertEquals(pivotDepth(), centreDepth(ui().layers.first { it.id == "E" }), 0.5f)
    }

    @Test
    fun `the draw depth factor is relative to the new automatic depth`() = runTest(dispatcher) {
        setup()
        drawFirst()
        vm.onSceneOrbit(0.9f, 0.3f)
        val edge = expectedEdge(setOf("L"))
        assertEquals(edge, vm.automaticDrawDepth()!!.first, 0.01f)
        vm.onDrawDepthStep(nearer = false)
        assertEquals(edge * DrawPlane.STEP_FACTOR, vm.effectiveDrawDepth()!!, 0.05f)
        vm.onDrawDepthStep(nearer = true)
        vm.onDrawDepthStep(nearer = true)
        assertEquals(edge / DrawPlane.STEP_FACTOR, vm.effectiveDrawDepth()!!, 0.05f)
    }

    @Test
    fun `with the grid off a layer with art takes no new plane, as before`() =
        runTest(dispatcher) {
            setup()
            drawFirst()
            vm.onToggleSurfaceGrid()
            vm.onSceneOrbit(0.9f, 0.3f)
            val count = ui().layers.size
            assertFalse("a layer with art, grid off: no new plane", vm.adoptViewPlaneForStroke())
            assertEquals(count, ui().layers.size)
        }

    /**
     * What the depth-model extraction makes of the photo "L" ([ReliefBuild.assemble]): a Relief group "R" whose first child
     * is the skin "S" (a copy of the photo), and a HIDDEN flat-surface group "F" that keeps the original "L".
     */
    private fun extractPlane() {
        val depth = FloatArray(16) { (it % 4) * 1f + (it / 4) * 0.5f }
        val relief = ReliefSurface.build(depth, 4, 4, 48, 48, 0f, 0f, 48f, 48f)!!
        val photo = ui().layers.first { it.id == "L" }
        val flat = ReliefBuild.flatSheet(photo, 48, 48, 48f, 48f)!!
        val built = ReliefBuild.assemble(
            ui().layers, "L", ReliefIds("R", "S", "F"), ReliefNames("Relief", "Flat surface", "skin"), relief, flat, photo,
        )
        vm.dispatchForTest(EditorIntent.SetLayers(built))
        vm.onLayerActivated("S")
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `an extracted plane is the first plane, and the next plane meets its nearest edge, not the pivot`() =
        runTest(dispatcher) {
            val original = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
            vm.dispatchForTest(
                EditorIntent.SetLayers(listOf(Layer(id = "L", name = "Photo", bitmap = original, uri = android.net.Uri.parse("file:///photo.png")))),
            )
            vm.putLayerBaseForTest("L", original)
            vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
            vm.selectCustomBrush("solid")
            vm.setActiveTool(Tool.BRUSH)
            extractPlane()
            assertEquals("the skin is on the relief", "R", ui().layers.first { it.id == "S" }.parentId)
            vm.onSceneOrbit(0.9f, 0.3f)
            val edge = expectedEdge(setOf("S"))
            assertTrue("the extracted surface is the reference", edge != pivotDepth())
            assertTrue(kotlin.math.abs(edge - pivotDepth()) > 1f)
            assertTrue(vm.adoptViewPlaneForStroke())
            val made = ui().layers.first { it.id == ui().activeLayerId }
            assertTrue(made.id != "S" && made.id != "L" && made.parentId == null)
            assertEquals(edge, centreDepth(made), 0.5f)
        }

    @Test
    fun `the hidden original is ignored by the rule and showing it makes it a plane with content`() =
        runTest(dispatcher) {
            val original = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
            vm.dispatchForTest(
                EditorIntent.SetLayers(listOf(Layer(id = "L", name = "Photo", bitmap = original, uri = android.net.Uri.parse("file:///photo.png")))),
            )
            vm.putLayerBaseForTest("L", original)
            vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
            extractPlane()
            vm.onSceneOrbit(0.9f, 0.3f)
            fun edges(counted: Set<String>) =
                NearestEdge.outlines(ui().layers, 48f, 48f) { l -> l.id in counted }.size
            // Hidden: only the relief (through its skin) is an outline, though both photos "have content".
            assertEquals(1, edges(setOf("S", "L")))
            assertEquals(0, edges(setOf("L")))
            vm.onToggleVisibility("F")
            // Shown: the original's flat surface is a plane with content too.
            assertEquals(2, edges(setOf("S", "L")))
            assertEquals(1, edges(setOf("L")))
        }

    // ── What counts as a plane with content, through the real path (a project as it is reopened) ──

    private val fileUri = android.net.Uri.parse("file:///layer.png")

    private fun painted() = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.RED)

    private fun blank() = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)

    /**
     * A reopened project: [others] and the empty active layer "E", no strokes recorded. Returns where E's plane landed
     * and the nearest edge of [counted] (the layers the test says count), measured from geometry alone.
     */
    private fun reopenAndDraw(others: List<Layer>, counted: Set<String>, landed: Boolean): Triple<Float, Float, Float> {
        vm.dispatchForTest(EditorIntent.SetLayers(others + Layer(id = "E", name = "E", bitmap = blank())))
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.onLayerActivated("E")
        vm.selectCustomBrush("solid")
        vm.setActiveTool(Tool.BRUSH)
        if (landed) dispatcher.scheduler.advanceUntilIdle() else assertTrue("a scan is still pending", vm.paintScans.inFlight > 0)
        vm.onSceneOrbit(0.9f, 0.3f)
        val edge = expectedEdgeOrNull(counted)
        assertTrue(vm.adoptViewPlaneForStroke())
        val e = ui().layers.first { it.id == "E" }
        return Triple(centreDepth(e), edge ?: Float.NaN, pivotDepth())
    }

    private fun assertOnEdge(r: Triple<Float, Float, Float>) {
        assertTrue("the edge is not the pivot here", kotlin.math.abs(r.second - r.third) > 1f)
        assertEquals("nearest edge", r.second, r.first, 0.5f)
    }

    private fun sketch(id: String, bitmap: android.graphics.Bitmap) = Layer(id = id, name = id, isSketch = true, bitmap = bitmap, uri = fileUri)

    @Test
    fun `a reopened painted sketch layer with no strokes counts at the first stroke, before its scan lands`() =
        runTest(dispatcher) {
            assertOnEdge(reopenAndDraw(listOf(sketch("S", painted())), setOf("S"), landed = false))
        }

    @Test
    fun `a reopened painted sketch layer counts once its scan has landed too`() = runTest(dispatcher) {
        assertOnEdge(reopenAndDraw(listOf(sketch("S", painted())), setOf("S"), landed = true))
    }

    @Test
    fun `a sketch layer with a file uri but no paint is not a plane with content`() = runTest(dispatcher) {
        val r = reopenAndDraw(listOf(sketch("S", blank())), emptySet(), landed = false)
        assertEquals("the pivot", r.third, r.first, 0.5f)
        assertTrue("and the layer would have been an edge", expectedEdgeOrNull(setOf("S"))!! != r.third)
    }

    @Test
    fun `an imported image counts as a plane with content`() = runTest(dispatcher) {
        assertOnEdge(reopenAndDraw(listOf(Layer(id = "I", name = "I", bitmap = blank(), uri = fileUri)), setOf("I"), landed = true))
    }

    @Test
    fun `a text layer counts as a plane with content`() = runTest(dispatcher) {
        val text = Layer(id = "T", name = "T", textParams = com.hereliesaz.graffitixr.common.model.TextLayerParams(text = "a"))
        assertOnEdge(reopenAndDraw(listOf(text), setOf("T"), landed = true))
    }

    @Test
    fun `a layer of shapes counts as a plane with content`() = runTest(dispatcher) {
        val shapes = Layer(
            id = "H", name = "H",
            shapes = listOf(com.hereliesaz.graffitixr.common.model.VectorShape(com.hereliesaz.graffitixr.common.model.ShapeKind.RECTANGLE)),
        )
        assertOnEdge(reopenAndDraw(listOf(shapes), setOf("H"), landed = true))
    }

    // ── The scan budget: nothing heavy on the main thread, one scan per (bitmap, generation) ──

    /** Counts the background scans by bitmap; the scan itself is the real one. */
    private fun countScans(): MutableMap<android.graphics.Bitmap, Int> {
        val counts = java.util.IdentityHashMap<android.graphics.Bitmap, Int>()
        vm.paintScans.scan = { b ->
            counts[b] = (counts[b] ?: 0) + 1
            !ArtScan.isEmpty(b)
        }
        return counts
    }

    private fun reopen(vararg layers: Layer) {
        vm.dispatchForTest(EditorIntent.SetLayers(layers.toList()))
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.onLayerActivated("E")
        vm.selectCustomBrush("solid")
        vm.setActiveTool(Tool.BRUSH)
    }

    @Test
    fun `drag, guide and overlay reads never scan or probe, so a layer whose scan has not landed does not count there`() =
        runTest(dispatcher) {
            val s = painted()
            val counts = countScans()
            reopen(sketch("S", s), Layer(id = "E", name = "E", bitmap = blank()))
            vm.onSceneOrbit(0.9f, 0.3f)
            assertNull("not counted before its scan lands", vm.nearestEdgeDepth())
            vm.automaticDrawDepth()
            vm.drawPlaneGuideWorld(ui())
            vm.onDrawDepthDragStart()
            vm.onDrawDepthDrag(-100f)
            assertNull("still not counted after the reads", vm.nearestEdgeDepth())
            assertFalse("and nothing was recorded by them", vm.paintScans.hasResult("S", s))
            assertEquals("and no scan ran on this thread", 0, counts.size)
            // The stroke start does count it, from the bounded probe, and records nothing either.
            assertTrue(vm.adoptViewPlaneForStroke())
            assertFalse(vm.paintScans.hasResult("S", s))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("the background scan answered", true, vm.paintScans.cached("S", s))
        }

    @Test
    fun `the warm-up scans a bitmap once per generation, and again when the generation changes`() = runTest(dispatcher) {
        val s = painted()
        val counts = countScans()
        reopen(sketch("S", s), Layer(id = "E", name = "E", bitmap = blank()))
        repeat(5) { vm.onSceneOrbit(0.1f, 0f) }
        dispatcher.scheduler.advanceUntilIdle()
        repeat(5) { vm.onSceneOrbit(0.1f, 0f) }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("one scan however many writes", 1, counts[s])
        s.eraseColor(Color.TRANSPARENT) // an in-place edit: a new generation
        vm.onSceneOrbit(0.1f, 0f)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("scanned again for the new generation", 2, counts[s])
        assertEquals("and it now reads empty", false, vm.paintScans.cached("S", s))
        vm.onSceneOrbit(0.1f, 0f)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("not again for the same generation", 2, counts[s])
    }

    @Test
    fun `the active empty layer is scanned once, not by the stroke start and again by the warm-up`() = runTest(dispatcher) {
        val e = blank()
        val counts = countScans()
        reopen(Layer(id = "E", name = "E", bitmap = e))
        assertTrue("the warm-up is queued", vm.paintScans.inFlight > 0)
        vm.onSceneOrbit(0.9f, 0.3f)
        assertTrue(vm.adoptViewPlaneForStroke())
        assertEquals("the stroke start's answer is the shared one", false, vm.paintScans.cached("E", e))
        assertEquals("and the queued scan was dropped", 0, vm.paintScans.inFlight)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("no background scan of it ran", null, counts[e])
    }
}
