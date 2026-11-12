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
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.runner.RunWith

/**
 * A brush double-tap in a project with a 3D group resets the view and leaves no mark: the first tap's
 * dot is removed by identity, whatever the undo stack looked like (empty, full, or a recent stroke the
 * dot would otherwise have joined as a chunk).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DoubleTapDotTest {

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

    private fun setup() {
        val original = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
        val group = Layer(
            id = "G", name = "G", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP, surface = wall,
        )
        val layer = Layer(id = "L", name = "Layer", bitmap = original, parentId = "G")
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(group, layer)))
        vm.putLayerBaseForTest("L", original)
        vm.onLayerActivated("L")
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.selectCustomBrush("solid")
        vm.setActiveTool(Tool.BRUSH)
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun stroke(x0: Float, x1: Float, y: Float) {
        vm.onStrokeStart(BrushSample(x0, y, uptimeMillis = 0L), canvasSize)
        vm.onStrokePoint(BrushSample(x1, y, uptimeMillis = 16L))
        vm.onStrokeEnd()
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** The first tap: asks the hook, then paints its dot as DrawingCanvas does. */
    private fun firstTap(x: Float, y: Float, t: Long) {
        assertFalse(vm.onBrushTap(androidx.compose.ui.geometry.Offset(x, y), t))
        vm.onStrokeStart(BrushSample(x, y, uptimeMillis = t), canvasSize)
        vm.onStrokePoint(BrushSample(x, y, uptimeMillis = t + 1))
        vm.onStrokeEnd()
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun pixels(): IntArray {
        val b = vm.uiState.value.layers.first { it.id == "L" }.bitmap!!
        return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    }

    private fun secondTapRemovesDot(t0: Long) {
        assertTrue(vm.onBrushTap(androidx.compose.ui.geometry.Offset(31f, 30f), t0 + 120))
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `empty stack - the double-tap leaves no pixels, no history, no redo and a reset view`() = runTest(dispatcher) {
        setup()
        vm.onSceneOrbit(0.4f, 0.2f)
        val before = pixels()
        val u = vm.uiState.value.undoCount
        firstTap(30f, 30f, 1000L)
        assertEquals("the dot is an entry of its own", u + 1, vm.uiState.value.undoCount)
        secondTapRemovesDot(1000L)
        assertEquals(u, vm.uiState.value.undoCount)
        assertEquals(0, vm.uiState.value.redoCount)
        assertTrue(before.contentEquals(pixels()))
        assertTrue(vm.uiState.value.sceneCamera.isDefault)
    }

    @Test
    fun `full stack - the double-tap loses no earlier entry`() = runTest(dispatcher) {
        setup()
        repeat(20) { i ->
            vm.dispatchForTest(EditorIntent.SetLayers(vm.uiState.value.layers.map { it.copy(name = "n$i") }))
            vm.pushHistoryForTest()
        }
        val u = vm.uiState.value.undoCount
        assertEquals(20, u)
        firstTap(30f, 30f, 1000L)
        secondTapRemovesDot(1000L)
        assertEquals("the oldest entry is not trimmed away", u, vm.uiState.value.undoCount)
        assertEquals(0, vm.uiState.value.redoCount)
    }

    @Test
    fun `a dot that would chunk-join an earlier stroke is its own entry and the stroke survives`() =
        runTest(dispatcher) {
            setup()
            stroke(5f, 15f, 10f)
            val afterStroke = pixels()
            val u = vm.uiState.value.undoCount
            firstTap(30f, 30f, 1000L)
            assertEquals("not merged into the stroke's entry", u + 1, vm.uiState.value.undoCount)
            secondTapRemovesDot(1000L)
            assertEquals(u, vm.uiState.value.undoCount)
            assertEquals(0, vm.uiState.value.redoCount)
            assertTrue("the earlier stroke is intact", afterStroke.contentEquals(pixels()))
        }
}
