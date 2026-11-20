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
    private var savedBytes: ByteArray? = null
    private var failBlank = false
    private val emitter = mockk<com.hereliesaz.graffitixr.common.coop.OpEmitter>(relaxed = true)
    private var vmRef: EditorViewModel? = null
    private var saveOnEmit = false

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
            coEvery { updateProject(any<(GraffitiProject) -> GraffitiProject>()) } answers {
                // The real repository applies the transform to the stored manifest.
                current.value?.let { current.value = firstArg<(GraffitiProject) -> GraffitiProject>()(it) }
            }
            every { this@mockk.projects } returns MutableStateFlow(emptyList())
            coEvery { saveArtifact(any(), any(), any()) } coAnswers {
                // saveProject also saves a thumbnail through here: only the layer's blank PNG is the one under test.
                val isBlankLayer = secondArg<String>().startsWith("layer_")
                if (isBlankLayer) events += "blank save started"
                if (isBlankLayer && failBlank) throw java.io.IOException("disk full")
                if (isBlankLayer) savedBytes = thirdArg()
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
        every { emitter.isActive } returns true
        every { emitter.emit(any()) } answers { if (saveOnEmit) vmRef?.saveProject() }
        return EditorViewModel(
            projectRepository = projects, settingsRepository = settings,
            projectManager = mockk(relaxed = true), exportManager = mockk(relaxed = true),
            context = RuntimeEnvironment.getApplication(), liquifyEngine = mockk(relaxed = true),
            dispatchers = EditorViewModelFixture.dispatchers(dispatcher), opEmitter = emitter,
            extensionRepository = extensions, repositoryApiClient = mockk(relaxed = true),
            customBrushRepository = brushes, figmaRepository = figma, projectFileScanner = mockk(relaxed = true),
        )
    }

    private fun built(): EditorViewModel = build().also { vmRef = it }

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

    @Test
    fun `no manifest names the new layer's file until that file is on disk, even on save and gesture end`() {
        val vm = build()
        settle()
        vm.setActiveTool(Tool.BRUSH)
        settle()
        assertEquals(1, vm.uiState.value.layers.size)

        // Inside the window: every kind of save the editor fires.
        vm.saveProject()
        vm.onGestureEnd()
        vm.saveProject("Renamed")
        settle()

        assertFalse("the blank write is still blocked", gate.isCompleted)
        assertTrue(
            "no manifest may name a layer whose file is not on disk yet: ${current.value?.layers}",
            current.value?.layers.orEmpty().isEmpty(),
        )
        coVerify(exactly = 0) { projects.createProject(any<GraffitiProject>()) }

        gate.complete(Unit)
        settle()
        assertEquals(
            "once the file is written the manifest names the layer",
            listOf(vm.uiState.value.layers.single().id), current.value?.layers?.map { it.id },
        )
    }

    @Test
    fun `killing the editor mid-persist leaves a manifest with no dangling layer`() {
        val vm = build()
        settle()
        vm.setActiveTool(Tool.BRUSH)
        settle()
        vm.saveProject()
        settle()
        // The process dies here: the gate never opens, so the blank PNG never lands.
        val onDisk = current.value ?: error("project")
        assertTrue("the stored project loads: no layer points at the missing file", onDisk.layers.isEmpty())
    }

    @Test
    fun `the blank PNG is encoded from a fresh bitmap, not the one the user is painting into`() {
        val vm = build()
        settle()
        vm.setActiveTool(Tool.BRUSH)
        settle()
        // A stroke commit or ensurePaintableBitmap can recycle the live bitmap while the write is in flight.
        vm.uiState.value.layers.single().bitmap!!.recycle()

        gate.complete(Unit)
        settle()

        assertTrue("a PNG was still written", (savedBytes?.size ?: 0) > 0)
        assertEquals(listOf(vm.uiState.value.layers.single().id), current.value?.layers?.map { it.id })
    }

    @Test
    fun `a save fired at the exact moment the layer lands still does not name its file`() {
        val vm = built()
        settle()
        // emitMembership runs right after the layer reaches uiState and before the blank write is started.
        saveOnEmit = true
        vm.setActiveTool(Tool.BRUSH)
        settle()
        assertFalse(gate.isCompleted)
        assertEquals(1, vm.uiState.value.layers.size)
        assertTrue("manifest: ${current.value?.layers}", current.value?.layers.orEmpty().isEmpty())
    }

    @Test
    fun `a failed blank write leaves no manifest entry until a stroke writes the file`() {
        failBlank = true
        val vm = built()
        settle()
        vm.setActiveTool(Tool.BRUSH)
        settle()
        vm.saveProject()
        vm.onGestureEnd()
        settle()
        assertEquals("the layer is still usable", 1, vm.uiState.value.layers.size)
        assertTrue("no dangling entry: ${current.value?.layers}", current.value?.layers.orEmpty().isEmpty())

        // A real pixel write creates the file; from then on the manifest may name the layer.
        val layer = vm.uiState.value.layers.single()
        val file = java.io.File.createTempFile("layer", ".png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        kotlinx.coroutines.CoroutineScope(dispatcher).launch {
            assertTrue(vm.writeLayerBitmapForTest(layer.id, file.path, bitmap))
        }
        settle()
        vm.saveProject()
        settle()
        assertEquals(listOf(layer.id), current.value?.layers?.map { it.id })
        file.delete()
    }

    @Test
    fun `when no memory is left for the blank bitmap the layer is likewise kept out of the manifest`() {
        io.mockk.mockkObject(com.hereliesaz.graffitixr.common.util.SafeBitmap)
        try {
            every { com.hereliesaz.graffitixr.common.util.SafeBitmap.create(any(), any(), any()) } returns null
            val vm = built()
            settle()
            vm.setActiveTool(Tool.BRUSH)
            settle()
            gate.complete(Unit)
            vm.saveProject()
            settle()
            assertEquals(1, vm.uiState.value.layers.size)
            assertTrue("manifest: ${current.value?.layers}", current.value?.layers.orEmpty().isEmpty())
        } finally {
            io.mockk.unmockkObject(com.hereliesaz.graffitixr.common.util.SafeBitmap)
        }
    }

    @Test
    fun `a save that snapshotted while the layer was pending still includes it if its file landed before the write`() {
        failBlank = true
        val vm = built()
        settle()
        vm.setActiveTool(Tool.BRUSH)
        settle()
        val layer = vm.uiState.value.layers.single()
        val file = java.io.File.createTempFile("layer", ".png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        // The repository applies the transform at write time. Between the save's snapshot (layer pending) and that
        // write, a real pixel write lands the file: the omission must be decided now, not at the snapshot.
        coEvery { projects.updateProject(any<(GraffitiProject) -> GraffitiProject>()) } coAnswers {
            assertTrue(vm.writeLayerBitmapForTest(layer.id, file.path, bitmap))
            current.value?.let { current.value = firstArg<(GraffitiProject) -> GraffitiProject>()(it) }
        }
        vm.saveProject()
        settle()
        assertEquals(listOf(layer.id), current.value?.layers?.map { it.id })
        file.delete()
    }

    @Test
    fun `a save with no open project neither cancels pending blank writes nor includes their layers`() {
        failBlank = true
        val vm = built()
        settle()
        vm.setActiveTool(Tool.BRUSH)
        settle()
        val layerId = vm.uiState.value.layers.single().id
        val created = mutableListOf<GraffitiProject>()
        coEvery { projects.createProject(any<GraffitiProject>()) } answers { created += firstArg<GraffitiProject>() }
        // No current project: persistProject then invents a fresh project id for the manifest it creates.
        every { projects.currentProject } returns MutableStateFlow<GraffitiProject?>(null)
        vm.saveProject()
        settle()
        assertTrue("a manifest was created: $created", created.isNotEmpty())
        assertTrue(
            "the pending layer must stay out of it: ${created.map { m -> m.layers.map { it.id } }}",
            created.none { m -> m.layers.any { it.id == layerId } },
        )
    }

    private companion object {
        const val ROUNDS = 20
        const val SLEEP_MS = 25L
    }
}
