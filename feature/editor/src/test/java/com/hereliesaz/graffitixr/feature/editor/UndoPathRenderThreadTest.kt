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
import com.hereliesaz.graffitixr.nativebridge.GpuRenderThread
import com.hereliesaz.graffitixr.nativebridge.GpuUndoSnapshots
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A glee audit found that a stroke's async commit coroutine (`commitStampStroke`)
 * was never registered in `rebuildJobs` -- the map
 * `rebuildLayerBitmap`/`applyTileDeltaFastPath` use to cancel each other's stale publishes. So a
 * fast Undo landing right after a stroke commit could race that commit's own in-flight publish:
 * if the commit coroutine happened to take longer than undo's own rebuild, its never-cancelled
 * publish could land after undo's and silently resurrect the just-undone stroke's pixels.
 *
 * The undo tests check the mechanism -- that the commit's job is (a) registered in `rebuildJobs`
 * and (b) cancelled by an immediately following undo -- because under `StandardTestDispatcher`
 * undo's rebuild always publishes last by enqueue order, fix or no fix. They also check what the
 * layer finally shows. The back-to-back test needs no such caveat: losing the first commit is
 * visible in the pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class UndoPathRenderThreadTest {

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

    private val red = Color.RED
    private val spot1 = com.hereliesaz.graffitixr.common.azphalt.DirtyRegion(10, 10, 14, 14)
    private val spot2 = com.hereliesaz.graffitixr.common.azphalt.DirtyRegion(30, 30, 34, 34)

    /**
     * A stroke whose commit has not run (the test dispatcher is not advanced): its history entry and recorded stroke
     * exist, and its tile delta and GPU undo snapshot are attached as a GPU commit would have. No GPU paints here.
     */
    private fun strokeOnly(y: Float) {
        vm.onStrokeStart(BrushSample(12f, y, uptimeMillis = 0L), canvasSize)
        vm.onStrokePoint(BrushSample(26f, y, uptimeMillis = 16L))
        vm.onStrokeEnd()
    }

    private fun attachDelta(index: Int, spot: com.hereliesaz.graffitixr.common.azphalt.DirtyRegion, refId: Long) {
        val n = spot.width * spot.height
        val delta = com.hereliesaz.graffitixr.common.azphalt.TileDelta.TileSnapshot(
            0, 0, spot, IntArray(n), IntArray(n) { red },
        )
        vm.attachTileDeltasForTest(
            vm.undoDrawCommandsForTest()[index], listOf(delta), bitmap().width, bitmap().height,
            GpuUndoSnapshots.refForTest(1L, refId),
        )
    }

    private fun bitmap() = vm.uiState.value.layers.first { it.id == "L" }.bitmap!!

    private fun painted(spot: com.hereliesaz.graffitixr.common.azphalt.DirtyRegion) =
        bitmap().getPixel(spot.left + 1, spot.top + 1) == red

    @Test
    fun `undo undo redo return while the render thread is held, end right, and free each snapshot once`() =
        runTest(dispatcher) {
            val original = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
            vm.dispatchForTest(EditorIntent.SetLayers(listOf(Layer(id = "L", name = "Layer", bitmap = original))))
            vm.putLayerBaseForTest("L", original)
            vm.onLayerActivated("L")
            vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
            vm.selectCustomBrush("solid")
            vm.setActiveTool(Tool.BRUSH)
            dispatcher.scheduler.advanceUntilIdle()
            // LayerStore.removeLastStroke invalidates the residency entry before the fast path asks for it (so in
            // the app the GPU swap is not reached today); the hook leaves the entry alone so the swap path runs.
            vm.stopLayerStoreInvalidationForTest()
            strokeOnly(12f)
            // A different tool: two like strokes close together would join one undo chunk.
            vm.setActiveTool(Tool.ERASER)
            strokeOnly(30f)
            // The layer as the two strokes left it (after the strokes' own bitmap handling).
            val b0 = bitmap()
            for (spot in listOf(spot1, spot2)) {
                for (y in spot.top until spot.bottom) for (x in spot.left until spot.right) b0.setPixel(x, y, red)
            }
            attachDelta(0, spot1, 101L)
            attachDelta(1, spot2, 102L)
            vm.layerResidencyForTest().generationFor("L", bitmap())
            assertEquals("deltas attached to the top entry", 1, vm.topUndoTileDeltaCountForTest())
            assertTrue("both strokes painted", painted(spot1) && painted(spot2))
            val drops = GpuUndoSnapshots.dropRequests.get()
            val swaps = GpuUndoSnapshots.swapRequests.get()
            val generation = vm.layerResidencyForTest().currentGeneration("L")

            // The fake render thread: the real one, held, so every queued swap waits.
            val release = CountDownLatch(1)
            val held = CountDownLatch(1)
            GpuRenderThread.post {
                held.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            assertTrue(held.await(5, TimeUnit.SECONDS))

            val t0 = System.nanoTime()
            vm.onUndoClicked()
            vm.onUndoClicked()
            vm.onRedoClicked()
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("undo, undo, redo took $ms ms with the render thread held", ms < 2_000)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue("redo brought the first stroke back", painted(spot1))
            assertFalse("the second stroke stays undone", painted(spot2))

            assertTrue(
                "the GPU swaps were queued (undo, undo, redo), not skipped",
                GpuUndoSnapshots.swapRequests.get() - swaps >= 3,
            )
            release.countDown()
            GpuRenderThread.flush()
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(painted(spot1))
            assertFalse(painted(spot2))
            assertEquals(
                "each of the two snapshots is freed exactly once",
                2, GpuUndoSnapshots.dropRequests.get() - drops,
            )
            assertTrue(
                "the residency entry was invalidated, not left claiming the GPU copy matches",
                vm.layerResidencyForTest().currentGeneration("L") != generation,
            )
        }
}
