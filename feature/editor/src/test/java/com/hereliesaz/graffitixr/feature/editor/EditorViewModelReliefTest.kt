@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.ReliefBuildStage
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.nativebridge.LiquifyEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * "Build surface from image" through the view-model, with a fake depth source: every way it can end, one undo
 * step for the build, the live strength slider and the near/far swap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelReliefTest {

    private lateinit var viewModel: EditorViewModel
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val currentProjectFlow = kotlinx.coroutines.flow.MutableStateFlow<GraffitiProject?>(null)
    private val context: Context = mockk(relaxed = true)
    private val projectManager: ProjectManager = mockk(relaxed = true)
    private val exportManager: com.hereliesaz.graffitixr.feature.editor.export.ExportManager = mockk(relaxed = true)
    private val liquifyEngine: LiquifyEngine = mockk(relaxed = true)
    private val opEmitter: OpEmitter = mockk(relaxed = true)
    private val extensionRepository: ExtensionRepository = mockk(relaxed = true)
    private val customBrushRepository: com.hereliesaz.graffitixr.data.brush.CustomBrushRepository = mockk(relaxed = true)
    private val figmaRepository: com.hereliesaz.graffitixr.data.figma.FigmaRepository = mockk(relaxed = true)
    private val projectFileScanner: com.hereliesaz.graffitixr.data.ProjectFileScanner = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // A relaxed mock returns a mocked Flow whose collect never completes normally, and the
        // view-model collects this one in its init block — give it a real flow to collect.
        every { figmaRepository.isAuthenticated } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        // The OpenCV-backed singletons (ImageProcessor, SketchProcessor, StencilProcessor, …) call
        // NativeLibLoader.loadAll() in their init blocks; on a host JVM that throws (the .so is
        // Android-arm only). No-op it so those objects can initialise and have their methods mocked.
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        // Emit a test project so projectId is non-null, enabling onAddLayer to work
        val testProject = GraffitiProject(id = "test-project")
        currentProjectFlow.value = testProject
        every { projectRepository.currentProject } returns currentProjectFlow
        every { settingsRepository.backgroundColor } returns kotlinx.coroutines.flow.flowOf(0xFF000000.toInt())
        
        // Mock static methods for Bitmap and Uri
        mockkStatic(BitmapFactory::class)
        mockkStatic(android.graphics.Bitmap::class)
        mockkStatic(Uri::class)
        mockkObject(com.hereliesaz.graffitixr.common.util.ImageUtils)

        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every { mockBitmap.width } returns 100
        every { mockBitmap.height } returns 100
        every { mockBitmap.copy(any(), any()) } returns mockBitmap
        every { BitmapFactory.decodeStream(any()) } returns mockBitmap
        every { android.graphics.Bitmap.createBitmap(any<Int>(), any<Int>(), any()) } returns mockBitmap

        // Mock ImageUtils so ImageDecoder/BitmapFactory isn't invoked in unit tests
        coEvery {
            com.hereliesaz.graffitixr.common.util.ImageUtils.getBitmapDimensions(any(), any())
        } returns Pair(100, 100)
        coEvery {
            com.hereliesaz.graffitixr.common.util.ImageUtils.loadBitmapAsync(any(), any(), any())
        } returns mockBitmap
        coEvery { projectRepository.saveArtifact(any(), any(), any()) } returns "/path/to/artifact.png"
        coEvery { projectRepository.deleteArtifact(any(), any()) } returns true
        every { com.hereliesaz.graffitixr.common.util.ImageUtils.bitmapToByteArray(any()) } returns ByteArray(0)

        // Mock TextRasterizer and GoogleFontCache to avoid Android dependencies

        every { Uri.parse(any()) } answers {
            val uriString = it.invocation.args[0] as String
            val mUri = mockk<Uri>()
            every { mUri.toString() } returns uriString
            every { mUri.scheme } returns if (uriString.contains("://")) uriString.split("://")[0] else null
            every { mUri.path } returns if (uriString.contains("://")) uriString.split("://")[1] else uriString
            mUri
        }

        // Mock Context and ContentResolver
        val contentResolver = mockk<ContentResolver>()
        val inputStream = ByteArrayInputStream(ByteArray(0))
        every { context.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(any()) } returns inputStream

        viewModel = newViewModel()
    }

    private val testDispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    private fun newViewModel() = EditorViewModel(
        projectRepository, settingsRepository, projectManager, exportManager, context,
        liquifyEngine, testDispatcherProvider, opEmitter, extensionRepository,
        mockk(relaxed = true), customBrushRepository,
        figmaRepository, projectFileScanner,
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(BitmapFactory::class)
        unmockkStatic(android.graphics.Bitmap::class)
        unmockkStatic(Uri::class)
        unmockkObject(com.hereliesaz.graffitixr.common.util.ImageUtils)
        unmockkObject(NativeLibLoader)
    }


    private class FakeDepth(var availability: ReliefDepthAvailability = ReliefDepthAvailability.Ready) : ReliefDepthSource {
        var gate: CompletableDeferred<Unit>? = null
        var failure: ReliefDepthException? = null
        var calls = 0
        override suspend fun availability() = availability
        override suspend fun estimate(bitmap: Bitmap): ReliefDepth {
            calls++
            gate?.await()
            failure?.let { throw it }
            return ReliefDepth(8, 8, FloatArray(64) { (it % 8) / 7f })
        }
    }

    private val depth = FakeDepth()

    private fun layers() = viewModel.uiState.value.layers

    private fun withPhoto() {
        viewModel.onCanvasSizeChanged(IntSize(1000, 800))
        viewModel.onAddLayer(Uri.parse("content://test/a.png"))
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.reliefDepthSource = depth
        // The test device has no screen size, so the layer is added at scale 0; a real photo sits at 1 or less.
        viewModel.onScaleChanged(1f)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun notices(block: () -> Unit): List<EditorNotice> {
        val seen = mutableListOf<EditorNotice>()
        val job = kotlinx.coroutines.CoroutineScope(testDispatcher).launch { viewModel.notices.collect { seen += it } }
        testDispatcher.scheduler.advanceUntilIdle()
        block()
        testDispatcher.scheduler.advanceUntilIdle()
        job.cancel()
        return seen
    }

    @Test
    fun `with no depth model installed it says so and changes nothing`() {
        withPhoto()
        depth.availability = ReliefDepthAvailability.NoModel
        val before = layers()
        val seen = notices { viewModel.onBuildSurfaceFromImage() }
        assertTrue(seen.single().text.contains("No depth model is installed"))
        assertEquals(NoticeKind.WARNING, seen.single().kind)
        assertEquals(before, layers())
        assertNull(viewModel.uiState.value.reliefBuild)
        assertEquals(0, depth.calls)
    }

    @Test
    fun `a model still downloading says how far and changes nothing`() {
        withPhoto()
        depth.availability = ReliefDepthAvailability.Downloading(40)
        val seen = notices { viewModel.onBuildSurfaceFromImage() }
        assertTrue(seen.single().text.contains("still downloading (40%)"))
        assertEquals(1, layers().size)
        assertNull(viewModel.uiState.value.reliefBuild)
    }

    @Test
    fun `an unusable model is reported with its reason`() {
        withPhoto()
        depth.availability = ReliefDepthAvailability.Unavailable("no checksum")
        val seen = notices { viewModel.onBuildSurfaceFromImage() }
        assertTrue(seen.single().text.contains("no checksum"))
    }

    @Test
    fun `the photo becomes the only paint of a relief group in one undo step`() {
        withPhoto()
        val photo = layers().single()
        val seen = notices { viewModel.onBuildSurfaceFromImage() }
        assertEquals(NoticeKind.NOTICE, seen.last().kind)
        val group = layers().single { it.surface?.relief != null }
        assertEquals(photo.id, layers().single { it.parentId == group.id }.id)
        assertNull(viewModel.uiState.value.reliefBuild)
        assertTrue(group.surface!!.isValid)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(photo.id), layers().map { it.id })
        assertNull(layers().single().parentId)
        viewModel.onRedoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(layers().singleOrNull { it.surface?.relief != null })
    }

    @Test
    fun `while the model runs the build shows its step and Cancel stops it without a change`() {
        withPhoto()
        depth.gate = CompletableDeferred()
        val before = layers()
        val seen = notices {
            viewModel.onBuildSurfaceFromImage()
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(ReliefBuildStage.ESTIMATING, viewModel.uiState.value.reliefBuild?.stage)
            viewModel.onCancelReliefBuild()
        }
        assertTrue(seen.last().text.contains("cancelled"))
        assertNull(viewModel.uiState.value.reliefBuild)
        assertEquals(before, layers())
    }

    @Test
    fun `a second build while one runs is refused`() {
        withPhoto()
        depth.gate = CompletableDeferred()
        val seen = notices {
            viewModel.onBuildSurfaceFromImage()
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.onBuildSurfaceFromImage()
        }
        assertTrue(seen.single().text.contains("already being built"))
        assertEquals(1, depth.calls)
    }

    @Test
    fun `a model failure is a notice, not a crash or a half surface`() {
        withPhoto()
        depth.failure = ReliefDepthException("The depth model failed while running.")
        val seen = notices { viewModel.onBuildSurfaceFromImage() }
        assertEquals("The depth model failed while running.", seen.single().text)
        assertEquals(1, layers().size)
        assertNull(viewModel.uiState.value.reliefBuild)
    }

    @Test
    fun `with no layer selected it asks for one`() {
        viewModel.onCanvasSizeChanged(IntSize(1000, 800))
        val seen = notices { viewModel.onBuildSurfaceFromImage() }
        assertEquals("Select a photo layer first.", seen.single().text)
    }

    @Test
    fun `the strength drag is one undo step and the swap is another`() {
        withPhoto()
        notices { viewModel.onBuildSurfaceFromImage() }
        val group = layers().single { it.surface?.relief != null }.id
        fun relief() = layers().single { it.id == group }.surface!!.relief!!
        val original = relief().strength
        viewModel.onReliefStrength(group, 0.4f)
        viewModel.onReliefStrength(group, 0.6f)
        viewModel.onReliefTuneDone()
        assertEquals(0.6f, relief().strength, 1e-4f)
        viewModel.onReliefInvert(group)
        assertTrue(relief().inverted)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, relief().inverted)
        assertEquals(0.6f, relief().strength, 1e-4f)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(original, relief().strength, 1e-4f)
    }
}
