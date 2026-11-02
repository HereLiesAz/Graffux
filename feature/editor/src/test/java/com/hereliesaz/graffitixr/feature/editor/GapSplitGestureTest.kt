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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.runner.RunWith

/** A stroke the active layer's plane breaks at a gap is still one gesture (PlaneStrokeFeed). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GapSplitGestureTest {

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

    private fun ready(tool: Tool = Tool.BRUSH) {
        val original = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(Layer(id = "L", name = "Layer", bitmap = original))))
        vm.putLayerBaseForTest("L", original)
        vm.onLayerActivated("L")
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.setActiveTool(tool)
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** One finger's gesture through the feed, the plane missed for [gap] in the middle. */
    private fun gesture(endWith: (PlaneStrokeFeed<BrushSample>) -> Unit) {
        val hits = listOf(true, true, false, true, true)
        var n = 0
        val feed = PlaneStrokeFeed<BrushSample>(
            toLayer = { s -> s.takeIf { hits[n++] } },
            start = { vm.onStrokeStart(it, canvasSize) },
            point = { vm.onStrokePoint(it) },
            end = { vm.onStrokeEnd() },
            cancel = { vm.onStrokeCancel() },
            segmentEnd = { vm.onStrokeSegmentEnd() },
        )
        feed.onStart(BrushSample(6f, 12f, uptimeMillis = 0L))
        (1 until hits.size).forEach { feed.onPoint(BrushSample(6f + it * 8f, 12f, uptimeMillis = it * 16L)) }
        endWith(feed)
    }

    @Test
    fun `a gesture the plane split at a gap is one undo step`() = runTest(dispatcher) {
        // Blur strokes never join an undo chunk on their own, so only the gesture joins them.
        ready(Tool.BLUR)
        val before = vm.uiState.value.undoCount
        gesture { it.onEnd() }
        assertEquals(before + 1, vm.uiState.value.undoCount)
    }

    @Test
    fun `a second-finger cancel after a gap takes back every segment of the gesture`() = runTest(dispatcher) {
        ready()
        val before = vm.uiState.value.undoCount
        gesture { it.onCancel() }
        assertEquals(before, vm.uiState.value.undoCount)
        // Nothing to redo either: the gesture never happened.
        assertEquals(0, vm.uiState.value.redoCount)
        // The next gesture is its own step, not joined to anything.
        gesture { it.onEnd() }
        assertEquals(before + 1, vm.uiState.value.undoCount)
    }
}
