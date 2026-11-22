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

    /** The depth the rule asks for now: the nearest edge of every plane that has content in the project. */
    private fun expectedEdge(only: Set<String>? = null): Float = expectedEdgeOrNull(only)!!

    private fun expectedEdgeOrNull(only: Set<String>? = null): Float? = NearestEdge.depth(
        NearestEdge.outlines(ui().layers, 48f, 48f) { l -> !with(vm) { l.isEmptyPaintLayer() } && (only == null || l.id in only) },
        cam(),
    )

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
        val edge = expectedEdge()
        assertTrue("the edge and the pivot differ here", kotlin.math.abs(edge - pivotDepth()) > 1f)
        assertTrue(vm.adoptViewPlaneForStroke())
        val made = ui().layers.first { it.id == ui().activeLayerId }
        assertTrue(made.id != "L")
        assertEquals(edge, centreDepth(made), 0.5f)
        assertTrue(kotlin.math.abs(centreDepth(made) - pivotDepth()) > 1f)
    }

    @Test
    fun `the third plane meets the nearest edge of the first and second together`() = runTest(dispatcher) {
        setup()
        drawFirst()
        vm.onSceneOrbit(0.9f, 0.3f)
        val secondEdge = expectedEdge()
        assertTrue(vm.adoptViewPlaneForStroke())
        stroke()
        val second = ui().activeLayerId!!
        assertEquals(secondEdge, centreDepth(ui().layers.first { it.id == second }), 0.5f)
        // Over several views the nearest edge is sometimes the first plane's and sometimes the second's.
        val views = listOf(-1.4f to -0.2f, 1.2f to 0.4f, 0.1f to -0.6f, 2.0f to 0.2f, -0.5f to 0.9f)
        val owners = views.mapNotNull { (yaw, pitch) ->
            vm.setSceneCameraForTest(SceneCamera(yaw = yaw, pitch = pitch))
            val first = expectedEdgeOrNull(setOf("L"))
            val last = expectedEdgeOrNull(setOf(second))
            if (first == null || last == null) return@mapNotNull null
            assertEquals(minOf(first, last), expectedEdge(), 0.01f)
            if (first < last) "L" else second
        }.toSet()
        assertEquals("both planes are the nearest in some view", setOf("L", second), owners)
        for ((yaw, pitch) in views) {
            vm.setSceneCameraForTest(SceneCamera(yaw = yaw, pitch = pitch))
            val both = expectedEdgeOrNull() ?: continue
            assertTrue(vm.adoptViewPlaneForStroke())
            val third = ui().layers.first { it.id == ui().activeLayerId }
            assertEquals("view $yaw/$pitch: the union, not the last plane alone", both, centreDepth(third), 0.5f)
            vm.onUndoClicked()
            dispatcher.scheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `the draw depth factor is relative to the new automatic depth`() = runTest(dispatcher) {
        setup()
        drawFirst()
        vm.onSceneOrbit(0.9f, 0.3f)
        val edge = expectedEdge()
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
}
