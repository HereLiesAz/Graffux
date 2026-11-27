@file:Suppress("MaxLineLength", "LargeClass")

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
        val layer = Layer(id = "L", name = "Layer", isSketch = true, bitmap = original, uri = android.net.Uri.parse("file:///l.png"))
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
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers + sketch("E", blank)))
        vm.putLayerBaseForTest("E", blank)
        vm.onLayerActivated("E")
        vm.onToggleSurfaceGrid()
        dispatcher.scheduler.advanceUntilIdle() // E's scan lands: without it E is unknown, not known empty
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
        vm.dispatchForTest(EditorIntent.SetLayers(others + sketch("E", blank())))
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.onLayerActivated("E")
        vm.selectCustomBrush("solid")
        vm.setActiveTool(Tool.BRUSH)
        if (landed) dispatcher.scheduler.advanceUntilIdle() else assertTrue("a scan is still pending", vm.paintScans.inFlight > 0)
        vm.onSceneOrbit(0.9f, 0.3f)
        val edge = expectedEdgeOrNull(counted)
        assertTrue(vm.adoptViewPlaneForStroke())
        // E is unknown until its scan lands, so the stroke may have made a new layer on the plane: the active one has it.
        val e = ui().layers.first { it.id == ui().activeLayerId }
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
        vm.paintScans.scan = { b, _ ->
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
            reopen(sketch("S", s), sketch("E", blank()))
            vm.onSceneOrbit(0.9f, 0.3f)
            assertNull("not counted before its scan lands", vm.nearestEdgeDepth())
            vm.automaticDrawDepth()
            vm.drawPlaneGuideWorld(ui())
            vm.onDrawDepthDragStart()
            vm.onDrawDepthDrag(-100f)
            assertNull("still not counted after the reads", vm.nearestEdgeDepth())
            assertFalse("and nothing was recorded by them", (vm.paintScans.cached("S", s) != null))
            assertEquals("and no scan ran on this thread", 0, counts.size)
            // The stroke start does count it, from the bounded probe, and records nothing either.
            assertTrue(vm.adoptViewPlaneForStroke())
            assertFalse((vm.paintScans.cached("S", s) != null))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("the background scan answered", true, vm.paintScans.cached("S", s))
        }

    @Test
    fun `the warm-up scans a bitmap once per generation, and again when the generation changes`() = runTest(dispatcher) {
        val s = painted()
        val counts = countScans()
        reopen(sketch("S", s), sketch("E", blank()))
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
    fun `an unscanned blank layer is not scanned on the main thread, so the stroke makes a new layer`() =
        runTest(dispatcher) {
            val e = blank()
            val counts = countScans()
            reopen(sketch("E", e))
            assertTrue("the warm-up is queued", vm.paintScans.inFlight > 0)
            vm.onSceneOrbit(0.9f, 0.3f)
            assertTrue(vm.adoptViewPlaneForStroke())
            assertTrue("a new layer took the stroke; E is unknown, not known empty", ui().activeLayerId != "E")
            assertNull("nothing was scanned or recorded on this thread", vm.paintScans.cached("E", e))
            assertEquals(0, counts.size)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("the background scan answered", false, vm.paintScans.cached("E", e))
            assertEquals(1, counts[e])
        }

    @Test
    fun `isEmptyPaintLayer trusts the shared answer for the bitmap and generation, and not a stale one`() = runTest(dispatcher) {
        val e = blank()
        reopen(sketch("E", e))
        vm.paintScans.record("E", e, painted = true) // the cache says painted though the pixels are blank
        val layer = ui().layers.first { it.id == "E" }
        assertFalse("the cache hit decides", with(vm) { layer.isEmptyPaintLayer() })
        e.eraseColor(Color.TRANSPARENT) // a new generation: that answer no longer holds
        assertNull("a stale answer is not served", vm.paintScans.cached("E", e))
        assertFalse("and nothing is scanned here: unknown is not known empty", with(vm) { layer.isEmptyPaintLayer() })
        vm.onSceneOrbit(0.01f, 0f) // a layers write: the warm-up scans the new generation
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue("the landed scan answers", with(vm) { layer.isEmptyPaintLayer() })
    }

    @Test
    fun `a layer the app makes blank is recorded as blank, so its stroke start scans nothing`() = runTest(dispatcher) {
        setup()
        drawFirst()
        vm.onSceneOrbit(0.9f, 0.3f)
        assertTrue(vm.adoptViewPlaneForStroke())
        val made = ui().layers.first { it.id == ui().activeLayerId }
        assertTrue(made.id != "L")
        assertEquals("known blank at creation", false, vm.paintScans.cached(made.id, made.bitmap!!))
    }

    @Test
    fun `the flat-sheet planner off the main thread does not touch the scan cache`() = runTest(dispatcher) {
        vm.warmScans = false // the background warm-up would fill the cache itself; this is about the planner
        val child = blank()
        val group = Layer(id = "g", name = "g", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP)
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(group, sketch("c", child).copy(parentId = "g"))))
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.onArmSurfaceDraw("g")
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.paintScans.cached("c", child))
        assertTrue("the empty group may take a flat sheet", vm.canSkipSurfaceDrawOffMain())
        assertNull("and the planner left the cache alone", vm.paintScans.cached("c", child))
    }

    @Test
    fun `a painted bitmap the probe finds is recorded as painted, and the stroke start reads it as not empty`() =
        runTest(dispatcher) {
            val p = painted()
            vm.warmScans = false
            reopen(sketch("E", p))
            assertNull(vm.paintScans.cached("E", p))
            assertFalse(with(vm) { ui().layers.first { it.id == "E" }.isEmptyPaintLayer() })
            assertEquals("the exact probe answer is recorded", true, vm.paintScans.cached("E", p))
        }

    @Test
    fun `a new blank layer is recorded blank where it lands, with no scan`() = runTest(dispatcher) {
        vm.warmScans = false
        val counts = countScans()
        projectFlow.value = com.hereliesaz.graffitixr.common.model.GraffitiProject(id = "p")
        dispatcher.scheduler.advanceUntilIdle()
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.onAddBlankLayer()
        dispatcher.scheduler.advanceUntilIdle()
        val made = ui().layers.single()
        assertEquals("known blank at creation", false, vm.paintScans.cached(made.id, made.bitmap!!))
        assertEquals(0, counts.size)
    }

    @Test
    fun `a scan that is cancelled mid-way is stopped through the checkpoint it is given`() = runTest(dispatcher) {
        val b = painted()
        var threw = false
        vm.paintScans.scan = { _, check ->
            vm.paintScans.forget("L") // the layer goes away while its scan runs: the job is cancelled
            try {
                check()
            } catch (e: kotlinx.coroutines.CancellationException) {
                threw = true
                throw e
            }
            true
        }
        vm.paintScans.warm("L", b)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue("the checkpoint passed to the scan throws once its job is cancelled", threw)
        assertNull(vm.paintScans.cached("L", b))
    }

    @Test
    fun `the answer recorded for a layer does not keep its replaced bitmap alive`() = runTest(dispatcher) {
        var old: android.graphics.Bitmap? = blank()
        val ref = java.lang.ref.WeakReference(old)
        vm.paintScans.record("L", old!!, painted = false)
        old = null
        repeat(20) { if (ref.get() != null) { System.gc(); Thread.sleep(20) } }
        assertNull("only the weak answer held it", ref.get())
        assertNull(vm.paintScans.cached("L", blank()))
    }

    // ── Layers as the app makes them: a sketch layer with a file uri is paint, not imported content ──

    private fun isEmpty(layer: Layer): Boolean = with(vm) { layer.isEmptyPaintLayer() }

    @Test
    fun `a blank sketch layer with its file uri is an empty layer, and an imported image with a uri is not`() =
        runTest(dispatcher) {
            val known = blank()
            vm.paintScans.record("S", known, painted = false) // what the app records for a layer it makes blank
            assertTrue("every layer the app makes has a uri, and is still empty", isEmpty(sketch("S", known)))
            assertFalse("unknown (no answer yet) is not known empty", isEmpty(sketch("U", blank())))
            assertFalse("an imported image is content", isEmpty(Layer(id = "I", name = "I", bitmap = blank(), uri = fileUri)))
            val text = com.hereliesaz.graffitixr.common.model.TextLayerParams(text = "a")
            assertFalse("text is content", isEmpty(sketch("T", blank()).copy(textParams = text)))
        }

    @Test
    fun `a painted sketch layer with its file uri is not empty`() = runTest(dispatcher) {
        val p = painted()
        vm.warmScans = false
        assertFalse("the probe finds the paint", isEmpty(sketch("S", p)))
        assertEquals(true, vm.paintScans.cached("S", p))
    }

    @Test
    fun `with the grid off a blank sketch layer with its uri moves onto the view plane`() = runTest(dispatcher) {
        setup()
        drawFirst()
        vm.onToggleSurfaceGrid()
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers + sketch("E", blank())))
        vm.onLayerActivated("E")
        dispatcher.scheduler.advanceUntilIdle() // E's scan lands
        vm.onSceneOrbit(0.9f, 0.3f)
        val before = ui().layers.size
        assertTrue(vm.adoptViewPlaneForStroke())
        assertEquals("moved, not a new layer", before, ui().layers.size)
        assertEquals("E", ui().activeLayerId)
    }

    @Test
    fun `with the grid off an unknown blank sketch layer is left alone until its scan lands`() = runTest(dispatcher) {
        setup()
        drawFirst()
        vm.onToggleSurfaceGrid()
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers + sketch("E", blank())))
        vm.onLayerActivated("E")
        vm.onSceneOrbit(0.9f, 0.3f)
        val before = ui().layers.size
        assertFalse("unknown: no plane and no new layer with the grid off", vm.adoptViewPlaneForStroke())
        assertEquals(before, ui().layers.size)
    }

    @Test
    fun `an empty sketch layer is moved onto the plane, not duplicated by a new layer`() = runTest(dispatcher) {
        setup()
        vm.onSceneOrbit(0.5f, 0.1f)
        val before = ui().layers.size
        assertTrue(vm.adoptViewPlaneForStroke())
        assertEquals("the first stroke moved L onto the plane", before, ui().layers.size)
    }

    // ── A saved sketch layer whose bitmap is not decoded yet is unknown, not empty ──

    /** A project reopened with "E" (a saved sketch layer, full of art on disk) not decoded yet, after one stroke on "L". */
    private fun reopenedUndecoded(): Layer {
        setup()
        drawFirst()
        val e = Layer(id = "E", name = "E", isSketch = true, bitmap = null, uri = fileUri)
        vm.dispatchForTest(EditorIntent.SetLayers(ui().layers + e))
        vm.onLayerActivated("E")
        vm.onSceneOrbit(0.9f, 0.3f)
        return e
    }

    private fun poseOf(id: String) = ui().layers.first { it.id == id }.let {
        listOf(it.offset.x, it.offset.y, it.positionZ, it.rotationX, it.rotationY, it.rotationZ, it.scale)
    }

    @Test
    fun `a saved sketch layer with no bitmap is unknown, not empty, and a layer that never had pixels is empty`() {
        assertFalse("art on disk, not decoded yet", isEmpty(Layer(id = "S", name = "S", isSketch = true, bitmap = null, uri = fileUri)))
        assertTrue("never had pixels: no bitmap, no file", isEmpty(Layer(id = "N", name = "N", isSketch = true, bitmap = null, uri = null)))
        assertFalse("an imported image with no bitmap is content", isEmpty(Layer(id = "I", name = "I", bitmap = null, uri = fileUri)))
    }

    @Test
    fun `a recycled bitmap is unknown, not empty`() {
        val gone = blank().also { it.recycle() }
        assertFalse(isEmpty(sketch("R", gone)))
    }

    @Test
    fun `a layer whose strokes were baked into its base is not empty, though its bitmap reads blank`() = runTest(dispatcher) {
        setup()
        stroke()
        vm.bakeOldestStrokeForTest("L")
        val layer = ui().layers.first { it.id == "L" }
        vm.paintScans.record("L", layer.bitmap!!, painted = false) // a blank answer for this very bitmap
        assertEquals(0, vm.recordedStrokesForTest("L").size)
        assertTrue(vm.hasBakedStrokesForTest("L"))
        assertFalse("baked strokes are content", isEmpty(layer))
    }

    @Test
    fun `with the grid off a reopened layer not yet decoded is not moved onto the view plane`() = runTest(dispatcher) {
        reopenedUndecoded()
        vm.onToggleSurfaceGrid()
        val before = poseOf("E")
        val count = ui().layers.size
        assertFalse("unknown: nothing happens with the grid off", vm.adoptViewPlaneForStroke())
        assertEquals(before, poseOf("E"))
        assertEquals(count, ui().layers.size)
    }

    @Test
    fun `with the grid on a reopened layer not yet decoded keeps its pose and the stroke goes on a new layer`() =
        runTest(dispatcher) {
            reopenedUndecoded()
            val before = poseOf("E")
            val count = ui().layers.size
            assertTrue(vm.adoptViewPlaneForStroke())
            assertEquals("E is not moved", before, poseOf("E"))
            assertEquals("a new layer took the stroke", count + 1, ui().layers.size)
            assertNotEquals("E", ui().activeLayerId)
        }

    @Test
    fun `a chosen draw depth does not move a reopened layer not yet decoded either`() = runTest(dispatcher) {
        reopenedUndecoded()
        vm.onDrawDepthDrag(-150f)
        assertNotNull("a depth is set", vm.effectiveDrawDepth())
        val before = poseOf("E")
        val count = ui().layers.size
        assertTrue(vm.adoptViewPlaneForStroke())
        assertEquals("E is not moved", before, poseOf("E"))
        assertEquals("a new layer took the stroke", count + 1, ui().layers.size)
    }

    @Test
    fun `an armed group holding a layer not yet decoded is not offered the default flat sheet`() = runTest(dispatcher) {
        vm.warmScans = false
        val group = Layer(id = "g", name = "g", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP)
        val child = Layer(id = "c", name = "c", isSketch = true, bitmap = null, uri = fileUri, parentId = "g")
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(group, child)))
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.onArmSurfaceDraw("g")
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse("its art is unknown, so the sheet is not offered", vm.canSkipSurfaceDrawOffMain())
    }

    @Test
    fun `an imported image and a blank sketch layer with a bitmap keep their answers`() {
        val known = blank()
        vm.paintScans.record("B", known, painted = false)
        assertTrue("blank sketch with a bitmap is empty", isEmpty(sketch("B", known)))
        assertFalse("imported image with a bitmap is content", isEmpty(Layer(id = "I", name = "I", bitmap = blank(), uri = fileUri)))
    }
}
