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
 * "Insert surface": a ready-made surface becomes a new group, as one undo step.
 * Existing layers and groups are untouched; a rejected payload changes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelSurfaceInsertTest {

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


    private fun payload(text: String = SLAB) =
        (com.hereliesaz.graffitixr.common.scene.SurfacePayload.parse(text) as com.hereliesaz.graffitixr.common.scene.SurfaceParse.Ok).payload

    private fun layers() = viewModel.uiState.value.layers

    private fun withPhoto() {
        viewModel.onCanvasSizeChanged(IntSize(1000, 800))
        viewModel.onAddLayer(Uri.parse("content://test/a.png"))
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `inserting creates a new top-level surface group, active, and leaves the rest alone`() {
        withPhoto()
        val before = layers()
        assertTrue(viewModel.onInsertSurface(payload()))
        val group = layers().single { it.id !in before.map { l -> l.id } }
        assertEquals("Slab", group.name)
        assertEquals(com.hereliesaz.graffitixr.common.model.LayerType.GROUP, group.type)
        assertNull(group.parentId)
        assertTrue(group.surface!!.isValid)
        assertNotNull(group.surface!!.side)
        assertEquals(group.id, viewModel.uiState.value.activeLayerId)
        assertEquals(before, layers().filter { it.id != group.id })
    }

    @Test
    fun `one undo removes the group and redo brings it back`() {
        withPhoto()
        val before = layers().map { it.id }
        viewModel.onInsertSurface(payload())
        assertEquals(before.size + 1, layers().size)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(before, layers().map { it.id })
        viewModel.onRedoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(before.size + 1, layers().size)
        assertNotNull(layers().singleOrNull { it.surface != null })
    }

    @Test
    fun `each insert is its own group`() {
        viewModel.onCanvasSizeChanged(IntSize(1000, 800))
        viewModel.onInsertSurface(payload())
        viewModel.onInsertSurface(payload())
        assertEquals(2, layers().count { it.surface != null })
        assertEquals(2, layers().map { it.id }.toSet().size)
    }

    @Test
    fun `before the canvas has a size nothing is inserted`() {
        assertEquals(false, viewModel.onInsertSurface(payload()))
        assertTrue(layers().none { it.surface != null })
    }

    @Test
    fun `a surface that fails validation on read says so and changes nothing`() {
        viewModel.onCanvasSizeChanged(IntSize(1000, 800))
        every { extensionRepository.loadSurface("com.x", 0) } returns
            com.hereliesaz.graffitixr.common.scene.SurfaceParse.Rejected("front is required")
        val before = layers()
        viewModel.onInsertInstalledSurface("com.x", 0)
        assertEquals(before, layers())
    }

    @Test
    fun `an installed surface is read through the repository and inserted`() {
        viewModel.onCanvasSizeChanged(IntSize(1000, 800))
        every { extensionRepository.loadSurface("com.x", 0) } returns
            com.hereliesaz.graffitixr.common.scene.SurfaceParse.Ok(payload())
        viewModel.onInsertInstalledSurface("com.x", 0)
        assertEquals(1, layers().count { it.surface != null })
    }

    private companion object {
        const val SLAB = """{"format":"azphalt.surface","version":1,"name":"Slab","front":[[0.05,0.25],[0.95,0.25],[0.95,0.75],[0.05,0.75]],"side":[[0,0.25],[0.4,0.25],[0.4,0.75],[0,0.75]],"top":[[0.05,0],[0.95,0],[0.95,0.4],[0.05,0.4]]}"""
    }
}
