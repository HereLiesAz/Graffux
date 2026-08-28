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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.runner.RunWith

/**
 * A glee audit found that a stroke's async commit coroutine (`commitStampStroke`,
 * `processNewStroke`) was never registered in `rebuildJobs` -- the map
 * `rebuildLayerBitmap`/`applyTileDeltaFastPath` use to cancel each other's stale publishes. So a
 * fast Undo landing right after a stroke commit could race that commit's own in-flight publish:
 * if the commit coroutine happened to take longer than undo's own rebuild, its never-cancelled
 * publish could land after undo's and silently resurrect the just-undone stroke's pixels.
 *
 * This tests the mechanism directly -- that the commit's job is (a) registered in `rebuildJobs`
 * at all and (b) actually cancelled by an immediately following undo -- rather than trying to
 * reproduce the exact bitmap race end-to-end. An end-to-end pixel comparison was tried first and
 * discarded: under `StandardTestDispatcher`, every coroutine here runs perfectly synchronously and
 * deterministically, and a later-launched coroutine's continuations always dequeue (and thus
 * publish) after an earlier-launched one's equal-depth continuations regardless of cancellation --
 * so undo's rebuild always "wins" the last-publish race by sheer enqueue order whether or not the
 * fix is present, and no pixel-level assertion can distinguish the two. Asserting on the job
 * object itself has no such blind spot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class UndoRaceRegressionTest {

    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val canvasSize = IntSize(48, 48)
    private val brush = AzphaltBrush(name = "Solid", hardness = 1f, opacity = 1f)

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
            exportManager = mockk(relaxed = true),
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            slamManager = mockk(relaxed = true),
            dispatchers = dispatchers,
            opEmitter = mockk(relaxed = true),
            extensionRepository = extensions,
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

    @Test
    fun `undo called immediately after a stroke commit cancels that commit's still-pending rebuild job`() = runTest(dispatcher) {
        val original = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
        val layer = Layer(id = "L", name = "Layer", bitmap = original)
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(layer)))
        vm.putLayerBaseForTest("L", original)
        vm.onLayerActivated("L")
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.selectCustomBrush("solid")
        vm.setActiveTool(Tool.BRUSH)
        dispatcher.scheduler.advanceUntilIdle()

        vm.onStrokeStart(BrushSample(12f, 12f, uptimeMillis = 0L), canvasSize)
        vm.onStrokePoint(BrushSample(18f, 12f, uptimeMillis = 16L))
        vm.onStrokeEnd()

        // StandardTestDispatcher only enqueues -- nothing has run yet, so this is exactly the
        // commit coroutine the fix is supposed to have registered before it ever got a chance to
        // execute.
        val commitJob = vm.rebuildJobForTest("L")
        assertNotNull(
            "the stroke commit's coroutine must be tracked in rebuildJobs so a following undo can cancel it",
            commitJob,
        )
        assertFalse("the commit job must not already be cancelled before undo runs", commitJob!!.isCancelled)

        // Neither the commit's own coroutine nor undo's rebuild has run yet. Calling onUndoClicked
        // here, before advancing, is exactly the race: undo should synchronously cancel whatever
        // commit job is currently registered for this layer, before that job ever gets to run.
        vm.onUndoClicked()

        assertTrue(
            "onUndoClicked must cancel the still-in-flight commit job so its late publish can't " +
                "resurrect the undone stroke",
            commitJob.isCancelled,
        )

        dispatcher.scheduler.advanceUntilIdle()
    }
}
