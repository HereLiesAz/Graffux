package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import com.hereliesaz.graffitixr.data.brush.CustomBrushRepository
import com.hereliesaz.graffitixr.data.figma.FigmaRepository
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Picking a paint tool on an empty canvas makes a layer. The layer (and the tool with it) must land without waiting
 * for the blank PNG's durable write: until it lands the tool is NONE and touches pan or zoom, so a slow first
 * disk write used to cost the first strokes. The blank write still happens, and no later pixel write to the same
 * file can be overtaken by it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NewLayerLandsBeforePersistTest {
    private val dispatcher = StandardTestDispatcher()
    private val current = MutableStateFlow<GraffitiProject?>(GraffitiProject(id = "P"))
    private val gate = CompletableDeferred<Unit>()
    private val events = mutableListOf<String>()
    private lateinit var projects: ProjectRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun build(): EditorViewModel {
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { backgroundColor } returns MutableStateFlow(0)
            every { inputSampleRateHz } returns MutableStateFlow(120)
            every { canvasRenderScale } returns MutableStateFlow(1f)
            every { isRightHanded } returns MutableStateFlow(true)
            every { isImperialUnits } returns MutableStateFlow(false)
            every { gestureMapping } returns MutableStateFlow(emptyMap())
            every { savedPalette } returns MutableStateFlow(emptyList())
        }
        projects = mockk(relaxed = true) {
            every { currentProject } returns current
            every { this@mockk.projects } returns MutableStateFlow(emptyList())
            coEvery { saveArtifact(any(), any(), any()) } coAnswers {
                // saveProject also saves a thumbnail through here: only the layer's blank PNG is the one under test.
                val isBlankLayer = secondArg<String>().startsWith("layer_")
                if (isBlankLayer) events += "blank save started"
                if (isBlankLayer) gate.await()
                if (isBlankLayer) events += "blank save finished"
                val root = RuntimeEnvironment.getApplication().filesDir
                java.io.File(root, "projects/${firstArg<String>()}/${secondArg<String>()}").path
            }
        }
        val extensions = mockk<ExtensionRepository>(relaxed = true) {
            every { installed } returns MutableStateFlow(emptyList())
        }
        val brushes = mockk<CustomBrushRepository>(relaxed = true) {
            every { this@mockk.brushes } returns MutableStateFlow(emptyList())
        }
        val figma = mockk<FigmaRepository>(relaxed = true) {
            every { isAuthenticated } returns MutableStateFlow(false)
        }
        return EditorViewModel(
            projectRepository = projects, settingsRepository = settings,
            projectManager = mockk(relaxed = true), exportManager = mockk(relaxed = true),
            context = RuntimeEnvironment.getApplication(), liquifyEngine = mockk(relaxed = true),
            dispatchers = EditorViewModelFixture.dispatchers(dispatcher), opEmitter = mockk(relaxed = true),
            extensionRepository = extensions, repositoryApiClient = mockk(relaxed = true),
            customBrushRepository = brushes, figmaRepository = figma, projectFileScanner = mockk(relaxed = true),
        )
    }

    private fun settle() {
        repeat(ROUNDS) {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(SLEEP_MS)
        }
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `the layer lands and the tool activates while the blank PNG write is still pending`() {
        val vm = build()
        settle()

        vm.setActiveTool(Tool.BRUSH)
        settle()

        assertFalse("the blank write is still blocked", gate.isCompleted)
        val state = vm.uiState.value
        assertEquals("the layer landed without awaiting saveArtifact", 1, state.layers.size)
        assertEquals("and the tool the user picked is active on it", Tool.BRUSH, state.activeTool)
        assertEquals(state.layers.single().id, state.activeLayerId)
        // The blank write was started (after the layer landed), not skipped.
        coVerify(exactly = 1) { projects.saveArtifact("P", match { it.startsWith("layer_") }, any()) }
        assertEquals(listOf("blank save started"), events)

        gate.complete(Unit)
        settle()
        assertEquals(listOf("blank save started", "blank save finished"), events)
        coVerify(exactly = 1) { projects.saveArtifact("P", match { it.startsWith("layer_") }, any()) }
        assertTrue(vm.uiState.value.layers.single().uri.toString().startsWith("file://"))
    }

    @Test
    fun `a pixel write to the new layer's file waits for the blank write, so paint is never overwritten`() {
        val vm = build()
        settle()
        vm.setActiveTool(Tool.BRUSH)
        settle()
        val layerId = vm.uiState.value.layers.single().id
        // A recycled bitmap is the cheap stand-in for "a stroke's pixels": the write returns once it is allowed to
        // run, which is all this test needs to see (when, relative to the blank write).
        val painted = mockk<Bitmap>(relaxed = true) { every { isRecycled } returns true }

        kotlinx.coroutines.CoroutineScope(dispatcher).launch {
            vm.writeLayerBitmapForTest(layerId, "/p/layer.png", painted)
            events += "stroke pixels written"
        }
        settle()
        assertEquals(
            "the stroke's write must not run while the blank write is in flight",
            listOf("blank save started"), events,
        )

        gate.complete(Unit)
        settle()
        assertEquals(
            "blank first, stroke pixels last",
            listOf("blank save started", "blank save finished", "stroke pixels written"), events,
        )
        assertTrue(events.last() == "stroke pixels written")
    }

    private companion object {
        const val ROUNDS = 20
        const val SLEEP_MS = 25L
    }
}
