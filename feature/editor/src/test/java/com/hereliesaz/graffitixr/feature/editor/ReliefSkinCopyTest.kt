@file:Suppress("MaxLineLength", "LargeClass", "TooManyFunctions")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import com.hereliesaz.graffitixr.data.brush.CustomBrushRepository
import com.hereliesaz.graffitixr.data.figma.FigmaRepository
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The skin of "Build surface from image" is an independent copy of the photo, made off the main thread, whose file is
 * never left behind, and which is not the photo's component or instance (spec/3d-space.md, "The photo is duplicated").
 * Real bitmaps (Robolectric native graphics): a mocked bitmap would answer every copy with itself.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class ReliefSkinCopyTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val projectFlow = MutableStateFlow<GraffitiProject?>(null)
    private val projectRepo = mockk<ProjectRepository>(relaxed = true)
    private val canvasSize = IntSize(48, 48)
    private val saved = mutableListOf<String>()
    private var duringWrite: () -> Unit = {}
    private var captured: GraffitiProject? = null

    private val depth = object : ReliefDepthSource {
        override suspend fun availability() = ReliefDepthAvailability.Ready
        override suspend fun estimate(bitmap: Bitmap) = ReliefDepth(8, 8, FloatArray(64) { (it % 8) / 7f })
    }

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
        every { projectRepo.currentProject } returns projectFlow
        every { projectRepo.projects } returns MutableStateFlow(emptyList())
        coEvery { projectRepo.saveArtifact(any(), any(), any()) } answers {
            val name = secondArg<String>()
            saved += name
            // Only the skin's file: the other layers' pixel files are written by the ordinary saves.
            if (name.startsWith("layer_dup_")) duringWrite()
            "/tmp/${secondArg<String>()}"
        }
        coEvery { projectRepo.updateProject(any<(GraffitiProject) -> GraffitiProject>()) } answers {
            val next = firstArg<(GraffitiProject) -> GraffitiProject>()(projectFlow.value ?: GraffitiProject(id = "p"))
            captured = next
            projectFlow.value = next
        }
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val io = dispatcher
            override val default = dispatcher
            override val unconfined = dispatcher
        }
        vm = EditorViewModel(
            projectRepository = projectRepo,
            settingsRepository = settings,
            projectManager = mockk(relaxed = true),
            exportManager = mockk(relaxed = true),
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            liquifyEngine = mockk(relaxed = true),
            dispatchers = dispatchers,
            opEmitter = mockk(relaxed = true),
            extensionRepository = mockk<ExtensionRepository>(relaxed = true) {
                every { installed } returns MutableStateFlow(emptyList())
            },
            repositoryApiClient = mockk(relaxed = true),
            customBrushRepository = mockk<CustomBrushRepository>(relaxed = true) {
                every { brushes } returns MutableStateFlow(emptyList())
            },
            figmaRepository = mockk<FigmaRepository>(relaxed = true) {
                every { isAuthenticated } returns MutableStateFlow(false)
            },
            projectFileScanner = mockk(relaxed = true),
        )
        vm.reliefDepthSource = depth
        // The view model's init collectors start on the first idle; let them run before a test sets its layers.
        dispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun layers() = vm.uiState.value.layers

    private fun dupName() = saved.single { it.startsWith("layer_dup_") }

    private fun photo(extra: (Layer) -> Layer = { it }) = extra(
        Layer(
            id = "P", name = "Photo", bitmap = RenderTestBase.filled(48, 48, Color.RED),
            uri = android.net.Uri.parse("file:///photo.png"), scale = 1f,
        ),
    )

    private fun open(withProject: Boolean = true, vararg start: Layer) {
        if (withProject) {
            projectFlow.value = GraffitiProject(id = "p")
            // The loaded project replaces the layers when it lands: let it, then set the test's layers.
            dispatcher.scheduler.advanceUntilIdle()
        }
        vm.dispatchForTest(EditorIntent.SetLayers(start.toList()))
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.onLayerActivated("P")
        start.firstOrNull { it.id == "P" }?.bitmap?.let { vm.putLayerBaseForTest("P", it) }
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun build() {
        vm.onBuildSurfaceFromImage()
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun skinOf(): Layer = layers().single { it.id == vm.uiState.value.activeLayerId }

    // --- independence ---------------------------------------------------------------------------

    @Test
    fun theSkinIsAnIndependentCopyOfThePhotoOnItsOwnFileWithItsOwnBase() {
        val original = photo()
        open(true, original)
        build()
        val skin = skinOf()
        val kept = layers().single { it.id == "P" }
        assertNotSame("the skin has its own bitmap", kept.bitmap, skin.bitmap)
        assertNotNull(skin.bitmap)
        assertEquals(Color.RED, skin.bitmap!!.getPixel(3, 3))
        val dup = saved.filter { it.startsWith("layer_dup_") }
        assertEquals("one pixel file written, for the skin", 1, dup.size)
        assertTrue(skin.uri.toString().endsWith(dup.single()))
        assertNotNull(skin.uri)
        assertTrue("not the photo's file", skin.uri != kept.uri)
        val base = vm.layerBaseForTest(skin.id)
        assertNotNull("the skin has a registered base", base)
        assertNotSame(skin.bitmap, base)
        assertNotSame(vm.layerBaseForTest("P"), base)
        assertEquals(Color.RED, base!!.getPixel(3, 3))
    }

    @Test
    fun paintingOnTheSkinDoesNotChangeThePhoto() {
        open(true, photo())
        build()
        val skin = skinOf()
        val kept = layers().single { it.id == "P" }
        skin.bitmap!!.setPixel(3, 3, Color.BLUE)
        assertEquals(Color.RED, kept.bitmap!!.getPixel(3, 3))
        assertEquals("nor the skin's base", Color.RED, vm.layerBaseForTest(skin.id)!!.getPixel(3, 3))
        kept.bitmap!!.setPixel(5, 5, Color.GREEN)
        assertEquals(Color.RED, skin.bitmap!!.getPixel(5, 5))
    }

    // --- no project -----------------------------------------------------------------------------

    @Test
    fun withNoProjectOpenAReliefIsStillBuiltAndTheSkinHasNoFile() {
        open(false, photo())
        build()
        assertEquals(4, layers().size)
        assertNull(skinOf().uri)
        assertTrue(saved.none { it.startsWith("layer_dup_") })
        coVerify(exactly = 0) { projectRepo.deleteArtifact(any(), any()) }
    }

    // --- the file is never left behind -----------------------------------------------------------

    @Test
    fun cancelDuringThePngWriteRemovesTheFileAndChangesNothing() {
        open(true, photo())
        val before = layers()
        duringWrite = { vm.onCancelReliefBuild() }
        build()
        assertEquals(before, layers())
        assertEquals(1, saved.count { it.startsWith("layer_dup_") })
        coVerify(exactly = 1) { projectRepo.deleteArtifact("p", dupName()) }
        assertNull(vm.uiState.value.reliefBuild)
    }

    @Test
    fun aPhotoChangedDuringTheWriteIsNotBuiltAndItsFileIsRemoved() {
        open(true, photo())
        duringWrite = {
            vm.dispatchForTest(EditorIntent.SetLayers(layers().map { it.copy(offset = Offset(5f, 0f)) }))
        }
        build()
        assertEquals(1, layers().size)
        coVerify(exactly = 1) { projectRepo.deleteArtifact("p", dupName()) }
    }

    @Test
    fun aListReplacedByAnotherEditDuringTheWriteDoesNotAbortTheBuild() {
        open(true, photo())
        duringWrite = {
            vm.dispatchForTest(EditorIntent.SetLayers(layers() + Layer(id = "X", name = "Other")))
        }
        build()
        assertEquals(5, layers().size)
        assertTrue(layers().any { it.id == "X" })
        assertNotNull(layers().singleOrNull { it.surface?.relief != null })
        coVerify(exactly = 0) { projectRepo.deleteArtifact(any(), any()) }
    }

    @Test
    fun aFileThatCannotBeWrittenIsAnOwnNoticeNotACrash() {
        open(true, photo())
        coEvery { projectRepo.saveArtifact(any(), any(), any()) } throws java.io.IOException("full")
        build()
        assertEquals(1, layers().size)
        assertNull(vm.uiState.value.reliefBuild)
    }

    // --- identity ---------------------------------------------------------------------------------

    @Test
    fun theSkinOfAComponentMainIsNotAMainAndAnInstanceKeepsTheOriginalAsItsMain() {
        val main = photo { it.copy(componentId = "c") }
        val instance = Layer(id = "I", name = "Instance", instanceOf = "c", originX = 0)
        open(true, main, instance)
        build()
        val skin = skinOf()
        assertNull(skin.componentId)
        assertNull(skin.instanceOf)
        assertEquals("c", layers().single { it.id == "P" }.componentId)
        assertEquals(1, layers().count { it.componentId == "c" })
    }

    @Test
    fun theSkinOfAnInstanceIsNotAnInstance() {
        val main = Layer(id = "M", name = "Main", componentId = "c")
        val instancePhoto = photo { it.copy(instanceOf = "c", isLinked = true, clipToLayerBelow = true) }
        open(true, main, instancePhoto)
        build()
        val skin = skinOf()
        assertNull(skin.instanceOf)
        assertFalse(skin.isLinked)
        assertFalse(skin.clipToLayerBelow)
        assertEquals("c", layers().single { it.id == "P" }.instanceOf)
    }

    // --- builder-driven round trip ----------------------------------------------------------------

    @Test
    fun aBuiltReliefSurvivesASaveAndALoad() {
        open(true, photo())
        build()
        val built = layers()
        vm.saveProject()
        dispatcher.scheduler.advanceUntilIdle()
        val project = captured
        assertNotNull("the project was saved", project)
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val back = json.decodeFromString(GraffitiProject.serializer(), json.encodeToString(GraffitiProject.serializer(), project!!))
        assertEquals(built.map { it.id }, back.layers.map { it.id })
        assertEquals(built.map { it.parentId }, back.layers.map { it.parentId })
        assertEquals(built.map { it.isVisible }, back.layers.map { it.isVisible })
        assertEquals(listOf(true, true, false, true), back.layers.map { it.isVisible })
        assertEquals(built[0].surface!!.relief, back.layers[0].surface!!.relief)
        assertTrue(back.layers[2].surface!!.isFlat && back.layers[2].surface!!.isValid)
        assertEquals(built[2].surface!!.reach, back.layers[2].surface!!.reach)
    }
}
