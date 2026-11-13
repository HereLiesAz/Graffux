package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.scene.planePose
import com.hereliesaz.graffitixr.common.scene.SurfaceView
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.is3DIn
import com.hereliesaz.graffitixr.common.model.SurfaceDrawNotice
import com.hereliesaz.graffitixr.common.model.surfaceDrawArmed
import com.hereliesaz.graffitixr.common.model.isPlacedIn3D
import com.hereliesaz.graffitixr.common.model.isSpatial

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.toLayerProps
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.nativebridge.LiquifyEngine
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

import com.hereliesaz.graffitixr.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import com.hereliesaz.graffitixr.common.model.TextLayerParams
import com.hereliesaz.graffitixr.common.model.VectorShape
import com.hereliesaz.graffitixr.common.model.ShapeKind

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

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

    /** The default dispatcher: runs on [testDispatcher], or holds its work until [release] so a scan can lag. */
    private val scanDispatcher = object : CoroutineDispatcher() {
        var held = false
        val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (held) pending.addLast(block) else testDispatcher.dispatch(context, block)
        }

        fun release() {
            held = false
            val none = kotlin.coroutines.EmptyCoroutineContext
            while (pending.isNotEmpty()) testDispatcher.dispatch(none, pending.removeFirst())
        }
    }

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
        mockkObject(TextRasterizer)
        mockkObject(GoogleFontCache)

        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every { mockBitmap.width } returns 100
        every { mockBitmap.height } returns 100
        every { mockBitmap.copy(any(), any()) } returns mockBitmap
        every { BitmapFactory.decodeStream(any()) } returns mockBitmap
        every { android.graphics.Bitmap.createBitmap(any<Int>(), any<Int>(), any()) } returns mockBitmap

        // Mock ImageUtils so ImageDecoder/BitmapFactory isn't invoked in unit tests
        coEvery { com.hereliesaz.graffitixr.common.util.ImageUtils.getBitmapDimensions(any(), any()) } returns Pair(100, 100)
        coEvery { com.hereliesaz.graffitixr.common.util.ImageUtils.loadBitmapAsync(any(), any(), any()) } returns mockBitmap
        coEvery { projectRepository.saveArtifact(any(), any(), any()) } returns "/path/to/artifact.png"
        coEvery { projectRepository.deleteArtifact(any(), any()) } answers {
            // the real repository deletes under its save lock; the fixture deletes the file the view model points at
            // (a test that never set filesDir has a relaxed mock there: nothing to delete)
            runCatching {
                val f = java.io.File(context.filesDir, "projects/${firstArg<String>()}/${secondArg<String>()}")
                !f.exists() || f.delete()
            }.getOrDefault(true)
        }
        every { com.hereliesaz.graffitixr.common.util.ImageUtils.bitmapToByteArray(any()) } returns ByteArray(0)

        // Mock TextRasterizer and GoogleFontCache to avoid Android dependencies
        every { TextRasterizer.rasterize(any(), any(), any(), any(), any()) } returns mockBitmap
        coEvery { GoogleFontCache.getTypeface(any(), any(), any(), any()) } returns mockk(relaxed = true)

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

        val testDispatcherProvider = object : DispatcherProvider {
            override val main: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = scanDispatcher
            override val unconfined: CoroutineDispatcher = testDispatcher
        }

        viewModel = EditorViewModel(
            projectRepository, settingsRepository, projectManager, exportManager, context,
            liquifyEngine, testDispatcherProvider, opEmitter, extensionRepository,
            mockk(relaxed = true), customBrushRepository,
            figmaRepository, projectFileScanner,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(BitmapFactory::class)
        unmockkStatic(android.graphics.Bitmap::class)
        unmockkStatic(Uri::class)
        unmockkObject(com.hereliesaz.graffitixr.common.util.ImageUtils)
        unmockkObject(TextRasterizer)
        unmockkObject(GoogleFontCache)
        unmockkObject(NativeLibLoader)
    }

    @Test
    fun `exporting an empty animation emits one notice instead of a toast`() = runTest {
        val seen = mutableListOf<EditorNotice>()
        val job = launch(testDispatcher) { viewModel.notices.collect { seen += it } }
        testDispatcher.scheduler.runCurrent()
        viewModel.exportAnimation()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(EditorNotice(NoticeKind.NOTICE, "Nothing to export")), seen)
        job.cancel()
    }

    @Test
    fun `initial state is correct`() {
        val state = viewModel.uiState.value
        assertTrue(state.layers.isEmpty())
        assertNull(state.activeLayerId)
    }

    @Test
    fun `onAddLayer adds a layer`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertEquals(1, state.layers.size)
        assertNotNull(state.activeLayerId)
        assertEquals(state.layers.first().id, state.activeLayerId)
    }

    @Test
    fun `onLayerActivated updates activeLayerId`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val layerId = viewModel.uiState.value.layers.first().id
        viewModel.onLayerActivated(layerId)
        
        assertEquals(layerId, viewModel.uiState.value.activeLayerId)
    }

    @Test
    fun `onScaleChanged updates active layer`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val layerId = viewModel.uiState.value.layers.first().id
        viewModel.onLayerActivated(layerId)
        
        viewModel.onScaleChanged(2.0f)
        assertEquals(2.0f, viewModel.uiState.value.layers.first().scale)
    }

    @Test
    fun `onOffsetChanged updates active layer`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val layerId = viewModel.uiState.value.layers.first().id
        viewModel.onLayerActivated(layerId)
        
        val newOffset = Offset(10f, 20f)
        viewModel.onOffsetChanged(newOffset)
        assertEquals(newOffset, viewModel.uiState.value.layers.first().offset)
    }

    @Test
    fun `toggleImageLock updates state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val layerId = viewModel.uiState.value.layers.first().id
        viewModel.onLayerActivated(layerId)
        
        assertFalse(viewModel.uiState.value.layers.first().isImageLocked)
        viewModel.toggleImageLock()
        assertTrue(viewModel.uiState.value.layers.first().isImageLocked)
    }

    @Test
    fun `saveProject calls createProject when no project exists`() = runTest {
        currentProjectFlow.value = null
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        viewModel.saveProject()
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { projectRepository.createProject(any<GraffitiProject>()) }
    }

    @Test
    fun `onLayerRemoved removes layer and clears active ID if necessary`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val layerId = viewModel.uiState.value.layers.first().id
        viewModel.onLayerRemoved(layerId)
        
        assertTrue(viewModel.uiState.value.layers.isEmpty())
        assertNull(viewModel.uiState.value.activeLayerId)
    }

    @Test
    fun `undoing a removed layer restores its bitmap, not a permanently blank layer`() = runTest {
        // Regression: onLayerRemoved used to call layerStore.remove(id) immediately, discarding the
        // exact base+strokes cache the undo path needs to rebuild pixels from. The layer came back
        // in the panel, at the right z-order, rendering nothing, for the rest of the session.
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        val layerId = viewModel.uiState.value.layers.first().id
        viewModel.onLayerRemoved(layerId)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.layers.isEmpty())

        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val restored = viewModel.uiState.value.layers.find { it.id == layerId }
        assertNotNull("removed layer should reappear on undo", restored)
        assertNotNull("restored layer must have its pixels back, not a blank bitmap", restored!!.bitmap)
    }

    @Test
    fun `closing the open project resets the undo and redo counts`() = runTest {
        // Regression: history.clear() on project close wasn't paired with updateHistoryCounts(),
        // leaving undoCount/redoCount at whatever they were a moment ago — an enabled Undo control
        // wired to stacks that are now empty, which flashes "Undo" and changes nothing forever.
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.undoCount > 0)

        currentProjectFlow.value = null
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.undoCount)
        assertEquals(0, viewModel.uiState.value.redoCount)
    }

    @Test
    fun `opening another project straight from this one resets the undo and redo history`() = runTest {
        // File > Open / New replace the current project without passing through null, so the
        // reset used to happen only on close: the next project opened with this one's undo stack,
        // and an Undo restored (and saved) this project's layers into it.
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.undoCount > 0)

        currentProjectFlow.value = GraffitiProject(id = "another-project")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("another-project", viewModel.uiState.value.projectId)
        assertEquals(0, viewModel.uiState.value.undoCount)
        assertEquals(0, viewModel.uiState.value.redoCount)
        val layersBefore = viewModel.uiState.value.layers
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Undo must not bring the previous project's layers back", layersBefore, viewModel.uiState.value.layers)
    }

    @Test
    fun `a rail toggle survives a refused or failed project write`() = runTest {
        coEvery { projectRepository.updateProject(any<(GraffitiProject) -> GraffitiProject>()) } throws
            java.io.IOException("transient read failure")
        viewModel.onRailHostExpansionChanged("host", true)
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { projectRepository.updateProject(any<(GraffitiProject) -> GraffitiProject>()) } throws
            com.hereliesaz.graffitixr.domain.repository.ProjectNotSavedException("test-project")
        viewModel.onRailHostExpansionChanged("host", false)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 2) { projectRepository.updateProject(any<(GraffitiProject) -> GraffitiProject>()) }
    }

    @Test
    fun `saveProject calls updateProject when project exists`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.saveProject()
        testDispatcher.scheduler.advanceUntilIdle()

        // Updates now go through the atomic transform overload (read-modify-write) so a concurrent
        // AR wall-map save can't clobber the layer edits — see the save-race fix.
        coVerify { projectRepository.updateProject(any<(GraffitiProject) -> GraffitiProject>()) }
    }

    @Test
    fun `undo restores previous state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertEquals(1, viewModel.uiState.value.layers.size)
        
        viewModel.onUndoClicked()
        assertEquals(0, viewModel.uiState.value.layers.size)
    }

    @Test
    fun `redo restores undone state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        viewModel.onUndoClicked()
        assertEquals(0, viewModel.uiState.value.layers.size)
        
        viewModel.onRedoClicked()
        assertEquals(1, viewModel.uiState.value.layers.size)
    }

    @Test
    fun `gesture undo restores state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        val initialScale = viewModel.uiState.value.layers.first().scale
        
        // Start gesture
        viewModel.onGestureStart()
        testDispatcher.scheduler.advanceUntilIdle()

        // Transform
        viewModel.onTransformGesture(Offset.Zero, 2.0f, 0f)
        testDispatcher.scheduler.advanceUntilIdle()

        val modifiedScale = viewModel.uiState.value.layers.first().scale
        assertEquals(initialScale * 2.0f, modifiedScale, 0.01f)

        // End gesture
        viewModel.onGestureEnd()
        testDispatcher.scheduler.advanceUntilIdle()

        // Undo
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val restoredScale = viewModel.uiState.value.layers.first().scale
        assertEquals(initialScale, restoredScale, 0.01f)
    }

    @Test
    fun `setActiveTool on an empty project creates a layer and keeps the tool active`() = runTest {
        // Regression: picking a raster tool with no layers used to race a separate coroutine that
        // polled for the layer and dispatched SetActiveTool afterward, which AddLayer's own
        // activeTool reset could beat (or, on a slow/failed first write, never land at all) —
        // leaving activeTool stuck on NONE so every touch fell through to canvas pan instead of
        // painting. The tool must now land atomically with the layer, in the same dispatch.
        assertTrue(viewModel.uiState.value.layers.isEmpty())

        viewModel.setActiveTool(Tool.BRUSH)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.layers.size)
        assertEquals(Tool.BRUSH, state.activeTool)
        assertEquals(state.layers.first().id, state.activeLayerId)
    }

    @Test
    fun `onStrokeStart replays all buffered points after bitmap copy`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        val layerId = viewModel.uiState.value.layers.first().id
        viewModel.onLayerActivated(layerId)
        viewModel.setActiveTool(Tool.BRUSH)
        testDispatcher.scheduler.advanceUntilIdle()

        val canvasSize = IntSize(100, 100)

        viewModel.onStrokeStart(Offset(10f, 10f), canvasSize)
        viewModel.onStrokePoint(Offset(20f, 20f))
        viewModel.onStrokePoint(Offset(30f, 30f))
        viewModel.onStrokePoint(Offset(40f, 40f))
        testDispatcher.scheduler.advanceUntilIdle()

        val live = viewModel.liveStroke.value
        assertNotNull(live.bitmap)
        assertTrue("Expected liveStroke.version >= 1, got ${live.version}", live.version >= 1)
    }

    @Test
    fun `layers with textParams null are the image-bearing ones through add and remove`() = runTest {
        // Allow init coroutines to run so projectId is populated before any layer operations.
        testDispatcher.scheduler.advanceUntilIdle()

        // 1. Initial empty state -> no stencil content
        assertFalse(viewModel.uiState.value.layers.any { it.textParams == null })

        // 2. Add text layer -> still no stencil content (textParams is non-null for text layers)
        viewModel.onAddTextLayer()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.layers.size)
        assertFalse(viewModel.uiState.value.layers.any { it.textParams == null })

        // 3. Add image layer -> stencil content exists (image layers have textParams == null)
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.layers.size)
        assertTrue(viewModel.uiState.value.layers.any { it.textParams == null })

        // 4. Remove image layer -> back to only text layer, no stencil content
        val imageLayerId = viewModel.uiState.value.layers.find { it.textParams == null }!!.id
        viewModel.onLayerRemoved(imageLayerId)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.layers.size)
        assertFalse(viewModel.uiState.value.layers.any { it.textParams == null })

        // 5. Add blank sketch layer -> stencil content exists again (textParams == null)
        viewModel.onAddBlankLayer()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.layers.size)
        assertTrue(viewModel.uiState.value.layers.any { it.textParams == null })
    }

    // ── Frame resize + constraints ───────────────────────────────────────────────────────────

    /** A 400x200 frame holding one right-pinned child and one stretching child. */
    private fun seedFrame() {
        val frame = Layer(
            id = "F", name = "F", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP,
            layoutWidth = 400f, layoutHeight = 200f,
        )
        val end = Layer(
            id = "end", name = "end", parentId = "F", offset = Offset(130f, 0f),
            constraints = com.hereliesaz.graffitixr.common.model.Constraints(
                horizontal = com.hereliesaz.graffitixr.common.model.ConstraintAnchor.END,
            ),
            shapes = listOf(VectorShape(kind = ShapeKind.RECTANGLE, width = 100f, height = 50f)),
        )
        val fill = Layer(
            id = "fill", name = "fill", parentId = "F",
            constraints = com.hereliesaz.graffitixr.common.model.Constraints(
                horizontal = com.hereliesaz.graffitixr.common.model.ConstraintAnchor.STRETCH,
            ),
            shapes = listOf(VectorShape(kind = ShapeKind.RECTANGLE, width = 360f, height = 50f)),
        )
        viewModel.dispatchForTest(EditorIntent.SetLayers(listOf(frame, end, fill)))
        viewModel.onLayerActivated("F")
    }

    private fun layer(id: String) = viewModel.uiState.value.layers.first { it.id == id }

    @Test
    fun `the Frame tool leaves an image-locked layer where it is`() = runTest {
        viewModel.dispatchForTest(
            EditorIntent.AppendLayer(
                Layer(
                    id = "locked", name = "locked", isImageLocked = true,
                    shapes = listOf(VectorShape(kind = ShapeKind.RECTANGLE, width = 20f, height = 20f)),
                ),
            ),
        )
        viewModel.onDesignDragStart("locked")
        viewModel.onDesignMove("locked", Offset(100f, 0f))
        viewModel.onDesignDragEnd("locked", moved = true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(Offset.Zero, layer("locked").offset)
    }

    @Test
    fun `a mutation with no emit of its own still reaches peers, and a peer's op is not echoed`() = runTest {
        every { opEmitter.isActive } returns true
        viewModel.dispatchForTest(EditorIntent.AppendLayer(Layer(id = "c", name = "c")))
        testDispatcher.scheduler.advanceUntilIdle()
        val end = com.hereliesaz.graffitixr.common.model.Constraints(
            com.hereliesaz.graffitixr.common.model.ConstraintAnchor.END,
        )
        viewModel.dispatchForTest(EditorIntent.SetLayerConstraints("c", end))
        testDispatcher.scheduler.advanceUntilIdle()
        verify {
            opEmitter.emit(
                match { it is Op.LayerPropsChange && it.layerId == "c" && it.props.extended?.constraints == end },
            )
        }
        viewModel.dispatchForTest(
            EditorIntent.SetLayerTransformById("c", scale = 2f, offset = Offset(5f, 0f), rx = 0f, ry = 0f, rz = 0f),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        verify { opEmitter.emit(match { it is Op.LayerTransform && it.layerId == "c" && it.matrix[0] == 2f }) }

        val renamed = viewModel.uiState.value.layers.first { it.id == "c" }.copy(name = "from peer")
        viewModel.applySpectatorOp(Op.LayerPropsChange("c", renamed.toLayerProps()))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("from peer", viewModel.uiState.value.layers.first { it.id == "c" }.name)
        verify(exactly = 0) {
            opEmitter.emit(match { it is Op.LayerPropsChange && it.props.extended?.name == "from peer" })
        }
    }

    @Test
    fun `an edit to a main component reaches its instance and its peers`() = runTest {
        // Audit round 2, #3: instances followed their main only for path-node edits.
        every { opEmitter.isActive } returns true
        viewModel.dispatchForTest(EditorIntent.AppendLayer(Layer(id = "m", name = "m", componentId = "C1")))
        viewModel.dispatchForTest(EditorIntent.AppendLayer(Layer(id = "i", name = "i", instanceOf = "C1")))
        viewModel.dispatchForTest(EditorIntent.ActivateLayer("m"))
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.dispatchForTest(EditorIntent.SetSaturation(0.25f))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0.25f, viewModel.uiState.value.layers.first { it.id == "i" }.saturation)
        verify {
            opEmitter.emit(match { it is Op.LayerPropsChange && it.layerId == "i" && it.props.saturation == 0.25f })
        }
    }

    @Test
    fun `an instance that inherits its main's new pixels replays from them`() = runTest {
        // Codex on #669: the instance's replay base stayed the one recorded when it was placed.
        val old = mockk<Bitmap>(relaxed = true)
        val oldBase = mockk<Bitmap>(relaxed = true)
        every { old.copy(any(), any()) } returns oldBase
        val painted = mockk<Bitmap>(relaxed = true)
        val paintedBase = mockk<Bitmap>(relaxed = true)
        every { painted.copy(any(), any()) } returns paintedBase
        viewModel.dispatchForTest(
            EditorIntent.SetLayers(
                listOf(
                    Layer(id = "m", name = "m", componentId = "C1", bitmap = old),
                    Layer(id = "i", name = "i", instanceOf = "C1", bitmap = old),
                ),
            ),
        )
        viewModel.putLayerBaseForTest("i", old)
        testDispatcher.scheduler.advanceUntilIdle()
        assertSame(oldBase, viewModel.layerBaseForTest("i"))

        viewModel.dispatchForTest(
            EditorIntent.RenderTextLayer("m", painted, com.hereliesaz.graffitixr.common.model.TextLayerParams()),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertSame(painted, viewModel.uiState.value.layers.first { it.id == "i" }.bitmap)
        assertSame(paintedBase, viewModel.layerBaseForTest("i"))
    }

    @Test
    fun `an older instance rebase that finishes last does not overwrite the newer one`() = runTest {
        // Codex on #680: two main edits close together; the older rebase's copy finished last.
        val seed = mockk<Bitmap>(relaxed = true)
        val first = mockk<Bitmap>(relaxed = true)
        val firstBase = mockk<Bitmap>(relaxed = true)
        val second = mockk<Bitmap>(relaxed = true)
        val secondBase = mockk<Bitmap>(relaxed = true)
        every { second.copy(any(), any()) } returns secondBase
        viewModel.dispatchForTest(
            EditorIntent.SetLayers(
                listOf(
                    Layer(id = "m", name = "m", componentId = "C1", bitmap = seed),
                    Layer(id = "i", name = "i", instanceOf = "C1", bitmap = seed),
                ),
            ),
        )
        val params = com.hereliesaz.graffitixr.common.model.TextLayerParams()
        viewModel.dispatchForTest(EditorIntent.RenderTextLayer("m", first, params))
        // While the older rebase is still copying, the main paints again and that rebase completes.
        every { first.copy(any(), any()) } answers {
            viewModel.dispatchForTest(EditorIntent.RenderTextLayer("m", second, params))
            kotlinx.coroutines.runBlocking { viewModel.rebaseInstanceForTest("i", "m", second) }
            firstBase
        }
        viewModel.rebaseInstanceForTest("i", "m", first)
        assertSame(secondBase, viewModel.layerBaseForTest("i"))
    }

    // ── Instance pixel files are copy-on-write ──────────────────────────────────────────────

    private fun pngWriting(text: String): Bitmap = mockk<Bitmap>(relaxed = true).also { bmp ->
        every { bmp.width } returns 4
        every { bmp.height } returns 4
        every { bmp.compress(any(), any(), any()) } answers {
            (it.invocation.args[2] as java.io.OutputStream).write(text.toByteArray())
            true
        }
    }

    private fun instanceProject(dir: java.io.File, mainBmp: Bitmap, instBmp: Bitmap): java.io.File {
        val mainFile = java.io.File(dir, "main.png").apply { writeText("MAIN") }
        val shared = Uri.parse("file://${mainFile.path}")
        viewModel.dispatchForTest(
            EditorIntent.SetLayers(
                listOf(
                    Layer(id = "m", name = "m", componentId = "C1", uri = shared, bitmap = mainBmp),
                    Layer(id = "i", name = "i", instanceOf = "C1", uri = shared, bitmap = instBmp),
                    Layer(id = "f", name = "f", instanceOf = "C1", uri = shared, bitmap = mainBmp),
                ),
            ),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        return mainFile
    }

    @Test
    fun `saving an instance's own paint leaves its main's pixel file intact`() = runTest {
        val dir = kotlin.io.path.createTempDirectory("instpix").toFile()
        try {
            val mainBmp = pngWriting("MAIN")
            val painted = pngWriting("INST")
            val mainFile = instanceProject(dir, mainBmp, painted)

            viewModel.scheduleDiskSaveForTest("i", painted)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("MAIN", mainFile.readText())
            val layers = viewModel.uiState.value.layers
            val inst = layers.first { it.id == "i" }
            assertNotEquals(mainFile.path, inst.uri?.path)
            assertEquals("INST", java.io.File(inst.uri!!.path!!).readText())
            // A second save stays on the instance's own file rather than forking again.
            viewModel.scheduleDiskSaveForTest("i", painted)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(inst.uri, viewModel.uiState.value.layers.first { it.id == "i" }.uri)

            // Reopening: the main keeps its file and pixels, the painted instance its own file,
            // and the instance that never painted still inherits the main's.
            assertEquals(
                mapOf("f" to "m"),
                com.hereliesaz.graffitixr.common.model.pixelInheritorsOnLoad(layers),
            )
            assertEquals(mainFile.path, layers.first { it.id == "m" }.uri?.path)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an instance's own file is published only once it has been written`() = runTest {
        // Codex on #681: a manifest saved during the save debounce named a file not yet on disk.
        val dir = kotlin.io.path.createTempDirectory("instpix").toFile()
        try {
            val painted = pngWriting("INST")
            val mainFile = instanceProject(dir, pngWriting("MAIN"), painted)

            viewModel.scheduleDiskSaveForTest("i", painted)
            testDispatcher.scheduler.advanceTimeBy(1000)
            testDispatcher.scheduler.runCurrent()
            assertEquals(mainFile.path, viewModel.uiState.value.layers.first { it.id == "i" }.uri?.path)
            // A second save inside the window reuses the same pending file.
            viewModel.scheduleDiskSaveForTest("i", painted)
            testDispatcher.scheduler.advanceUntilIdle()

            val own = viewModel.uiState.value.layers.first { it.id == "i" }.uri!!
            assertNotEquals(mainFile.path, own.path)
            assertTrue(java.io.File(own.path!!).isFile)
            assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("layer_inst_") && it.name.endsWith(".png") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `property undo keeps each layer's live pixel file`() = runTest {
        // Codex on #681: a property snapshot restored a stale URI beside the live bitmap.
        val dir = kotlin.io.path.createTempDirectory("instpix").toFile()
        try {
            val painted = pngWriting("INST")
            val mainFile = instanceProject(dir, pngWriting("MAIN"), painted)
            viewModel.pushHistoryForTest() // snapshot: the instance on the main's file
            viewModel.scheduleDiskSaveForTest("i", painted)
            testDispatcher.scheduler.advanceUntilIdle()
            val own = viewModel.uiState.value.layers.first { it.id == "i" }.uri
            assertNotEquals(mainFile.path, own?.path)

            viewModel.onUndoClicked()
            testDispatcher.scheduler.advanceUntilIdle()
            val inst = viewModel.uiState.value.layers.first { it.id == "i" }
            assertSame(painted, inst.bitmap)
            assertEquals(own?.path, inst.uri?.path)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an inheriting instance writes nothing and keeps following its main`() = runTest {
        val dir = kotlin.io.path.createTempDirectory("instpix").toFile()
        try {
            val mainBmp = pngWriting("MAIN")
            val mainFile = instanceProject(dir, mainBmp, pngWriting("INST"))
            val shared = viewModel.uiState.value.layers.first { it.id == "m" }.uri

            assertNull(viewModel.ownPixelFileForTest("f", mainBmp))
            viewModel.scheduleDiskSaveForTest("f", mainBmp)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("MAIN", mainFile.readText())
            assertSame(shared, viewModel.uiState.value.layers.first { it.id == "f" }.uri)

            val repainted = pngWriting("MAIN2")
            viewModel.dispatchForTest(
                EditorIntent.RenderTextLayer("m", repainted, com.hereliesaz.graffitixr.common.model.TextLayerParams()),
            )
            testDispatcher.scheduler.advanceUntilIdle()
            assertSame(repainted, viewModel.uiState.value.layers.first { it.id == "f" }.bitmap)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `undoing an instance's only stroke makes it inherit its main again`() = runTest {
        every { opEmitter.isActive } returns true
        val dir = kotlin.io.path.createTempDirectory("instpix").toFile()
        try {
            val mainBmp = pngWriting("MAIN")
            val painted = pngWriting("INST")
            val mainFile = instanceProject(dir, mainBmp, painted)
            val base = mockk<Bitmap>(relaxed = true)
            viewModel.putLayerBaseForTest("i", base)
            val recorded = viewModel.layerBaseForTest("i")!!
            viewModel.recordInheritedBaseForTest("i", recorded, mainBmp)
            viewModel.scheduleDiskSaveForTest("i", painted)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.reinheritOnUndoForTest("i"))
            val inst = viewModel.uiState.value.layers.first { it.id == "i" }
            assertSame(mainBmp, inst.bitmap)
            assertEquals(mainFile.path, inst.uri?.path)
            testDispatcher.scheduler.advanceUntilIdle()
            // Co-op peers get the restored pixels too (Codex on #681).
            verify { opEmitter.emit(match { it is Op.LayerBitmapReplace && it.layerId == "i" }) }
            // Nothing left to undo back to: a second call is a no-op.
            assertFalse(viewModel.reinheritOnUndoForTest("i"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a freshly placed instance re-inherits when its first stroke is undone`() = runTest {
        // Codex on #681: placement recorded no inherited base, so place -> stroke -> undo never relinked.
        val dir = kotlin.io.path.createTempDirectory("instpix").toFile()
        try {
            val mainFile = java.io.File(dir, "main.png").apply { writeText("MAIN") }
            val mainBmp = pngWriting("MAIN")
            viewModel.dispatchForTest(
                EditorIntent.SetLayers(
                    listOf(
                        Layer(
                            id = "m", name = "m", componentId = "C1",
                            uri = Uri.parse("file://${mainFile.path}"), bitmap = mainBmp,
                        ),
                    ),
                ),
            )
            viewModel.onPlaceInstance("C1")
            testDispatcher.scheduler.advanceUntilIdle()
            val id = viewModel.uiState.value.layers.first { it.instanceOf == "C1" }.id

            // The stroke: the instance shows pixels of its own, and saving them forks its file.
            val painted = pngWriting("INST")
            viewModel.dispatchForTest(
                EditorIntent.RenderTextLayer(id, painted, com.hereliesaz.graffitixr.common.model.TextLayerParams()),
            )
            viewModel.scheduleDiskSaveForTest(id, painted)
            testDispatcher.scheduler.advanceUntilIdle()
            assertNotEquals(mainFile.path, viewModel.uiState.value.layers.first { it.id == id }.uri?.path)

            assertTrue(viewModel.reinheritOnUndoForTest(id))
            val inst = viewModel.uiState.value.layers.first { it.id == id }
            assertSame(mainBmp, inst.bitmap)
            assertEquals(mainFile.path, inst.uri?.path)
            assertEquals("MAIN", mainFile.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reinheriting on undo drops the stroke's material and its sidecar`() = runTest {
        // Codex on #681: live wetness/impasto and the on-disk sidecar survived the relink.
        val dir = kotlin.io.path.createTempDirectory("instpix").toFile()
        try {
            every { context.filesDir } returns dir
            val projectId = viewModel.uiState.value.projectId!!
            val sidecar = java.io.File(
                java.io.File(dir, "projects/$projectId"), MaterialStateCodec.fileNameForLayer("i"),
            )
            sidecar.parentFile!!.mkdirs()
            sidecar.writeText("stale")
            val mainBmp = pngWriting("MAIN")
            instanceProject(dir, mainBmp, pngWriting("INST"))
            viewModel.putLayerBaseForTest("i", mockk(relaxed = true))
            viewModel.recordInheritedBaseForTest("i", viewModel.layerBaseForTest("i")!!, mainBmp)
            viewModel.seedLiveWetnessForTest("i", 4, 4)
            assertTrue(viewModel.hasLiveWetnessForTest("i"))

            assertTrue(viewModel.reinheritOnUndoForTest("i"))
            testDispatcher.scheduler.advanceUntilIdle()
            assertFalse(viewModel.hasLiveWetnessForTest("i"))
            assertFalse(sidecar.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `inherited-base bookkeeping is pruned with the layer caches`() = runTest {
        // Codex on #681: the map was never pruned, holding bitmaps of deleted layers and old projects.
        val bmp = mockk<Bitmap>(relaxed = true)
        viewModel.dispatchForTest(EditorIntent.SetLayers(listOf(Layer(id = "live", name = "live"))))
        viewModel.recordInheritedBaseForTest("live", bmp, bmp)
        viewModel.recordInheritedBaseForTest("gone", bmp, bmp)
        viewModel.pushHistoryForTest()
        assertEquals(setOf("live"), viewModel.inheritedBaseIdsForTest())
    }

    @Test
    fun `an instance's first own text raster saves the manifest with its new file`() = runTest {
        // Codex on #681: the fork happened after the edit's own save, so the manifest kept the main's URI.
        val dir = kotlin.io.path.createTempDirectory("instpix").toFile()
        try {
            val mainFile = java.io.File(dir, "main.png").apply { writeText("MAIN") }
            val shared = Uri.parse("file://${mainFile.path}")
            val params = com.hereliesaz.graffitixr.common.model.TextLayerParams(text = "a")
            viewModel.dispatchForTest(
                EditorIntent.SetLayers(
                    listOf(
                        Layer(id = "m", name = "m", componentId = "C1", uri = shared,
                            bitmap = pngWriting("MAIN"), textParams = params,
                        ),
                        Layer(id = "i", name = "i", instanceOf = "C1", uri = shared,
                            bitmap = pngWriting("MAIN"), textParams = params,
                        ),
                    ),
                ),
            )
            testDispatcher.scheduler.advanceUntilIdle()
            // Each manifest as it was written: the transform reads live state, so apply it at call time.
            val saved = mutableListOf<GraffitiProject>()
            io.mockk.coEvery { projectRepository.updateProject(any<(GraffitiProject) -> GraffitiProject>()) } answers {
                @Suppress("UNCHECKED_CAST")
                val transform = firstArg<Any>() as (GraffitiProject) -> GraffitiProject
                saved += transform(GraffitiProject(id = "test-project"))
            }

            // The font load takes a moment, as on a device: the edit's own save lands before the raster.
            io.mockk.coEvery { GoogleFontCache.getTypeface(any(), any(), any(), any()) } coAnswers {
                kotlinx.coroutines.delay(100)
                mockk(relaxed = true)
            }
            viewModel.onTextContentChanged("i", "ab")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("MAIN", mainFile.readText())
            val forked = viewModel.uiState.value.layers.first { it.id == "i" }.uri
            assertNotEquals(mainFile.path, forked?.path)
            // A manifest written after the fork records the instance's own file, not the main's.
            assertTrue(
                saved.any { project -> project.layers.any { it.id == "i" && it.uri.toString() == forked.toString() } },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `local edits not yet sent still reach peers when a peer's op lands first`() = runTest {
        every { opEmitter.isActive } returns true
        viewModel.dispatchForTest(EditorIntent.AppendLayer(Layer(id = "c", name = "c")))
        viewModel.dispatchForTest(EditorIntent.AppendLayer(Layer(id = "d", name = "d")))
        testDispatcher.scheduler.advanceUntilIdle()
        val end = com.hereliesaz.graffitixr.common.model.Constraints(
            com.hereliesaz.graffitixr.common.model.ConstraintAnchor.END,
        )
        viewModel.dispatchForTest(EditorIntent.SetLayerConstraints("c", end))
        // Before the collector runs: a peer renames d.
        val renamed = viewModel.uiState.value.layers.first { it.id == "d" }.copy(name = "peer d")
        viewModel.applySpectatorOp(Op.LayerPropsChange("d", renamed.toLayerProps()))
        testDispatcher.scheduler.advanceUntilIdle()
        verify {
            opEmitter.emit(
                match { it is Op.LayerPropsChange && it.layerId == "c" && it.props.extended?.constraints == end },
            )
        }
    }

    @Test
    fun `the first edit after a session starts reaches peers`() = runTest {
        viewModel.dispatchForTest(EditorIntent.AppendLayer(Layer(id = "c", name = "c")))
        testDispatcher.scheduler.advanceUntilIdle()
        every { opEmitter.isActive } returns true
        viewModel.dispatchForTest(EditorIntent.RenameLayer("c", "first"))
        testDispatcher.scheduler.advanceUntilIdle()
        verify { opEmitter.emit(match { it is Op.LayerPropsChange && it.props.extended?.name == "first" }) }
        // What peers already had from the session's project load is not sent again.
        verify(exactly = 0) { opEmitter.emit(match { it is Op.LayerAdd }) }
    }

    @Test
    fun `layers added or removed with no emit of their own, and by undo, reach peers once`() = runTest {
        every { opEmitter.isActive } returns true
        viewModel.dispatchForTest(EditorIntent.AppendLayer(Layer(id = "solo", name = "solo")))
        testDispatcher.scheduler.advanceUntilIdle()
        verify(exactly = 1) { opEmitter.emit(match { it is Op.LayerAdd && it.layer.id == "solo" }) }
        viewModel.onLayerRemoved("solo")
        testDispatcher.scheduler.advanceUntilIdle()
        verify(exactly = 1) { opEmitter.emit(Op.LayerRemove("solo")) }
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        verify(exactly = 2) { opEmitter.emit(match { it is Op.LayerAdd && it.layer.id == "solo" }) }
        viewModel.dispatchForTest(EditorIntent.RemoveLayerById("solo"))
        testDispatcher.scheduler.advanceUntilIdle()
        verify(exactly = 2) { opEmitter.emit(Op.LayerRemove("solo")) }
    }

    @Test
    fun `deleting a layer tells peers and undo brings it back`() = runTest {
        viewModel.dispatchForTest(EditorIntent.AppendLayer(Layer(id = "solo", name = "solo")))
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onLayerRemoved("solo")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.layers.none { it.id == "solo" })
        verify { opEmitter.emit(Op.LayerRemove("solo")) }
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.layers.any { it.id == "solo" })
    }

    @Test
    fun `deleting a group tells peers about it and its contents, and undo brings both back`() = runTest {
        viewModel.dispatchForTest(EditorIntent.AppendLayer(Layer(id = "kid", name = "kid", parentId = "grp")))
        val group = Layer(id = "grp", name = "grp", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP)
        viewModel.dispatchForTest(EditorIntent.AppendLayer(group))
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onDeleteGroup("grp")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.layers.none { it.id == "grp" || it.id == "kid" })
        verify { opEmitter.emit(Op.LayerRemove("kid")) }
        verify { opEmitter.emit(Op.LayerRemove("grp")) }
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        val ids = viewModel.uiState.value.layers.map { it.id }
        assertTrue("grp" in ids && "kid" in ids)
    }

    @Test
    fun `a drop into a frame and a frame drawn over artwork tell peers the new parent`() = runTest {
        viewModel.dispatchForTest(
            EditorIntent.AppendLayer(
                Layer(
                    id = "under", name = "under", offset = Offset(10f, 10f),
                    shapes = listOf(VectorShape(kind = ShapeKind.RECTANGLE, width = 20f, height = 20f)),
                ),
            ),
        )
        viewModel.onAddFrame(400f, 400f)
        testDispatcher.scheduler.advanceUntilIdle()
        val frameId = viewModel.uiState.value.activeLayerId!!
        verify { opEmitter.emit(Op.LayerParent("under", frameId)) }
        viewModel.onDesignDragStart("under")
        viewModel.onDesignMove("under", Offset(1000f, 0f))
        viewModel.onDesignDragEnd("under", moved = true)
        testDispatcher.scheduler.advanceUntilIdle()
        verify { opEmitter.emit(Op.LayerParent("under", null)) }
    }

    @Test
    fun `a hidden layer under the finger is not picked`() = runTest {
        viewModel.dispatchForTest(EditorIntent.SetCanvasSize(androidx.compose.ui.unit.IntSize(400, 400)))
        viewModel.dispatchForTest(
            EditorIntent.AppendLayer(
                Layer(
                    id = "hidden", name = "hidden", isVisible = false,
                    shapes = listOf(VectorShape(kind = ShapeKind.RECTANGLE, width = 100f, height = 100f)),
                ),
            ),
        )
        assertEquals(null, viewModel.designHitAt(Offset(200f, 200f), 400f, 400f))
    }

    @Test
    fun `the Frame tool adds a frame beside the rest and keeps the tool in hand`() = runTest {
        viewModel.setActiveTool(com.hereliesaz.graffitixr.common.model.Tool.FRAME)
        viewModel.onAddFrame(390f, 844f)
        viewModel.onAddFrame(100f, 100f)
        testDispatcher.scheduler.advanceUntilIdle()
        val frames = viewModel.uiState.value.layers
            .filter { it.type == com.hereliesaz.graffitixr.common.model.LayerType.FRAME }
        assertEquals(listOf("Frame 1", "Frame 2"), frames.map { it.name })
        // The second sits 80 to the right of the first, tops aligned.
        assertEquals(195f + 80f + 50f, frames[1].offset.x, 0.01f)
        assertEquals(-422f + 50f, frames[1].offset.y, 0.01f)
        assertEquals(frames[1].id, viewModel.uiState.value.activeLayerId)
        assertEquals(com.hereliesaz.graffitixr.common.model.Tool.FRAME, viewModel.uiState.value.activeTool)
    }

    @Test
    fun `the properties set a frame's box, fill and clip, each one undo step`() = runTest {
        viewModel.onAddFrame(200f, 100f)
        testDispatcher.scheduler.advanceUntilIdle()
        val id = viewModel.uiState.value.activeLayerId!!
        val undoBefore = viewModel.uiState.value.undoCount
        viewModel.onSetLayerBox(10f, 20f, 300f, 150f)
        viewModel.onSetFrameFill(null)
        viewModel.onSetFrameClip(false)
        testDispatcher.scheduler.advanceUntilIdle()
        val f = layer(id)
        assertEquals(Offset(160f, 95f), f.offset)
        assertEquals(300f, f.layoutWidth, 0.01f)
        assertEquals(null, f.frameFillArgb)
        assertEquals(false, f.clipsContent)
        assertEquals(undoBefore + 3, viewModel.uiState.value.undoCount)
        viewModel.onUndoClicked()
        viewModel.onUndoClicked()
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(200f, layer(id).layoutWidth, 0.01f)
        assertEquals(true, layer(id).clipsContent)
    }

    @Test
    fun `a layer dragged into a frame with the Frame tool is adopted in one undo step`() = runTest {
        viewModel.onAddFrame(400f, 400f) // centred on the origin
        testDispatcher.scheduler.advanceUntilIdle()
        val frameId = viewModel.uiState.value.activeLayerId!!
        viewModel.dispatchForTest(
            EditorIntent.AppendLayer(
                Layer(
                    id = "s", name = "s", offset = Offset(600f, 0f),
                    shapes = listOf(VectorShape(kind = ShapeKind.RECTANGLE, width = 20f, height = 20f)),
                ),
            ),
        )
        val undoBefore = viewModel.uiState.value.undoCount
        viewModel.onDesignDragStart("s")
        viewModel.onDesignMove("s", Offset(-600f, 0f))
        viewModel.onDesignDragEnd("s", moved = true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(frameId, layer("s").parentId)
        assertEquals(undoBefore + 1, viewModel.uiState.value.undoCount)
    }

    @Test
    fun `a numeric frame resize and its constrained children undo as one step`() = runTest {
        seedFrame()
        val undoBefore = viewModel.uiState.value.undoCount

        viewModel.onSetFrameSize(800f, 200f)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(800f, layer("F").layoutWidth, 0.01f)
        assertEquals(330f, layer("end").offset.x, 0.01f)
        assertEquals(760f, layer("fill").shapes.first().width, 0.01f)
        assertEquals(undoBefore + 1, viewModel.uiState.value.undoCount)

        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(400f, layer("F").layoutWidth, 0.01f)
        assertEquals(130f, layer("end").offset.x, 0.01f)
        assertEquals(360f, layer("fill").shapes.first().width, 0.01f)
    }

    @Test
    fun `dragging a frame's resize handle resizes its box, not its scale, in one undo step`() = runTest {
        seedFrame()
        val undoBefore = viewModel.uiState.value.undoCount

        viewModel.onGestureStart()
        viewModel.onTransformGesture(Offset.Zero, 1.5f, 0f)
        viewModel.onTransformGesture(Offset.Zero, 4f / 3f, 0f)
        viewModel.onGestureEnd()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1f, layer("F").scale, 0.0001f)
        assertEquals(800f, layer("F").layoutWidth, 0.5f)
        assertEquals(400f, layer("F").layoutHeight, 0.5f)
        assertEquals(330f, layer("end").offset.x, 0.5f)
        assertEquals(760f, layer("fill").shapes.first().width, 0.5f)
        assertEquals(undoBefore + 1, viewModel.uiState.value.undoCount)

        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(400f, layer("F").layoutWidth, 0.01f)
        assertEquals(130f, layer("end").offset.x, 0.01f)
        assertEquals(360f, layer("fill").shapes.first().width, 0.01f)
    }

    @Test
    fun `a frame resize and its undo send peers the children's changes, not just the frame's`() = runTest {
        seedFrame()
        viewModel.onSetFrameSize(800f, 200f)
        testDispatcher.scheduler.advanceUntilIdle()

        verify {
            opEmitter.emit(match { it is Op.LayerGeometry && it.layerId == "F" && it.layoutWidth == 800f })
        }
        verify {
            opEmitter.emit(match { it is Op.LayerGeometry && it.layerId == "fill" && it.shapes.first().width == 760f })
        }
        verify {
            opEmitter.emit(match { it is Op.LayerTransform && it.layerId == "end" && it.matrix[1] == 330f })
        }
        // Undo's resync must carry the restored geometry too, or a peer keeps the resized frame.
        viewModel.onUndoClicked(); testDispatcher.scheduler.advanceUntilIdle()
        verify { opEmitter.emit(match { it is Op.LayerGeometry && it.layerId == "F" && it.layoutWidth == 400f }) }
        verify {
            opEmitter.emit(match { it is Op.LayerGeometry && it.layerId == "fill" && it.shapes.first().width == 360f })
        }
    }

    @Test
    fun `hug contents goes through the same resize path`() = runTest {
        val frame = Layer(
            id = "F", name = "F", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP,
            autoLayout = com.hereliesaz.graffitixr.common.model.AutoLayout(
                direction = com.hereliesaz.graffitixr.common.model.LayoutDirection.HORIZONTAL,
            ),
        )
        val a = Layer(
            id = "a", name = "a", parentId = "F",
            shapes = listOf(VectorShape(kind = ShapeKind.RECTANGLE, width = 100f, height = 50f)),
        )
        viewModel.dispatchForTest(EditorIntent.SetLayers(listOf(frame, a)))
        viewModel.onLayerActivated("F")
        val undoBefore = viewModel.uiState.value.undoCount

        viewModel.onHugContents()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(100f, layer("F").layoutWidth, 0.01f)
        assertEquals(50f, layer("F").layoutHeight, 0.01f)
        assertEquals(undoBefore + 1, viewModel.uiState.value.undoCount)
    }

    @Test
    fun `undo and redo on empty stacks do not crash`() {
        // Fresh ViewModel has empty undo and redo stacks; neither call should throw.
        viewModel.onUndoClicked()
        viewModel.onRedoClicked()

        val state = viewModel.uiState.value
        assertEquals(0, state.undoCount)
        assertEquals(0, state.redoCount)
        assertTrue(state.layers.isEmpty())
    }

    @Test
    fun `onLayerRemoved with unknown id does not modify state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        val layerCountBefore = viewModel.uiState.value.layers.size
        assertEquals(1, layerCountBefore)

        // Removing a non-existent ID should leave the layer list unchanged.
        viewModel.onLayerRemoved("non-existent-id")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(layerCountBefore, viewModel.uiState.value.layers.size)
    }

    // ==================== Layer-state characterization (refactor safety net) ====================
    // These pin the observable behavior of the layer-management operations a future LayerManager
    // extraction must preserve. They seed state via setLayers() and avoid OpenCV, so they run in
    // plain JVM. If an extraction changes behavior, these go red. advanceUntilIdle() runs FIRST so
    // the init currentProject-collect (which seeds layers from the empty test project) settles
    // before setLayers() seeds the real fixture.

    private fun lyr(id: String, name: String = id) = Layer(id = id, name = name)

    /** A 3D layer on its own: a model layer (the per-layer 3D flag is retired). */
    private fun modelLyr(id: String) =
        lyr(id).copy(model = com.hereliesaz.graffitixr.common.scene.ModelLayerContent("model_$id.obj"))

    // ── One 3D space (spec/3d-space.md) ──

    @Test
    fun `a co-op transform carries depth, and an older peer's means depth 0`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a").copy(positionZ = 12f)))
        val moved = lyr("a").copy(scale = 2f, rotationY = 30f, positionZ = -80f)
        val wire = com.hereliesaz.graffitixr.common.coop.LayerTransformCodec.encode(moved)
        viewModel.applySpectatorOp(Op.LayerTransform("a", wire))
        testDispatcher.scheduler.advanceUntilIdle()
        val got = viewModel.uiState.value.layers.single()
        assertEquals(-80f, got.positionZ)
        assertEquals(30f, got.rotationY)
        // A six-slot message from a build without depth.
        viewModel.applySpectatorOp(Op.LayerTransform("a", listOf(1f, 0f, 0f, 0f, 0f, 0f)))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0f, viewModel.uiState.value.layers.single().positionZ)
    }

    @Test
    fun `an empty layer adopts the view plane on its first stroke, only under a moved camera`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a")))
        viewModel.onLayerActivated("a")
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        // Default view: nothing changes, the 2D fast path stays.
        assertFalse(viewModel.adoptViewPlaneForStroke())
        assertFalse(viewModel.uiState.value.layers.single().isPlacedIn3D)
        viewModel.onSceneOrbit((Math.PI / 2).toFloat(), 0f)
        assertTrue(viewModel.adoptViewPlaneForStroke())
        val adopted = viewModel.uiState.value.layers.single()
        assertEquals(90f, kotlin.math.abs(adopted.rotationY), 0.5f)
        // A cancelled first stroke leaves no paint, so the plane goes back.
        viewModel.onStrokeCancel()
        assertFalse(viewModel.uiState.value.layers.single().isPlacedIn3D)
        // A layer placed in 3D by hand, or one with a source image, keeps its plane.
        viewModel.setLayers(listOf(lyr("a").copy(positionZ = 30f)))
        assertFalse(viewModel.adoptViewPlaneForStroke())
        assertEquals(30f, viewModel.uiState.value.layers.single().positionZ)
    }

    // ── The orbit pivot (spec/3d-space.md, "The orbit point") ──

    private val pivotW = 1000f
    private val pivotH = 1600f

    private fun pivotSetup(vararg layers: Layer) {
        viewModel.setLayers(layers.toList())
        viewModel.onLayerActivated(layers.first().id)
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun pivotNow() =
        com.hereliesaz.graffitixr.common.scene.OrbitPivot.of(viewModel.uiState.value.layers, pivotW, pivotH)

    private fun surfaceGroup(id: String, vararg pts: Float) = Layer(
        id = id, name = id, type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP,
        surface = com.hereliesaz.graffitixr.common.scene.SurfaceProfile(pts.toList(), 0f, 0f, 1f),
    )

    @Test
    fun `the orbit pivot follows every surface and is recomputed as they come and go`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val floor = surfaceGroup("f", 200f, 1000f, 0f, 800f, 1000f, 0f)
        val wall = surfaceGroup("w", 500f, 200f, 0f, 500f, 1000f, 0f)
        val paintF = Layer(id = "pf", name = "pf", parentId = "f")
        val paintW = Layer(id = "pw", name = "pw", parentId = "w")
        pivotSetup(floor, paintF)
        assertEquals(300f, viewModel.orbitPivot()!![2], 0.5f)
        viewModel.setLayers(listOf(floor, paintF, wall, paintW))
        assertEquals(600f, viewModel.orbitPivot()!![1], 0.5f)
        assertEquals(400f, viewModel.orbitPivot()!![2], 0.5f)
        viewModel.setLayers(listOf(floor, paintF))
        assertEquals(1000f, viewModel.orbitPivot()!![1], 0.5f)
    }

    @Test
    fun `a placed rotation point wins until reset to automatic`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val floor = surfaceGroup("f", 200f, 1000f, 0f, 800f, 1000f, 0f)
        pivotSetup(floor, Layer(id = "pf", name = "pf", parentId = "f"))
        viewModel.onPivotPicked(androidx.compose.ui.geometry.Offset(10f, 10f))
        assertEquals(null, viewModel.uiState.value.manualPivot)
        viewModel.onArmPivotPick()
        assertTrue(viewModel.uiState.value.pivotPickArmed)
        viewModel.onPivotPicked(androidx.compose.ui.geometry.Offset(500f, 1000f))
        val placed = viewModel.uiState.value.manualPivot!!
        assertEquals(0f, placed[2], 1f)
        assertEquals(false, viewModel.uiState.value.pivotPickArmed)
        assertEquals(1000f, placed[1], 1f)
        assertEquals(placed[0], viewModel.orbitPivot()!![0], 0.01f)
        viewModel.onResetPivot()
        assertEquals(null, viewModel.uiState.value.manualPivot)
        assertEquals(300f, viewModel.orbitPivot()!![2], 0.5f)
    }

    private fun boxPoint(id: String, u: Float, v: Float, z: Float): FloatArray {
        val layers = viewModel.uiState.value.layers
        return SceneMath.chainModel(com.hereliesaz.graffitixr.common.scene.planeChain(layers, id), pivotW, pivotH)
            .apply(u, v, z)
    }

    private fun dist(a: FloatArray, b: FloatArray) =
        kotlin.math.sqrt((0..2).sumOf { ((a[it] - b[it]).toDouble().let { d -> d * d }) }).toFloat()

    @Test
    fun `a Z turn of a lone layer 1 leaves its centre where it is and the pivot behind it`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        pivotSetup(lyr("a"))
        val centre = boxPoint("a", 500f, 800f, 0f)
        val pivot = pivotNow()
        assertEquals(800f, pivot[2], 0.01f)
        sliderDrag(20f, 55f)
        assertEquals(55f, viewModel.uiState.value.layers.single().rotationZ, 0.01f)
        val after = boxPoint("a", 500f, 800f, 0f)
        for (i in 0..2) assertEquals(centre[i], after[i], 0.05f)
        val pivotAfter = pivotNow()
        for (i in 0..2) assertEquals(pivot[i], pivotAfter[i], 0.05f)
    }

    @Test
    fun `an X turn swings layer 1 about the pivot behind it at a constant distance`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        pivotSetup(lyr("a").copy(rotationY = 1f)) // placed in 3D, so the slider has an axis
        setAxis(RotationAxis.X)
        val pivot = pivotNow()
        val corners = listOf(0f to 0f, 1000f to 1600f).map { (u, v) -> boxPoint("a", u, v, 0f) }
        val before = corners.map { dist(it, pivot) }
        sliderDrag(30f, 75f)
        val layer = viewModel.uiState.value.layers.single()
        assertEquals(75f, layer.rotationX, 0.01f)
        // It swung: its centre left the picture plane, round a point 800 behind it.
        assertTrue("centre left the plane: ${layer.positionZ}", kotlin.math.abs(layer.positionZ) > 100f)
        val after = listOf(0f to 0f, 1000f to 1600f).map { (u, v) -> dist(boxPoint("a", u, v, 0f), pivot) }
        for (i in before.indices) assertEquals(before[i], after[i], 0.5f)
        // And the point itself did not move.
        val still = boxPoint("a", 500f, 800f, 800f)
        for (i in 0..2) assertEquals(pivot[i], still[i], 0.5f)
        val again = pivotNow()
        for (i in 0..2) assertEquals(pivot[i], again[i], 0.5f)
    }

    @Test
    fun `the slider, a two-finger turn and the view orbit all revolve about the same pivot`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        pivotSetup(lyr("a").copy(rotationY = 1f))
        val pivot = pivotNow()
        // Slider: the layer's box point at the pivot stays put.
        setAxis(RotationAxis.Y)
        sliderDrag(40f)
        val viaSlider = boxPoint("a", 500f, 800f, 800f)
        // Two fingers on the view (axis Y) and the orbit call: the pivot keeps its place in the camera.
        fun cameraSpace() = SceneMath.frame(viewModel.uiState.value.sceneCamera, pivotW, pivotH).toCamera(pivot)
        val seen0 = cameraSpace()
        twoFinger(rotation = 25f, steps = 3)
        val seen1 = cameraSpace()
        viewModel.onSceneOrbit(0.4f, -0.2f)
        val seen2 = cameraSpace()
        for (i in 0..2) {
            assertEquals("slider $i", pivot[i], viaSlider[i], 0.5f)
            assertEquals("two-finger $i", seen0[i], seen1[i], 0.5f)
            assertEquals("orbit $i", seen0[i], seen2[i], 0.5f)
        }
        assertTrue(viewModel.uiState.value.sceneCamera.yaw != 0f)
    }

    @Test
    fun `a layer's Move-tool twist also revolves about the pivot`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        pivotSetup(lyr("a").copy(rotationY = 1f))
        setAxis(RotationAxis.X)
        val pivot = pivotNow()
        viewModel.onTransformClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        twoFinger(rotation = 20f, steps = 3)
        val still = boxPoint("a", 500f, 800f, 800f)
        assertTrue(kotlin.math.abs(viewModel.uiState.value.layers.single().rotationX) > 1f)
        for (i in 0..2) assertEquals(pivot[i], still[i], 0.5f)
    }

    @Test
    fun `a view plane adopted under an orbited camera passes through the pivot`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        pivotSetup(lyr("a"))
        // Layer 1 alone: the pivot is 800 (half its longer side) behind its centre.
        val pivot = floatArrayOf(500f, 800f, 800f)
        viewModel.onSceneOrbit((Math.PI / 2).toFloat(), 0f)
        assertTrue(viewModel.adoptViewPlaneForStroke())
        val centre = boxPoint("a", 500f, 800f, 0f)
        for (i in 0..2) assertEquals(pivot[i], centre[i], 0.5f)
    }

    @Test
    fun `a twist and a pinch in one Move-tool step measure the pinch from the depth the twist left`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val focal = SceneMath.focalLength(pivotH)
        // A lone layer tilted 90 degrees about X, its centre at camera depth 250 (default camera).
        pivotSetup(lyr("a").copy(rotationX = 90f, positionZ = 250f - focal))
        setAxis(RotationAxis.X)
        viewModel.onTransformClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        fun depth() = SceneMath.frame(viewModel.uiState.value.sceneCamera, pivotW, pivotH)
            .toCamera(boxPoint("a", 500f, 800f, 0f))[2]
        assertEquals(250f, depth(), 0.5f)
        twoFinger(zoom = 1.3f, rotation = -10f)
        // The twist alone leaves the centre near depth 111; 1.3x would land at 85, below the minimum
        // (5% of the focal length), so the clamp must hold it there.
        assertTrue("depth ${depth()} below the minimum", depth() >= focal * 0.05f - 0.5f)
    }

    @Test
    fun `turning a layer with the selection handle one way and back returns its centre`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        pivotSetup(lyr("a"), lyr("b").copy(positionZ = 400f))
        viewModel.onLayerActivated("b")
        setAxis(RotationAxis.X)
        val centre = boxPoint("b", 500f, 800f, 0f)
        viewModel.onGestureStart()
        viewModel.onRotateLayerHandle(30f)
        viewModel.onRotateLayerHandle(-30f)
        viewModel.onGestureEnd()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0f, viewModel.uiState.value.layers.first { it.id == "b" }.rotationX, 0.01f)
        val after = boxPoint("b", 500f, 800f, 0f)
        for (i in 0..2) assertEquals("axis $i", centre[i], after[i], 0.5f)
    }

    private fun twoFinger(pan: Offset = Offset.Zero, zoom: Float = 1f, rotation: Float = 0f, steps: Int = 1) {
        viewModel.onTwoFingerStart()
        repeat(steps) { viewModel.onTwoFingerGesture(pan, zoom, Offset(500f, 800f), rotation) }
        viewModel.onTwoFingerEnd()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `double-tap's axis cycle steps X Y Z and shows the chip`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val axes = (0 until 3).map {
            viewModel.onCycleRotationAxis()
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(viewModel.uiState.value.showRotationAxisFeedback)
            viewModel.uiState.value.activeRotationAxis
        }
        assertEquals(RotationAxis.entries.toSet(), axes.toSet())
        viewModel.onFeedbackShown()
        assertFalse(viewModel.uiState.value.showRotationAxisFeedback)
    }

    private fun setAxis(axis: RotationAxis) {
        while (viewModel.uiState.value.activeRotationAxis != axis) viewModel.onCycleRotationAxis()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `a twist on Z rotates the canvas and leaves the camera at identity`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        setAxis(RotationAxis.Z)
        twoFinger(rotation = 3f, steps = 5)
        assertEquals(15f, viewModel.uiState.value.viewportRotation, 0.01f)
        assertTrue(viewModel.uiState.value.sceneCamera.isDefault)
        assertFalse(viewModel.uiState.value.layers.single().isPlacedIn3D)
    }

    @Test
    fun `a twist on X or Y orbits the camera progressively, and Fit resets it`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(modelLyr("a")))
        viewModel.onLayerActivated("a")
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        setAxis(RotationAxis.Y)
        viewModel.onTwoFingerStart()
        val yaws = (0 until 6).map {
            viewModel.onTwoFingerGesture(Offset.Zero, 1f, Offset(500f, 800f), 5f)
            viewModel.uiState.value.sceneCamera.yaw
        }
        viewModel.onTwoFingerEnd()
        assertTrue("yaw should grow on every step: $yaws", yaws.zipWithNext().all { (a, b) -> b > a })
        assertEquals(Math.toRadians(30.0).toFloat(), yaws.last(), 1e-4f)
        assertEquals(0f, viewModel.uiState.value.viewportRotation)
        val shortYaw = yaws[1]
        assertTrue(yaws.last() > shortYaw * 2)
        setAxis(RotationAxis.X)
        twoFinger(rotation = 10f)
        assertEquals(Math.toRadians(10.0).toFloat(), viewModel.uiState.value.sceneCamera.pitch, 1e-4f)
        // Neither is history.
        assertEquals(0, viewModel.uiState.value.undoCount)
        viewModel.resetViewport()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.sceneCamera.isDefault)
    }

    @Test
    fun `with the Move tool two fingers act on a 3D layer, one undo step per gesture`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(modelLyr("a")))
        viewModel.onLayerActivated("a")
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onTransformClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(EditorPanel.TRANSFORM, viewModel.uiState.value.activePanel)
        setAxis(RotationAxis.Y)
        val undoBefore = viewModel.uiState.value.undoCount
        // Drag slides it, the twist turns it about Y, and nothing touches the view.
        twoFinger(pan = Offset(10f, 0f), rotation = 2f, steps = 4)
        val moved = viewModel.uiState.value.layers.single()
        // The twist revolves about the orbit pivot (800 behind the layer), so the layer's centre swings
        // as well as slides: it is the pivot's own point of the layer that moved by the drag alone.
        val pivotPoint = SceneMath.model(moved.planePose, 1000f, 1600f).apply(500f, 800f, 800f)
        assertEquals(540f, pivotPoint[0], 0.5f)
        val swungZ = moved.positionZ
        assertEquals(8f, moved.rotationY, 0.01f)
        assertEquals(undoBefore + 1, viewModel.uiState.value.undoCount)
        assertEquals(0f, viewModel.uiState.value.viewportRotation)
        assertTrue(viewModel.uiState.value.sceneCamera.isDefault)
        // A pinch-out brings it nearer.
        twoFinger(zoom = 1.2f)
        assertTrue(viewModel.uiState.value.layers.single().positionZ < 0f)
        // The Move tool stays in hand through its own gestures, and the view never moved.
        assertEquals(EditorPanel.TRANSFORM, viewModel.uiState.value.activePanel)
        assertEquals(1f, viewModel.uiState.value.viewportZoom)
        assertEquals(undoBefore + 2, viewModel.uiState.value.undoCount)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        // Back to where the twist left it (its swing about the pivot gave it a little depth).
        assertEquals(swungZ, viewModel.uiState.value.layers.single().positionZ, 0.01f)
        assertEquals(8f, viewModel.uiState.value.layers.single().rotationY, 0.01f)
    }

    @Test
    fun `Draw surface turns the next stroke into the group's profile, and Flatten clears it`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val group = lyr("g").copy(type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP)
        viewModel.setLayers(listOf(group, lyr("c").copy(parentId = "g")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onArmSurfaceDraw("g")
        assertEquals("g", viewModel.uiState.value.surfaceDrawGroupId)
        viewModel.onSurfaceProfileDrawn((0..30).map { Offset(200f + it * 20f, 1000f - (it - 15f) * (it - 15f)) })
        testDispatcher.scheduler.advanceUntilIdle()
        val profile = viewModel.uiState.value.layers.first { it.id == "g" }.surface
        assertTrue(profile != null && !profile.isFlat)
        assertEquals(null, viewModel.uiState.value.surfaceDrawGroupId)
        viewModel.onFlattenSurface("g")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.layers.first { it.id == "g" }.surface)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(profile, viewModel.uiState.value.layers.first { it.id == "g" }.surface)
    }

    @Test
    fun `outline assist straightens a wobbly surface stroke and one undo gives back the raw stroke`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val wobbly = (0..40).map { Offset(200f + it * 15f, 1000f + (if (it % 2 == 0) 3f else -3f)) }
        fun draw(assist: Boolean): com.hereliesaz.graffitixr.common.scene.SurfaceProfile? {
            viewModel.setLayers(
                listOf(
                    lyr("g").copy(type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP),
                    lyr("c").copy(parentId = "g"),
                ),
            )
            viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
            viewModel.dispatchForTest(EditorIntent.SetSurfaceOutlineAssist(assist))
            viewModel.onArmSurfaceDraw("g")
            viewModel.onSurfaceProfileDrawn(wobbly)
            testDispatcher.scheduler.advanceUntilIdle()
            return viewModel.uiState.value.layers.first { it.id == "g" }.surface
        }
        val raw = draw(assist = false)
        assertTrue(raw != null && raw.count > 2)
        val assisted = draw(assist = true)
        assertEquals(2, assisted?.count)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(raw, viewModel.uiState.value.layers.first { it.id == "g" }.surface)
    }

    @Test
    fun `the surface grid toggle shows every visible group's grid`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val floor = com.hereliesaz.graffitixr.common.scene.SurfaceMath.capture(
            listOf(200f to 1000f, 800f to 1000f), SceneCamera(), 1000f, 1600f,
            listOf(com.hereliesaz.graffitixr.common.scene.PlanePose()),
        )
        val group = lyr("g").copy(type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP, surface = floor)
        viewModel.setLayers(listOf(group, lyr("c").copy(parentId = "g"), lyr("x")))
        viewModel.onLayerActivated("c")
        fun grid() = viewModel.uiState.value.let {
            com.hereliesaz.graffitixr.common.scene.SurfaceMath
                .gridGroupIds(it.layers, it.surfaceGridVisible).firstOrNull()
        }
        // On by default: the wire mesh shows without being asked for.
        assertEquals("g", grid())
        viewModel.onToggleSurfaceGrid()
        assertEquals(null, grid())
        viewModel.onToggleSurfaceGrid()
        assertEquals("g", grid())
        // The mesh no longer follows the active layer: it stays on while a flat layer is active.
        viewModel.onLayerActivated("x")
        assertEquals("g", grid())
        viewModel.onLayerActivated("c")
        viewModel.onToggleSurfaceGrid()
        assertEquals(null, grid())
    }

    @Test
    fun `on a group surface the Move tool and the rotation triangle move the group, not the layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val floor = com.hereliesaz.graffitixr.common.scene.SurfaceMath.capture(
            listOf(200f to 1000f, 800f to 1000f), SceneCamera(), 1000f, 1600f,
            listOf(com.hereliesaz.graffitixr.common.scene.PlanePose()),
        )
        val group = lyr("g").copy(type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP, surface = floor)
        viewModel.setLayers(listOf(group, lyr("c").copy(parentId = "g"), lyr("d").copy(parentId = "g")))
        viewModel.onLayerActivated("c")
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onTransformClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        twoFinger(pan = Offset(10f, 0f), steps = 3)
        val after = viewModel.uiState.value.layers
        assertTrue(after.first { it.id == "g" }.offset.x > 1f)
        assertEquals(Offset.Zero, after.first { it.id == "c" }.offset)
        assertEquals(Offset.Zero, after.first { it.id == "d" }.offset)
        // The slider is gone on a surface; the triangle turns the group by 30 about Z.
        assertNull(viewModel.rotationSliderValue())
        viewModel.onRotationGizmoTurn(RotationAxis.Z, 30f)
        viewModel.onRotationGizmoEnd()
        val turned = viewModel.uiState.value.layers
        assertEquals(0f, turned.first { it.id == "c" }.let { it.rotationX + it.rotationY + it.rotationZ }, 0f)
        assertTrue(turned.first { it.id == "g" }.let { it.rotationX + it.rotationY + it.rotationZ } != 0f)
    }

    @Test
    fun `a still second finger under the Move tool leaves undo and redo alone`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(modelLyr("a")))
        viewModel.onLayerActivated("a")
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onTransformClicked()
        twoFinger(pan = Offset(10f, 0f))
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        val undo = viewModel.uiState.value.undoCount
        val redo = viewModel.uiState.value.redoCount
        assertEquals(1, redo)
        // Two fingers down and up without moving: how the Undo / Redo taps begin.
        viewModel.onTwoFingerStart()
        viewModel.onTwoFingerGesture(Offset.Zero, 1f, Offset(500f, 800f), 0f)
        viewModel.onTwoFingerEnd()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(undo, viewModel.uiState.value.undoCount)
        assertEquals(redo, viewModel.uiState.value.redoCount)
        assertFalse(viewModel.uiState.value.gestureInProgress)
    }

    @Test
    fun `repeated pinch-outs stop short of the near plane`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(modelLyr("a")))
        viewModel.onLayerActivated("a")
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onTransformClicked()
        val frame = SceneMath.frame(SceneCamera(), 1000f, 1600f)
        repeat(30) { twoFinger(zoom = 2f) }
        val z = viewModel.uiState.value.layers.single().positionZ
        val depth = frame.toCamera(floatArrayOf(500f, 800f, z))[2]
        assertTrue("depth $depth must stay in front of the near limit", depth >= frame.focal * 0.05f - 1e-2f)
        assertTrue("it did come nearer", depth < frame.focal * 0.5f)
    }

    @Test
    fun `a group's new surface is sent to co-op peers`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("g").copy(type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP)))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onArmSurfaceDraw("g")
        viewModel.onSurfaceProfileDrawn(listOf(Offset(100f, 900f), Offset(700f, 900f)))
        testDispatcher.scheduler.advanceUntilIdle()
        verify {
            opEmitter.emit(
                match { it is Op.LayerPropsChange && it.layerId == "g" && it.props.extended?.surface != null },
            )
        }
    }

    @Test
    fun `a Move-tool pan on a child of a scaled, rotated group follows the fingers`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val group = lyr("g").copy(scale = 2f, rotationZ = 90f)
        val child = modelLyr("c").copy(parentId = "g")
        viewModel.setLayers(listOf(group, child))
        viewModel.onLayerActivated("c")
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onTransformClicked()
        fun centre(): FloatArray = SceneMath.chainModel(
            com.hereliesaz.graffitixr.common.scene.planeChain(viewModel.uiState.value.layers, "c"), 1000f, 1600f,
        ).apply(500f, 800f, 0f)
        val before = centre()
        twoFinger(pan = Offset(10f, 0f))
        val after = centre()
        assertEquals(10f, after[0] - before[0], 0.05f)
        assertEquals(0f, after[1] - before[1], 0.05f)
    }

    @Test
    fun `Reset plane flattens a layer placed in 3D as one undo step`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a").copy(positionZ = 30f, rotationX = 10f)))
        viewModel.onLayerActivated("a")
        viewModel.resetActivePlane()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.layers.single().isPlacedIn3D)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(30f, viewModel.uiState.value.layers.single().positionZ)
    }

    @Test
    fun `a 3D strokes layer takes curves and erasures as undoable steps`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.addSpatialLayer()
        testDispatcher.scheduler.advanceUntilIdle()
        val layer = viewModel.uiState.value.layers.single()
        assertTrue(layer.isSpatial)
        assertEquals(com.hereliesaz.graffitixr.common.model.Tool.BRUSH, viewModel.uiState.value.activeTool)
        val stroke = com.hereliesaz.graffitixr.common.mesh.SpatialStroke(
            "s1", listOf(com.hereliesaz.graffitixr.common.mesh.SpatialPoint(0f, 0f, 0f)), -1, 0.1f,
        )
        viewModel.onSpatialStroke(layer.id, stroke)
        assertEquals(listOf(stroke), viewModel.uiState.value.layers.single().spatial!!.strokes)
        viewModel.onSpatialErase(layer.id, "s1")
        assertTrue(viewModel.uiState.value.layers.single().spatial!!.strokes.isEmpty())
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(stroke), viewModel.uiState.value.layers.single().spatial!!.strokes)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.layers.single().spatial!!.strokes.isEmpty())
    }

    @Test
    fun `characterize onLayerReordered reorders layers by the given id order`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a"), lyr("b"), lyr("c")))
        viewModel.onLayerReordered(listOf("c", "a", "b"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("c", "a", "b"), viewModel.uiState.value.layers.map { it.id })
    }

    @Test
    fun `characterize onLayerRenamed renames only the target layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a", "Alpha"), lyr("b", "Beta")))
        viewModel.onLayerRenamed("a", "Renamed")
        testDispatcher.scheduler.advanceUntilIdle()
        val layers = viewModel.uiState.value.layers
        assertEquals("Renamed", layers.first { it.id == "a" }.name)
        assertEquals("Beta", layers.first { it.id == "b" }.name)
    }

    @Test
    fun `characterize onToggleVisibility flips only the target layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a"), lyr("b")))
        viewModel.onToggleVisibility("a")
        testDispatcher.scheduler.advanceUntilIdle()
        val layers = viewModel.uiState.value.layers
        assertFalse(layers.first { it.id == "a" }.isVisible)
        assertTrue(layers.first { it.id == "b" }.isVisible)
    }

    @Test
    fun `onOpacityChanged in Design updates only the active layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a"), lyr("b")))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onOpacityChanged(0.25f)
        testDispatcher.scheduler.advanceUntilIdle()
        val layers = viewModel.uiState.value.layers
        assertEquals(0.25f, layers.first { it.id == "a" }.opacity)
        assertEquals(1.0f, layers.first { it.id == "b" }.opacity)
    }

    @Test
    fun `characterize onCycleBlendMode changes the active layer blend mode`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a")))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        val before = viewModel.uiState.value.layers.first().blendMode
        viewModel.onCycleBlendMode()
        testDispatcher.scheduler.advanceUntilIdle()
        val after = viewModel.uiState.value.layers.first().blendMode
        assertTrue("blend mode should advance", before != after)
    }

    @Test
    fun `characterize onLayerDuplicated appends a copy and activates it`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a", "Alpha")))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onLayerDuplicated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        val layers = viewModel.uiState.value.layers
        assertEquals(2, layers.size)
        val dup = layers.first { it.name == "Alpha Copy" }
        assertEquals(dup.id, viewModel.uiState.value.activeLayerId)
    }

    @Test
    fun `double-tap never changes the active layer, even over overlapping layers`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val l1 = VectorShape(kind = ShapeKind.RECTANGLE, width = 400f, height = 400f)
        viewModel.setLayers(
            listOf(
                Layer(id = "layer1", name = "1", shapes = listOf(l1)),
                Layer(id = "layer2", name = "2", shapes = listOf(l1)),
            ),
        )
        viewModel.onLayerActivated("layer2")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onCanvasDoubleTap()
        viewModel.onCanvasDoubleTap()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("layer2", viewModel.uiState.value.activeLayerId)
    }

    @Test
    fun `double-tap cycles the axis on a 3D layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a").copy(rotationX = 10f)))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        val before = viewModel.uiState.value.activeRotationAxis
        viewModel.onCanvasDoubleTap()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotEquals(before, viewModel.uiState.value.activeRotationAxis)
    }

    private fun group3d() = lyr("g").copy(
        type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP,
        surface = com.hereliesaz.graffitixr.common.scene.SurfaceProfile(
            listOf(0f, 0f, 0f, 500f, 0f, 0f, 1000f, 0f, 400f),
        ),
    )

    @Test
    fun `double-tap resets the view and does not step the axis in a project with a 3D group`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(group3d(), lyr("c").copy(parentId = "g", rotationX = 10f)))
        viewModel.onLayerActivated("c")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onViewportPanZoom(Offset(30f, 20f), 2f, Offset(100f, 100f))
        viewModel.onSceneOrbit(0.4f, 0.2f)
        val axis = viewModel.uiState.value.activeRotationAxis
        val undo = viewModel.uiState.value.undoCount
        viewModel.onCanvasDoubleTap()
        testDispatcher.scheduler.advanceUntilIdle()
        val st = viewModel.uiState.value
        assertTrue(st.sceneCamera.isDefault)
        assertEquals(1f, st.viewportZoom, 0f)
        assertEquals(axis, st.activeRotationAxis)
        assertEquals(undo, st.undoCount)
    }

    @Test
    fun `a brush double-tap with no 3D group paints both taps and changes no view`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a")))
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onViewportPanZoom(Offset(30f, 20f), 2f, Offset(100f, 100f))
        val before = viewModel.uiState.value
        assertFalse(viewModel.onBrushTap(Offset(50f, 50f), 1000L))
        assertFalse(viewModel.onBrushTap(Offset(52f, 50f), 1150L))
        val after = viewModel.uiState.value
        assertEquals(before.viewportOffset, after.viewportOffset)
        assertEquals(before.viewportZoom, after.viewportZoom, 0f)
        assertEquals(before.sceneCamera, after.sceneCamera)
    }

    private fun twoFinger(pan: Offset, zoom: Float = 1f, rotation: Float = 0f) {
        viewModel.onTwoFingerStart()
        viewModel.onTwoFingerGesture(pan, zoom, Offset(500f, 800f), rotation)
        viewModel.onTwoFingerEnd()
    }

    @Test
    fun `two-finger drag orbits and does not pan in a project with a 3D group, pivot fixed on screen`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(group3d(), lyr("c").copy(parentId = "g")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        testDispatcher.scheduler.advanceUntilIdle()
        val pivot = viewModel.orbitPivot()!!
        val before = viewModel.uiState.value
        val px = com.hereliesaz.graffitixr.common.scene.SceneMath.frame(before.sceneCamera, 1000f, 1600f)
            .project(pivot)!!
        twoFinger(Offset(100f, 50f))
        val after = viewModel.uiState.value
        assertTrue("yaw turned", after.sceneCamera.yaw != 0f)
        assertTrue("pitch turned", after.sceneCamera.pitch != 0f)
        assertEquals("the 2D view is not panned", before.viewportOffset, after.viewportOffset)
        val py = com.hereliesaz.graffitixr.common.scene.SceneMath.frame(after.sceneCamera, 1000f, 1600f)
            .project(pivot)!!
        assertEquals(px.first, py.first, 0.5f)
        assertEquals(px.second, py.second, 0.5f)
    }

    @Test
    fun `an orbit step turns the camera about the pivot, fixed on screen, in a project with a 3D group`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(group3d(), lyr("c").copy(parentId = "g")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        testDispatcher.scheduler.advanceUntilIdle()
        val pivot = viewModel.orbitPivot()!!
        val before = viewModel.uiState.value
        val px = com.hereliesaz.graffitixr.common.scene.SceneMath.frame(before.sceneCamera, 1000f, 1600f)
            .project(pivot)!!
        viewModel.onOrbitStep(com.hereliesaz.graffitixr.common.scene.OrbitStep.RIGHT)
        viewModel.onOrbitStep(com.hereliesaz.graffitixr.common.scene.OrbitStep.DOWN)
        val after = viewModel.uiState.value
        assertTrue("yaw turned", after.sceneCamera.yaw != 0f)
        assertTrue("pitch turned", after.sceneCamera.pitch != 0f)
        assertEquals(before.viewportOffset, after.viewportOffset)
        assertEquals(before.undoCount, after.undoCount)
        val py = com.hereliesaz.graffitixr.common.scene.SceneMath.frame(after.sceneCamera, 1000f, 1600f)
            .project(pivot)!!
        assertEquals(px.first, py.first, 0.5f)
        assertEquals(px.second, py.second, 0.5f)
    }

    @Test
    fun `a run of orbit step taps is saved once`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(group3d(), lyr("c").copy(parentId = "g")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        testDispatcher.scheduler.advanceUntilIdle()
        io.mockk.clearMocks(projectRepository, answers = false, recordedCalls = true)
        repeat(10) { viewModel.onOrbitStep(com.hereliesaz.graffitixr.common.scene.OrbitStep.RIGHT) }
        testDispatcher.scheduler.advanceTimeBy(100)
        assertTrue(viewModel.uiState.value.sceneCamera.yaw != 0f)
        testDispatcher.scheduler.advanceUntilIdle()
        io.mockk.coVerify(atLeast = 0, atMost = 1) {
            projectRepository.updateProject(any<(GraffitiProject) -> GraffitiProject>())
        }
    }

    @Test
    fun `orbit steps stop at the pitch clamp, and Reset view puts the camera back`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(group3d(), lyr("c").copy(parentId = "g")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        testDispatcher.scheduler.advanceUntilIdle()
        repeat(50) { viewModel.onOrbitStep(com.hereliesaz.graffitixr.common.scene.OrbitStep.UP) }
        assertEquals(
            -com.hereliesaz.graffitixr.common.scene.SceneCamera.MAX_PITCH,
            viewModel.uiState.value.sceneCamera.pitch, 1e-4f,
        )
        viewModel.resetViewport()
        assertTrue(viewModel.uiState.value.sceneCamera.isDefault)
    }

    @Test
    fun `an orbit step does nothing in a project with no 3D group`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        testDispatcher.scheduler.advanceUntilIdle()
        for (step in com.hereliesaz.graffitixr.common.scene.OrbitStep.values()) viewModel.onOrbitStep(step)
        assertTrue(viewModel.uiState.value.sceneCamera.isDefault)
    }

    @Test
    fun `a twist in a project with a 3D group rotates the canvas and does not orbit`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(group3d(), lyr("c").copy(parentId = "g", rotationX = 10f)))
        viewModel.onLayerActivated("c")
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        testDispatcher.scheduler.advanceUntilIdle()
        twoFinger(Offset.Zero, rotation = 20f)
        assertTrue(viewModel.uiState.value.sceneCamera.isDefault)
        assertEquals(20f, viewModel.uiState.value.viewportRotation, 0.01f)
    }

    @Test
    fun `two-finger drag still pans in a project with no 3D group`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        testDispatcher.scheduler.advanceUntilIdle()
        val before = viewModel.uiState.value.viewportOffset
        twoFinger(Offset(40f, 10f))
        assertEquals(before + Offset(40f, 10f), viewModel.uiState.value.viewportOffset)
        assertTrue(viewModel.uiState.value.sceneCamera.isDefault)
    }

    @Test
    fun `the Move tool on a 3D layer does not orbit even in a project with a 3D group`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(group3d(), modelLyr("a")))
        viewModel.onLayerActivated("a")
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onTransformClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        twoFinger(Offset(100f, 50f))
        assertTrue(viewModel.uiState.value.sceneCamera.isDefault)
    }

    @Test
    fun `double-tap is a no-op on a 2D layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a")))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        val before = viewModel.uiState.value
        viewModel.onCanvasDoubleTap()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(before.activeRotationAxis, viewModel.uiState.value.activeRotationAxis)
        assertEquals(before.layers, viewModel.uiState.value.layers)
    }

    @Test
    fun `the axis slot arms Draw surface on a 2D layer's group, and opens the views chooser on a surface`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val group = lyr("g").copy(type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP)
        viewModel.setLayers(listOf(group, lyr("c").copy(parentId = "g"), lyr("lone")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onLayerActivated("c")
        assertFalse(viewModel.uiState.value.layers.first { it.id == "c" }.is3DIn(viewModel.uiState.value.layers))
        viewModel.onAxisSlotTapped()
        assertEquals("g", viewModel.uiState.value.surfaceDrawGroupId)
        viewModel.onSurfaceProfileDrawn(listOf(Offset(100f, 900f), Offset(400f, 700f), Offset(700f, 900f)))
        testDispatcher.scheduler.advanceUntilIdle()
        val layers = viewModel.uiState.value.layers
        assertTrue(layers.first { it.id == "c" }.is3DIn(layers))
        val axis = viewModel.uiState.value.activeRotationAxis
        viewModel.onAxisSlotTapped()
        // No axis choice any more: the tap brings up the surface's views instead.
        assertEquals(axis, viewModel.uiState.value.activeRotationAxis)
        assertTrue(viewModel.uiState.value.surfaceViewsMenuOpen)
    }

    @Test
    fun `deleting the armed group disarms Draw surface, so the next stroke paints`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("g").copy(type = groupType), lyr("a").copy(parentId = "g")))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onArmSurfaceDraw("g")
        assertEquals("g", viewModel.uiState.value.surfaceDrawGroupId)
        viewModel.onLayerRemoved("g")
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.surfaceDrawGroupId)
    }

    /** What the 3D slot did in one state: the visible changes (armed, chip), and the document and history. */
    private data class SlotOutcome(
        val armed: Boolean,
        val notice: SurfaceDrawNotice?,
        val layersChanged: Boolean,
        val undo: Int,
        val axisMoved: Boolean,
    )

    private fun tapSlot(layers: List<Layer>, active: String?, canvas: Boolean = true): SlotOutcome {
        viewModel.setLayers(layers)
        if (canvas) viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onCancelSurfaceDraw()
        if (active != null) viewModel.onLayerActivated(active) else viewModel.onLayerActivated("none")
        testDispatcher.scheduler.advanceUntilIdle()
        val before = viewModel.uiState.value
        viewModel.onAxisSlotTapped()
        // Not advanceUntilIdle: that would run the notice's own timeout.
        testDispatcher.scheduler.runCurrent()
        val after = viewModel.uiState.value
        return SlotOutcome(
            armed = after.surfaceDrawArmed,
            notice = after.surfaceDrawNotice,
            layersChanged = after.layers != before.layers,
            undo = after.undoCount - before.undoCount,
            axisMoved = after.activeRotationAxis != before.activeRotationAxis,
        )
    }

    private val groupType = com.hereliesaz.graffitixr.common.model.LayerType.GROUP

    @Test
    fun `the 3D slot does something visible in every state, and changes no layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        // A lone ungrouped raster layer: armed, and not wrapped (that waits for the captured line).
        assertEquals(SlotOutcome(true, null, false, 0, false), tapSlot(listOf(lyr("a")), "a"))
        assertNotNull(viewModel.uiState.value.surfaceDrawWrapLayerId)
        // In a group, or the group itself: armed on the group.
        val grouped = listOf(lyr("g").copy(type = groupType), lyr("a").copy(parentId = "g"))
        assertEquals(SlotOutcome(true, null, false, 0, false), tapSlot(grouped, "a"))
        assertEquals("g", viewModel.uiState.value.surfaceDrawGroupId)
        assertEquals(SlotOutcome(true, null, false, 0, false), tapSlot(grouped, "g"))
        // Locked: a reason in the chip, nothing changes.
        assertEquals(
            SlotOutcome(false, SurfaceDrawNotice.LOCKED, false, 0, false),
            tapSlot(listOf(lyr("a").copy(isImageLocked = true)), "a"),
        )
        // No active layer: a reason, not a silent return.
        assertEquals(SlotOutcome(false, SurfaceDrawNotice.NO_LAYER, false, 0, false), tapSlot(listOf(lyr("a")), null))
        // A layer whose parent is no container cannot be armed: a reason.
        assertEquals(
            SlotOutcome(false, SurfaceDrawNotice.UNAVAILABLE, false, 0, false),
            tapSlot(listOf(lyr("p"), lyr("a").copy(parentId = "p")), "a"),
        )
        // A 3D layer: the axis steps (the slot shows it), as before.
        assertEquals(true, tapSlot(listOf(modelLyr("m")), "m").axisMoved)
        val ink = lyr("s").copy(spatial = com.hereliesaz.graffitixr.common.scene.SpatialLayerContent())
        assertEquals(true, tapSlot(listOf(ink), "s").axisMoved)
        // A layer whose group already has a surface: no axis step any more, the views chooser opens.
        val profile = com.hereliesaz.graffitixr.common.scene.SurfaceProfile(listOf(0f, 0f, 0f, 100f, 0f, 0f))
        val surfaced = listOf(lyr("g").copy(type = groupType, surface = profile), lyr("a").copy(parentId = "g"))
        assertEquals(false, tapSlot(surfaced, "a").axisMoved)
        assertTrue(viewModel.uiState.value.surfaceViewsMenuOpen)
    }

    @Test
    fun `arming then cancelling leaves the layers, groups and history exactly as they were`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("under"), lyr("lone"), lyr("over")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onLayerActivated("lone")
        testDispatcher.scheduler.advanceUntilIdle()
        val before = viewModel.uiState.value
        // By the slot again, and by Cancel (the Back key calls the same function).
        viewModel.onAxisSlotTapped()
        assertTrue(viewModel.uiState.value.surfaceDrawArmed)
        assertEquals(before.layers, viewModel.uiState.value.layers)
        viewModel.onAxisSlotTapped()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onAxisSlotTapped()
        viewModel.onCancelSurfaceDraw()
        testDispatcher.scheduler.advanceUntilIdle()
        val after = viewModel.uiState.value
        assertEquals(before.layers, after.layers)
        assertEquals(before.undoCount, after.undoCount)
        assertEquals(before.redoCount, after.redoCount)
        assertEquals(before.activeLayerId, after.activeLayerId)
        assertFalse(after.surfaceDrawArmed)
        assertNull(after.surfaceDrawNotice)
    }

    @Test
    fun `Skip wraps the layer and gives the group the flat default sheet as one undo step, Undo removes it`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("under"), lyr("lone"), lyr("over")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onLayerActivated("lone")
        testDispatcher.scheduler.advanceUntilIdle()
        val undo = viewModel.uiState.value.undoCount
        viewModel.onAxisSlotTapped()
        assertTrue(viewModel.uiState.value.surfaceDrawArmed)
        viewModel.onSkipSurfaceDraw()
        testDispatcher.scheduler.runCurrent()
        val st = viewModel.uiState.value
        val lone = st.layers.first { it.id == "lone" }
        val group = st.layers.first { it.id == lone.parentId }
        val surface = group.surface!!
        assertTrue(surface.isFlat)
        // Portrait 1000 x 1600 at the default view (offset 72, 72): 3/4 of the width, centred on the screen's
        // middle, which is canvas x = 428, in the plane z = 0.
        assertEquals(53f, surface.point(0)[0], 0.01f)
        assertEquals(803f, surface.point(1)[0], 0.01f)
        assertEquals(0f, surface.point(0)[2], 0f)
        assertFalse(st.surfaceDrawArmed)
        assertEquals(undo + 1, st.undoCount)
        assertTrue(lone.is3DIn(st.layers))
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("under", "lone", "over"), viewModel.uiState.value.layers.map { it.id })
    }

    @Test
    fun `Skip on an armed group sets the flat sheet, and Cancel still changes nothing`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val group = lyr("g").copy(type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP)
        viewModel.setLayers(listOf(group, lyr("c").copy(parentId = "g")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        val undo = viewModel.uiState.value.undoCount
        viewModel.onArmSurfaceDraw("g")
        viewModel.onCancelSurfaceDraw()
        assertNull(viewModel.uiState.value.layers.first { it.id == "g" }.surface)
        assertEquals(undo, viewModel.uiState.value.undoCount)
        viewModel.onArmSurfaceDraw("g")
        viewModel.onSkipSurfaceDraw()
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.layers.first { it.id == "g" }.surface!!.isFlat)
        assertEquals(undo + 1, viewModel.uiState.value.undoCount)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.layers.first { it.id == "g" }.surface)
    }

    private fun skipSheet(
        w: Int,
        h: Int,
        view: EditorIntent? = null,
    ): com.hereliesaz.graffitixr.common.scene.SurfaceProfile? {
        viewModel.setLayers(listOf(lyr("lone")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(w, h))
        view?.let { viewModel.dispatchForTest(it) }
        viewModel.onLayerActivated("lone")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onAxisSlotTapped()
        viewModel.onSkipSurfaceDraw()
        testDispatcher.scheduler.runCurrent()
        return viewModel.uiState.value.layers.firstOrNull { it.type == groupType }?.surface
    }

    /** A layer with art. Text stands in for pixels: Color.alpha is a stub in JVM tests, so a pixel scan sees none. */
    private fun paintedLayer(id: String): Layer = Layer(id = id, name = id, textParams = TextLayerParams(text = "art"))

    private fun armOn(layers: List<Layer>, active: String) {
        viewModel.setLayers(layers)
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onLayerActivated(active)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onAxisSlotTapped()
    }

    @Test
    fun `Skip is offered for an empty layer and for an empty group`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        armOn(listOf(lyr("lone")), "lone")
        assertTrue(viewModel.canSkipSurfaceDraw())
        viewModel.onCancelSurfaceDraw()
        val group = lyr("g").copy(type = groupType)
        viewModel.setLayers(listOf(group, lyr("c").copy(parentId = "g")))
        viewModel.onArmSurfaceDraw("g")
        assertTrue(viewModel.canSkipSurfaceDraw())
    }

    @Test
    fun `Skip is not offered for a layer with art, and a forced Skip is a no-op with a notice`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        armOn(listOf(paintedLayer("art")), "art")
        assertTrue(viewModel.uiState.value.surfaceDrawArmed)
        assertFalse(viewModel.canSkipSurfaceDraw())
        val undo = viewModel.uiState.value.undoCount
        viewModel.onSkipSurfaceDraw()
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("art"), viewModel.uiState.value.layers.map { it.id })
        assertEquals(undo, viewModel.uiState.value.undoCount)
        assertEquals(SurfaceDrawNotice.HAS_ART, viewModel.uiState.value.surfaceDrawNotice)
    }

    @Test
    fun `a bitmap recycled mid-scan means cannot tell, so Skip is hidden and nothing throws`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val bmp = io.mockk.mockk<Bitmap>(relaxed = true)
        io.mockk.every { bmp.isRecycled } returns false
        io.mockk.every { bmp.width } returns 2
        io.mockk.every { bmp.height } returns 3
        io.mockk.every { bmp.getPixels(any<IntArray>(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("Can't call getPixels() on a recycled bitmap")
        armOn(listOf(Layer(id = "r", name = "r", bitmap = bmp)), "r")
        assertTrue(viewModel.uiState.value.surfaceDrawArmed)
        assertFalse(viewModel.canSkipSurfaceDraw())
        assertFalse(viewModel.canSkipSurfaceDrawOffMain())
    }

    @Test
    fun `a Skip whose scan answers after the user re-armed another layer, or art arrived, commits nothing`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        // Re-armed on another layer while the scan is out.
        armOn(listOf(lyr("a"), lyr("b")), "a")
        scanDispatcher.held = true
        viewModel.onSkipSurfaceDraw()
        testDispatcher.scheduler.runCurrent()
        viewModel.onCancelSurfaceDraw()
        viewModel.onLayerActivated("b")
        testDispatcher.scheduler.runCurrent()
        viewModel.onAxisSlotTapped()
        scanDispatcher.release()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("a", "b"), viewModel.uiState.value.layers.map { it.id })
        assertTrue(viewModel.uiState.value.surfaceDrawArmed)
        // Art arrived (the layers changed) while the scan is out.
        viewModel.onCancelSurfaceDraw()
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.runCurrent()
        viewModel.onAxisSlotTapped()
        scanDispatcher.held = true
        viewModel.onSkipSurfaceDraw()
        testDispatcher.scheduler.runCurrent()
        viewModel.setLayers(listOf(paintedLayer("a"), lyr("b")))
        scanDispatcher.release()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("a", "b"), viewModel.uiState.value.layers.map { it.id })
        assertNull(viewModel.uiState.value.layers.firstOrNull { it.type == groupType })
    }

    @Test
    fun `Skip is not offered for a group that holds a layer with art`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val group = lyr("g").copy(type = groupType)
        viewModel.setLayers(listOf(group, paintedLayer("art").copy(parentId = "g")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onArmSurfaceDraw("g")
        assertFalse(viewModel.canSkipSurfaceDraw())
        viewModel.onSkipSurfaceDraw()
        testDispatcher.scheduler.runCurrent()
        assertNull(viewModel.uiState.value.layers.first { it.id == "g" }.surface)
        assertEquals(SurfaceDrawNotice.HAS_ART, viewModel.uiState.value.surfaceDrawNotice)
    }

    @Test
    fun `Skip in landscape gives the landscape sheet`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val p = skipSheet(2000, 1000)!!
        // The screen's middle is canvas x = 1000 - 72 at the default view; 2/3 of 2000 wide.
        assertEquals(261.33f, p.point(0)[0], 0.1f)
        assertEquals(1594.67f, p.point(1)[0], 0.1f)
        assertEquals(750f, p.reach!!, 0.1f)
    }

    @Test
    fun `Skip centres the sheet on what the user sees when the view is zoomed and panned`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val p = skipSheet(1000, 1600, EditorIntent.SetViewport(Offset(100f, 200f), 2f, 0f))!!
        // The screen's middle (500, 800) is world (200, 300); 3/4 of the width on screen is 375 world units.
        assertEquals(12.5f, p.point(0)[0], 0.1f)
        assertEquals(387.5f, p.point(1)[0], 0.1f)
        assertEquals(300f - 1600f / 3f / 2f, p.point(0)[1], 0.1f)
        assertEquals(1600f * 2f / 3f / 2f, p.reach!!, 0.1f)
    }

    @Test
    fun `Skip with no usable canvas changes nothing and says the surface is unavailable`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("lone")))
        viewModel.onLayerActivated("lone")
        testDispatcher.scheduler.advanceUntilIdle()
        val undo = viewModel.uiState.value.undoCount
        viewModel.onAxisSlotTapped()
        viewModel.onSkipSurfaceDraw()
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("lone"), viewModel.uiState.value.layers.map { it.id })
        assertEquals(undo, viewModel.uiState.value.undoCount)
        assertEquals(SurfaceDrawNotice.UNAVAILABLE, viewModel.uiState.value.surfaceDrawNotice)
    }

    @Test
    fun `Redo after undoing Skip brings the flat sheet back`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(skipSheet(1000, 1600))
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.layers.firstOrNull { it.type == groupType })
        viewModel.onRedoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.layers.first { it.type == groupType }.surface!!.isFlat)
    }

    @Test
    fun `the wrap and the profile are one undo step, made when the line is captured`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("under"), lyr("lone"), lyr("over")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onLayerActivated("lone")
        testDispatcher.scheduler.advanceUntilIdle()
        val undo = viewModel.uiState.value.undoCount
        viewModel.onAxisSlotTapped()
        // A tap-length line: nothing made, still armed, and it says so.
        viewModel.onSurfaceProfileDrawn(listOf(Offset(10f, 10f), Offset(11f, 10f)))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("under", "lone", "over"), viewModel.uiState.value.layers.map { it.id })
        assertTrue(viewModel.uiState.value.surfaceDrawArmed)
        assertEquals(SurfaceDrawNotice.TOO_SHORT, viewModel.uiState.value.surfaceDrawNotice)
        assertEquals(undo, viewModel.uiState.value.undoCount)
        viewModel.onSurfaceProfileDrawn(listOf(Offset(100f, 900f), Offset(400f, 700f), Offset(700f, 900f)))
        testDispatcher.scheduler.runCurrent()
        val st = viewModel.uiState.value
        val lone = st.layers.first { it.id == "lone" }
        val group = st.layers.first { it.id == lone.parentId }
        assertEquals(groupType, group.type)
        assertNotNull(group.surface)
        assertEquals(listOf("under", group.id, "over"), st.layers.filter { it.parentId == null }.map { it.id })
        assertFalse(st.surfaceDrawArmed)
        assertEquals(SurfaceDrawNotice.DONE, st.surfaceDrawNotice)
        assertTrue(lone.is3DIn(st.layers))
        assertEquals(undo + 1, st.undoCount)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("under", "lone", "over"), viewModel.uiState.value.layers.map { it.id })
    }

    @Test
    fun `a too-short stroke on a group stays armed and says so`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        tapSlot(listOf(lyr("g").copy(type = groupType), lyr("a").copy(parentId = "g")), "a")
        val undo = viewModel.uiState.value.undoCount
        viewModel.onSurfaceProfileDrawn(listOf(Offset(10f, 10f)))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("g", viewModel.uiState.value.surfaceDrawGroupId)
        assertEquals(SurfaceDrawNotice.TOO_SHORT, viewModel.uiState.value.surfaceDrawNotice)
        assertEquals(undo, viewModel.uiState.value.undoCount)
    }

    private fun surfaceGroupVm(): String {
        viewModel.setLayers(listOf(lyr("g").copy(type = groupType), lyr("a").copy(parentId = "g")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onAxisSlotTapped()
        viewModel.onSurfaceProfileDrawn(listOf(Offset(100f, 900f), Offset(400f, 700f), Offset(700f, 900f)))
        testDispatcher.scheduler.runCurrent()
        return "g"
    }

    @Test
    fun `a side view turns the camera, captures into the group, and puts the camera back`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val g = surfaceGroupVm()
        val before = viewModel.uiState.value
        val front = before.layers.first { it.id == g }.surface!!
        val camera = before.sceneCamera
        viewModel.onArmSurfaceView(g, SurfaceView.SIDE)
        val armed = viewModel.uiState.value
        assertEquals(SurfaceView.SIDE, armed.surfaceDrawView)
        assertEquals(SurfaceView.SIDE.camera(camera), armed.sceneCamera)
        assertEquals(camera, armed.surfaceReturnCamera)
        val undo = armed.undoCount
        val box = listOf(Offset(100f, 300f), Offset(500f, 300f), Offset(500f, 900f), Offset(100f, 900f))
        viewModel.onSurfaceProfileDrawn(box)
        testDispatcher.scheduler.runCurrent()
        val st = viewModel.uiState.value
        val surface = st.layers.first { it.id == g }.surface!!
        assertNotNull(surface.side)
        assertEquals(front.copy(side = null, top = null), surface.copy(side = null, top = null))
        assertNull(surface.top)
        assertEquals(camera, st.sceneCamera)
        assertNull(st.surfaceReturnCamera)
        assertFalse(st.surfaceDrawArmed)
        assertEquals(SurfaceDrawNotice.VIEW_DONE, st.surfaceDrawNotice)
        assertEquals(undo + 1, st.undoCount)
        // Drawing the front again keeps the side; drawing the side again replaces it.
        viewModel.onArmSurfaceView(g, SurfaceView.FRONT)
        viewModel.onSurfaceProfileDrawn(listOf(Offset(100f, 800f), Offset(700f, 800f)))
        testDispatcher.scheduler.runCurrent()
        assertEquals(surface.side, viewModel.uiState.value.layers.first { it.id == g }.surface!!.side)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.layers.first { it.id == g }.surface!!.side)
    }

    @Test
    fun `cancelling a side view puts the camera back and changes nothing`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val g = surfaceGroupVm()
        val before = viewModel.uiState.value
        viewModel.onArmSurfaceView(g, SurfaceView.TOP)
        viewModel.onArmSurfaceView(g, SurfaceView.SIDE)
        // Switching views keeps the first camera to return to.
        assertEquals(before.sceneCamera, viewModel.uiState.value.surfaceReturnCamera)
        viewModel.onCancelSurfaceDraw()
        val after = viewModel.uiState.value
        assertEquals(before.sceneCamera, after.sceneCamera)
        assertEquals(before.layers, after.layers)
        assertEquals(before.undoCount, after.undoCount)
        assertFalse(after.surfaceDrawArmed)
        // A group with no surface cannot take a side view, and says so.
        viewModel.setLayers(listOf(lyr("p").copy(type = groupType)))
        viewModel.onArmSurfaceView("p", SurfaceView.SIDE)
        assertEquals(SurfaceDrawNotice.UNAVAILABLE, viewModel.uiState.value.surfaceDrawNotice)
        assertFalse(viewModel.uiState.value.surfaceDrawArmed)
    }

    @Test
    fun `a reason and the confirmation clear themselves`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        tapSlot(listOf(lyr("a").copy(isImageLocked = true)), "a")
        assertEquals(SurfaceDrawNotice.LOCKED, viewModel.uiState.value.surfaceDrawNotice)
        testDispatcher.scheduler.advanceTimeBy(7_000)
        testDispatcher.scheduler.runCurrent()
        assertNull(viewModel.uiState.value.surfaceDrawNotice)
    }

    @Test
    fun `the surface-set confirmation offers Draw side and top view, so it stays until the user acts`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("lone")))
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        viewModel.onLayerActivated("lone")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onAxisSlotTapped()
        viewModel.onSurfaceProfileDrawn(listOf(Offset(100f, 900f), Offset(400f, 700f), Offset(700f, 900f)))
        testDispatcher.scheduler.advanceTimeBy(10 * 60_000L)
        testDispatcher.scheduler.runCurrent()
        assertEquals(SurfaceDrawNotice.DONE, viewModel.uiState.value.surfaceDrawNotice)
        viewModel.onCancelSurfaceDraw()
        assertNull(viewModel.uiState.value.surfaceDrawNotice)
    }

    @Test
    fun `the armed prompt has no timeout`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        tapSlot(listOf(lyr("a")), "a")
        testDispatcher.scheduler.advanceTimeBy(60_000)
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.surfaceDrawArmed)
    }

    @Test
    fun `a pending wrap follows its layer into a group made meanwhile, and the capture still works`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        tapSlot(listOf(lyr("a"), lyr("b")), "a")
        assertEquals("a", viewModel.uiState.value.surfaceDrawWrapLayerId)
        // GroupLayers puts "a" inside a new group while the capture is armed.
        viewModel.onGroupWithLayerAbove("a")
        testDispatcher.scheduler.runCurrent()
        val st = viewModel.uiState.value
        val group = st.layers.first { it.id == st.layers.first { l -> l.id == "a" }.parentId }
        assertNull(st.surfaceDrawWrapLayerId)
        assertEquals(group.id, st.surfaceDrawGroupId)
        viewModel.onSurfaceProfileDrawn(listOf(Offset(100f, 900f), Offset(400f, 700f), Offset(700f, 900f)))
        testDispatcher.scheduler.runCurrent()
        assertNotNull(viewModel.uiState.value.layers.first { it.id == group.id }.surface)
        assertFalse(viewModel.uiState.value.surfaceDrawArmed)
    }

    @Test
    fun `undoing the capture, or removing the layer, leaves nothing armed`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        tapSlot(listOf(lyr("a"), lyr("b")), "a")
        viewModel.onLayerRemoved("a")
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.surfaceDrawArmed)
    }

    @Test
    fun `the rotation slider leaves the open panel open`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a")))
        viewModel.onLayerActivated("a")
        viewModel.onTransformClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(EditorPanel.TRANSFORM, viewModel.uiState.value.activePanel)
        val undo = viewModel.uiState.value.undoCount
        sliderDrag(20f)
        assertEquals(EditorPanel.TRANSFORM, viewModel.uiState.value.activePanel)
        assertEquals(undo + 1, viewModel.uiState.value.undoCount)
        assertEquals(20f, viewModel.uiState.value.layers.single().rotationZ, 0.01f)
    }

    @Test
    fun `the rotation slider does nothing on a locked layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a").copy(isImageLocked = true)))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.rotationSliderValue())
        val undo = viewModel.uiState.value.undoCount
        sliderDrag(45f)
        viewModel.onRotationSliderReset()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0f, viewModel.uiState.value.layers.single().rotationZ, 0.01f)
        assertEquals(undo, viewModel.uiState.value.undoCount)
    }

    private fun sliderDrag(vararg degrees: Float) {
        viewModel.onRotationSliderStart()
        degrees.forEach { viewModel.onRotationSliderChange(it) }
        viewModel.onRotationSliderEnd()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `the rotation slider turns a 2D layer about Z, live, as one undo step`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a")))
        viewModel.onLayerActivated("a")
        setAxis(RotationAxis.X) // ignored on a 2D layer
        testDispatcher.scheduler.advanceUntilIdle()
        val undo = viewModel.uiState.value.undoCount
        val viewRotation = viewModel.uiState.value.viewportRotation
        sliderDrag(10f, 25f, 40f)
        val layer = viewModel.uiState.value.layers.single()
        assertEquals(40f, layer.rotationZ, 0.01f)
        assertEquals(0f, layer.rotationX, 0.01f)
        assertEquals(viewRotation, viewModel.uiState.value.viewportRotation, 0.0001f)
        assertEquals(40f, viewModel.rotationSliderValue()!!, 0.01f)
        assertEquals(undo + 1, viewModel.uiState.value.undoCount)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0f, viewModel.uiState.value.layers.single().rotationZ, 0.01f)
    }

    @Test
    fun `the rotation slider uses the cycler axis on a 3D layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a").copy(rotationX = 10f)))
        viewModel.onLayerActivated("a")
        setAxis(RotationAxis.Y)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(RotationAxis.Y, viewModel.rotationSliderAxis())
        sliderDrag(-60f)
        val layer = viewModel.uiState.value.layers.single()
        // Body-fixed: the layer was turned 60 about its OWN Y, after the 10 about its X.
        assertEquals(-60f, viewModel.rotationSliderValue()!!, 0.01f)
        val expected = SceneMath.mul3(SceneMath.rotation(10f, 0f, 0f), SceneMath.axisRotation(RotationAxis.Y, -60f))
        val got = SceneMath.rotation(layer.rotationX, layer.rotationY, layer.rotationZ)
        for (i in 0..8) assertEquals("m[$i]", expected[i], got[i], 1e-4f)
    }

    private fun bodyLine(axis: RotationAxis): FloatArray {
        val l = viewModel.uiState.value.layers.single()
        val r = SceneMath.rotation(l.rotationX, l.rotationY, l.rotationZ)
        val c = axis.ordinal
        return floatArrayOf(r[c], r[3 + c], r[6 + c])
    }

    private fun assertLine(msg: String, a: FloatArray, b: FloatArray) {
        for (i in 0..2) assertEquals("$msg[$i]", a[i], b[i], 1e-3f)
    }

    private fun threeDLayer() = pivotSetup(lyr("a").copy(positionZ = 1f))

    @Test
    fun `Z 90 then the X slider turns about the line through sides 1 and 3`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        threeDLayer()
        setAxis(RotationAxis.Z)
        sliderDrag(90f)
        // Sides 1 and 3 are now where 2 and 4 began: the object's X line is the scene's vertical.
        assertLine("X line", floatArrayOf(0f, 1f, 0f), bodyLine(RotationAxis.X))
        setAxis(RotationAxis.X)
        val sideEnd = boxPoint("a", 1000f, 800f, 0f) // the +X end of the layer's centre line
        val centre = boxPoint("a", 500f, 800f, 0f)
        sliderDrag(40f)
        assertLine("X line after the X turn", floatArrayOf(0f, 1f, 0f), bodyLine(RotationAxis.X))
        assertEquals(40f, viewModel.rotationSliderValue()!!, 0.01f)
        // The line is the axis of this turn about the pivot, so its direction is unchanged even
        // though the layer swung round the pivot.
        val after = boxPoint("a", 1000f, 800f, 0f).zip(boxPoint("a", 500f, 800f, 0f)) { e, c -> e - c }
        val before = sideEnd.zip(centre) { e, c -> e - c }
        assertEquals(before.map { it * it }.sum(), after.map { it * it }.sum(), 1f)
        assertEquals(1f, (0..2).sumOf { (after[it] * bodyLine(RotationAxis.X)[it]).toDouble() }.toFloat() / 500f, 1e-3f)
    }

    @Test
    fun `X then Y then X turns about the object's own lines, and returning to X continues from where it was`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        threeDLayer()
        for ((axis, to) in listOf(RotationAxis.X to 30f, RotationAxis.Y to 50f, RotationAxis.X to 55f)) {
            setAxis(axis)
            val own = bodyLine(axis)
            val start = viewModel.rotationSliderValue()!!
            sliderDrag(to)
            assertLine("$axis line", own, bodyLine(axis))
            assertEquals("$axis reading", to, viewModel.rotationSliderValue()!!, 0.01f)
            assertTrue(start != to)
        }
    }

    @Test
    fun `a body-axis turn is one undo step, and undo and redo restore the exact pose`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        threeDLayer()
        setAxis(RotationAxis.X)
        sliderDrag(30f)
        setAxis(RotationAxis.Y)
        val posed = viewModel.uiState.value.layers.single()
        val undo = viewModel.uiState.value.undoCount
        sliderDrag(20f, 50f)
        val turned = viewModel.uiState.value.layers.single()
        assertEquals(undo + 1, viewModel.uiState.value.undoCount)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        val undone = viewModel.uiState.value.layers.single()
        assertEquals(posed.rotationX, undone.rotationX, 1e-4f)
        assertEquals(posed.rotationY, undone.rotationY, 1e-4f)
        assertEquals(posed.rotationZ, undone.rotationZ, 1e-4f)
        assertEquals(posed.offset, undone.offset)
        viewModel.onRedoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        val redone = viewModel.uiState.value.layers.single()
        assertEquals(turned.rotationX, redone.rotationX, 1e-4f)
        assertEquals(turned.rotationY, redone.rotationY, 1e-4f)
        assertEquals(turned.rotationZ, redone.rotationZ, 1e-4f)
        assertEquals(50f, viewModel.rotationSliderValue()!!, 0.01f)
    }

    @Test
    fun `a double-tap on the rotation slider resets to 0 as one undo step`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a").copy(rotationZ = 370f)))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(10f, viewModel.rotationSliderValue()!!, 0.01f)
        val undo = viewModel.uiState.value.undoCount
        viewModel.onRotationSliderReset()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0f, viewModel.rotationSliderValue()!!, 0.01f)
        assertEquals(undo + 1, viewModel.uiState.value.undoCount)
    }

    @Test
    fun `on a 2D layer a twist only rotates the canvas, on any axis`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a")))
        viewModel.onLayerActivated("a")
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        setAxis(RotationAxis.Y)
        viewModel.onTransformClicked()
        twoFinger(rotation = 5f, steps = 2)
        assertEquals(10f, viewModel.uiState.value.viewportRotation, 0.01f)
        assertTrue(viewModel.uiState.value.sceneCamera.isDefault)
        assertEquals(0f, viewModel.uiState.value.layers.single().rotationY)
    }

    /** A surface group "g" with paint layers "c" and "d", "c" active, the canvas sized. */
    private fun surfaceGroupSetup(locked: Boolean = false) {
        val floor = com.hereliesaz.graffitixr.common.scene.SurfaceMath.capture(
            listOf(200f to 1000f, 800f to 1000f), SceneCamera(), 1000f, 1600f,
            listOf(com.hereliesaz.graffitixr.common.scene.PlanePose()),
        )!!
        val group = lyr("g").copy(type = groupType, surface = floor, isImageLocked = locked)
        viewModel.setLayers(listOf(group, lyr("c").copy(parentId = "g"), lyr("d").copy(parentId = "g")))
        viewModel.onLayerActivated("c")
        viewModel.onCanvasSizeChanged(androidx.compose.ui.unit.IntSize(1000, 1600))
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun groupBody(axis: RotationAxis): FloatArray {
        val l = viewModel.uiState.value.layers.first { it.id == "g" }
        val r = SceneMath.rotation(l.rotationX, l.rotationY, l.rotationZ)
        val c = axis.ordinal
        return floatArrayOf(r[c], r[3 + c], r[6 + c])
    }

    @Test
    fun `the rotation triangle is up only with an active layer on a surface group`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("flat")))
        viewModel.onLayerActivated("flat")
        assertFalse(viewModel.rotationGizmoVisible())
        viewModel.setLayers(listOf(lyr("tilted").copy(rotationX = 10f)))
        viewModel.onLayerActivated("tilted")
        assertFalse("a tilted plane is 3D but has no surface", viewModel.rotationGizmoVisible())
        assertNotNull("it keeps its slider", viewModel.rotationSliderValue())
        surfaceGroupSetup()
        assertTrue(viewModel.rotationGizmoVisible())
        assertNull("the slider is gone on a surface", viewModel.rotationSliderValue())
        viewModel.onLayerActivated("g")
        assertTrue("the group itself counts", viewModel.rotationGizmoVisible())
    }

    @Test
    fun `the rotation triangle is down for a locked surface group`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        surfaceGroupSetup(locked = true)
        assertFalse(viewModel.rotationGizmoVisible())
        val before = viewModel.uiState.value.layers
        viewModel.onRotationGizmoTurn(RotationAxis.X, 30f)
        viewModel.onRotationGizmoEnd()
        assertEquals(before, viewModel.uiState.value.layers)
    }

    @Test
    fun `triangle turns accumulate across swipes with no end stop, and a whole drag is one undo step`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        surfaceGroupSetup()
        val undo = viewModel.uiState.value.undoCount
        // Two swipes of 180 about the object's own Y: a full turn, back to the start.
        repeat(2) {
            viewModel.onRotationGizmoTurn(RotationAxis.Y, 90f)
            viewModel.onRotationGizmoTurn(RotationAxis.Y, 90f)
            viewModel.onRotationGizmoEnd()
            testDispatcher.scheduler.advanceUntilIdle()
        }
        assertEquals(undo + 2, viewModel.uiState.value.undoCount)
        val g = viewModel.uiState.value.layers.first { it.id == "g" }
        val r = SceneMath.rotation(g.rotationX, g.rotationY, g.rotationZ)
        val identity = SceneMath.rotation(0f, 0f, 0f)
        for (i in 0..8) assertEquals("m[$i]", identity[i], r[i], 1e-3f)
        // 135 + 135 is past a half turn and still moves: not clamped at 180.
        viewModel.onRotationGizmoTurn(RotationAxis.X, 135f); viewModel.onRotationGizmoEnd()
        viewModel.onRotationGizmoTurn(RotationAxis.X, 135f); viewModel.onRotationGizmoEnd()
        val g2 = viewModel.uiState.value.layers.first { it.id == "g" }
        val expected = SceneMath.axisRotation(RotationAxis.X, 270f)
        val got = SceneMath.rotation(g2.rotationX, g2.rotationY, g2.rotationZ)
        for (i in 0..8) assertEquals("x[$i]", expected[i], got[i], 1e-3f)
    }

    @Test
    fun `undoing a triangle drag restores the pose, and the paint layers are never turned`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        surfaceGroupSetup()
        val before = viewModel.uiState.value.layers
        viewModel.onRotationGizmoTurn(RotationAxis.X, 20f)
        viewModel.onRotationGizmoTurn(RotationAxis.X, 25f)
        viewModel.onRotationGizmoEnd()
        testDispatcher.scheduler.advanceUntilIdle()
        val turned = viewModel.uiState.value.layers
        assertEquals(0f, turned.first { it.id == "c" }.let { it.rotationX + it.rotationY + it.rotationZ }, 0f)
        assertTrue(turned.first { it.id == "g" }.rotationX != 0f)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        val restored = viewModel.uiState.value.layers.first { it.id == "g" }
        assertEquals(before.first { it.id == "g" }.rotationX, restored.rotationX, 1e-4f)
    }

    @Test
    fun `each triangle axis turns about the group's own line, in the slider's positive sense`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        surfaceGroupSetup()
        viewModel.onRotationGizmoStep(RotationAxis.Y, 90f)
        // Same result as composing R * Ry(90), what the slider's +90 on Y did.
        val g = viewModel.uiState.value.layers.first { it.id == "g" }
        val got = SceneMath.rotation(g.rotationX, g.rotationY, g.rotationZ)
        val expected = SceneMath.axisRotation(RotationAxis.Y, 90f)
        for (i in 0..8) assertEquals("m[$i]", expected[i], got[i], 1e-3f)
        // X is then the scene's -Z/+Z line; the body X line is untouched by a turn about it.
        val xLine = groupBody(RotationAxis.X)
        viewModel.onRotationGizmoStep(RotationAxis.X, 40f)
        for (i in 0..2) assertEquals("X line[$i]", xLine[i], groupBody(RotationAxis.X)[i], 1e-3f)
    }

    @Test
    fun `an accessibility step is one undo step`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        surfaceGroupSetup()
        val undo = viewModel.uiState.value.undoCount
        viewModel.onRotationGizmoStep(RotationAxis.Z, 15f)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(undo + 1, viewModel.uiState.value.undoCount)
    }

    @Test
    fun `the 3D button on a surface no longer chooses an axis, it opens the views chooser`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        surfaceGroupSetup()
        val axis = viewModel.uiState.value.activeRotationAxis
        val layers = viewModel.uiState.value.layers
        repeat(3) { viewModel.onAxisSlotTapped(); viewModel.onAxisSlotTapped() }
        assertEquals(axis, viewModel.uiState.value.activeRotationAxis)
        assertEquals(layers, viewModel.uiState.value.layers)
        viewModel.onAxisSlotTapped()
        assertTrue(viewModel.uiState.value.surfaceViewsMenuOpen)
        assertEquals(
            listOf(SurfaceView.FRONT, SurfaceView.SIDE, SurfaceView.TOP),
            viewModel.surfaceViewChoices().map { it.view },
        )
    }

    @Test
    fun `picking a view in the chooser closes it and arms that view's drawing`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        surfaceGroupSetup()
        viewModel.onAxisSlotTapped()
        viewModel.onPickSurfaceView(SurfaceView.SIDE)
        val s = viewModel.uiState.value
        assertFalse(s.surfaceViewsMenuOpen)
        assertEquals("g", s.surfaceDrawGroupId)
        assertEquals(SurfaceView.SIDE, s.surfaceDrawView)
        viewModel.onCancelSurfaceDraw()
        viewModel.onAxisSlotTapped()
        viewModel.onPickSurfaceView(SurfaceView.FRONT)
        assertEquals(SurfaceView.FRONT, viewModel.uiState.value.surfaceDrawView)
        assertEquals("g", viewModel.uiState.value.surfaceDrawGroupId)
        viewModel.onCancelSurfaceDraw()
        viewModel.onAxisSlotTapped()
        viewModel.onDismissSurfaceViews()
        assertFalse(viewModel.uiState.value.surfaceViewsMenuOpen)
    }

    @Test
    fun `the 3D button still cycles the axis on a tilted plane and still arms Draw surface on a flat layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a").copy(rotationX = 10f)))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        val before = viewModel.uiState.value.activeRotationAxis
        viewModel.onAxisSlotTapped()
        assertNotEquals(before, viewModel.uiState.value.activeRotationAxis)
        assertFalse(viewModel.uiState.value.surfaceViewsMenuOpen)
    }

    @Test
    fun `resolvedPlaybackRange resolves the -1 end sentinel to the last frame and clamps a stale range`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a"), lyr("b"), lyr("c")))
        testDispatcher.scheduler.advanceUntilIdle()

        // Untouched default (0..-1) resolves to the whole frame set.
        assertEquals(0, viewModel.resolvedPlaybackRange().first)
        assertEquals(2, viewModel.resolvedPlaybackRange().last)

        // A range that outlived a frame count drop clamps into what's left rather than throwing.
        viewModel.onSetAnimationRange(1, 50)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.resolvedPlaybackRange().first)
        assertEquals(2, viewModel.resolvedPlaybackRange().last)
    }

    @Test
    fun `onSetFrameHoldCount sets the hold count on the current frame only`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a"), lyr("b")))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onSelectFrame(0)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.currentFrameHoldCount())
        viewModel.onSetFrameHoldCount(4)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(4, viewModel.currentFrameHoldCount())
        assertEquals(4, viewModel.uiState.value.layers.first { it.id == "a" }.frameHoldCount)
        assertEquals(1, viewModel.uiState.value.layers.first { it.id == "b" }.frameHoldCount)
    }

    @Test
    fun `onRotateLayerHandle rotates active layer on Z axis`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val l = lyr("a").copy(rotationZ = 15f)
        viewModel.setLayers(listOf(l))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRotateLayerHandle(45f)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(60f, viewModel.uiState.value.layers.first().rotationZ, 0.01f)
    }

    @Test
    fun `rapid text edits cancel the previous rasterize job instead of racing it`() = runTest {
        // A glee audit found rerasterizeTextLayer() launched an unserialized coroutine on every
        // keystroke, each one racing to rasterize AND overwrite the same on-disk PNG -- an older,
        // still-in-flight write landing after a newer one could silently revert the layer on disk.
        // Pins the fix: a second edit before the first has run must cancel the first's job.
        val layer = lyr("text1").copy(textParams = TextLayerParams(text = "a"))
        viewModel.setLayers(listOf(layer))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onTextContentChanged("text1", "ab")
        val firstJob = viewModel.textRasterizeJobForTest("text1")
        assertNotNull("the first edit's rasterize coroutine must be tracked", firstJob)
        assertFalse("the first job must not already be cancelled", firstJob!!.isCancelled)

        viewModel.onTextContentChanged("text1", "abc")
        assertTrue(
            "a second edit arriving before the first one ran must cancel the first job",
            firstJob.isCancelled,
        )
        val secondJob = viewModel.textRasterizeJobForTest("text1")
        assertNotNull(secondJob)
        assertNotEquals(firstJob, secondJob)
        assertFalse(secondJob!!.isCancelled)

        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `inserting a path node shifts a selection that sits past the insertion point`() {
        // A glee audit found insertNode() shifts every later node's index up by one but
        // selectedNodeIndex was left untouched -- so a selection past the insertion point silently
        // pointed at the wrong node afterward. This pins the fix.
        val path = com.hereliesaz.graffitixr.common.model.VectorShape(
            kind = com.hereliesaz.graffitixr.common.model.ShapeKind.PATH,
            points = listOf(0f, 0f, 100f, 0f, 100f, 100f),
        )
        val layer = lyr("path1").copy(shapes = listOf(path))
        viewModel.setLayers(listOf(layer))
        viewModel.onSetPathEditLayer("path1")
        viewModel.onSelectPathNode(2)

        // Inserts a new node between node 0 and node 1 (segmentIndex 0): the selected node (2) sits
        // past the insertion point and must shift to 3 to keep pointing at the same node.
        viewModel.onInsertPathNode(segmentIndex = 0, t = 0.5f)

        assertEquals(3, viewModel.uiState.value.selectedNodeIndex)
    }

    @Test
    fun `inserting a path node before the selection leaves it untouched`() {
        val path = com.hereliesaz.graffitixr.common.model.VectorShape(
            kind = com.hereliesaz.graffitixr.common.model.ShapeKind.PATH,
            points = listOf(0f, 0f, 100f, 0f, 100f, 100f),
        )
        val layer = lyr("path1").copy(shapes = listOf(path))
        viewModel.setLayers(listOf(layer))
        viewModel.onSetPathEditLayer("path1")
        viewModel.onSelectPathNode(0)

        // segmentIndex 1 (between node 1 and node 2) is entirely after the selected node (0), which
        // must not move.
        viewModel.onInsertPathNode(segmentIndex = 1, t = 0.5f)

        assertEquals(0, viewModel.uiState.value.selectedNodeIndex)
    }

    @Test
    fun `the read-only warning for a newer-format project waits for the host`() = runTest {
        // Cold start: the project opens before anything collects (no collector is ever attached here).
        currentProjectFlow.value = GraffitiProject(id = "future", name = "Future", formatVersion = 99)
        testDispatcher.scheduler.advanceUntilIdle()
        val notice = viewModel.readOnlyNotice.value
        assertNotNull(notice)
        assertEquals(NoticeKind.WARNING, notice!!.kind)
        viewModel.consumeReadOnlyNotice(notice)
        assertNull(viewModel.readOnlyNotice.value)
    }

    @Test
    fun `a read-only project is never saved`() = runTest {
        currentProjectFlow.value = GraffitiProject(id = "future", name = "Future", formatVersion = 99)
        testDispatcher.scheduler.advanceUntilIdle()
        clearMocks(
            projectRepository, answers = false, recordedCalls = true, childMocks = false,
            verificationMarks = true, exclusionRules = false,
        )
        viewModel.saveProject()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { projectRepository.updateProject(any<(GraffitiProject) -> GraffitiProject>()) }
        coVerify(exactly = 0) { projectRepository.updateProject(any<GraffitiProject>()) }
    }

    // ── Hull gaps: carry, duplicate, fill/clone/lasso on view images ──

    private fun paintBitmap(): Bitmap = mockk<Bitmap>(relaxed = true).also {
        every { it.width } returns 64
        every { it.height } returns 64
        every { it.copy(any(), any()) } returns it
    }

    /** A surface group "g" with paint layer "a" holding pixels, still seen from the front only. */
    private fun paintedSurfaceVm(): Bitmap {
        every { context.filesDir } returns java.io.File(System.getProperty("java.io.tmpdir"))
        surfaceGroupVm()
        val bmp = paintBitmap()
        viewModel.setLayers(viewModel.uiState.value.layers.map { if (it.id == "a") it.copy(bitmap = bmp) else it })
        testDispatcher.scheduler.advanceUntilIdle()
        return bmp
    }

    private fun drawSide(g: String = "g") {
        viewModel.onArmSurfaceView(g, SurfaceView.SIDE)
        viewModel.onSurfaceProfileDrawn(
            listOf(Offset(100f, 300f), Offset(500f, 300f), Offset(500f, 900f), Offset(100f, 900f)),
        )
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `drawing the second view carries sheet paint onto the front image as one undo step`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        paintedSurfaceVm()
        val undo = viewModel.uiState.value.undoCount
        drawSide()
        val st = viewModel.uiState.value
        val image = st.layers.single { it.surfaceViewOf == "a" }
        assertEquals(SurfaceView.FRONT, image.surfaceView)
        assertEquals("g", image.parentId)
        assertFalse(image.isVisible)
        assertNotNull(image.bitmap)
        // The layer keeps its own pixels; the image sits right after it in the stack.
        assertNotNull(st.layers.first { it.id == "a" }.bitmap)
        assertEquals(st.layers.indexOfFirst { it.id == "a" } + 1, st.layers.indexOfFirst { it.id == image.id })
        assertEquals(undo + 1, st.undoCount)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.layers.none { it.surfaceViewOf != null })
        assertNull(viewModel.uiState.value.layers.first { it.id == "g" }.surface!!.side)
    }

    @Test
    fun `a surface that already had a second view, or a layer with no pixels, carries nothing`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        surfaceGroupVm()
        drawSide()
        assertTrue(viewModel.uiState.value.layers.none { it.surfaceViewOf != null })
        // Redrawing a view once there is a hull is not a first view: nothing new is carried.
        val bmp = paintBitmap()
        viewModel.setLayers(viewModel.uiState.value.layers.map { if (it.id == "a") it.copy(bitmap = bmp) else it })
        drawSide()
        assertTrue(viewModel.uiState.value.layers.none { it.surfaceViewOf != null })
    }

    @Test
    fun `duplicating a paint layer duplicates its view images, relinked, and undo takes them back`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        paintedSurfaceVm()
        drawSide()
        val source = viewModel.uiState.value.layers.single { it.surfaceViewOf == "a" }
        viewModel.onLayerDuplicated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        val layers = viewModel.uiState.value.layers
        val copy = layers.single { it.name == "a Copy" }
        val images = layers.filter { it.surfaceViewOf == copy.id }
        assertEquals(1, images.size)
        assertNotEquals(source.id, images.single().id)
        assertEquals(SurfaceView.FRONT, images.single().surfaceView)
        assertEquals(copy.parentId, images.single().parentId)
        // The original's image is still its own.
        assertEquals(1, layers.count { it.surfaceViewOf == "a" })
        assertEquals(layers.indexOfFirst { it.id == copy.id } + 1, layers.indexOfFirst { it.id == images.single().id })
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.layers.none { it.id == copy.id || it.surfaceViewOf == copy.id })
        assertEquals(1, viewModel.uiState.value.layers.count { it.surfaceViewOf == "a" })
    }

    @Test
    fun `a fill on a multi-view surface lands in the tapped view's image, bounded to its face, and a lasso clips it per image`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        paintedSurfaceVm()
        drawSide()
        viewModel.setActiveTool(Tool.FILL)
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        val at = com.hereliesaz.graffitixr.common.scene.SurfaceMath.ViewFlat(SurfaceView.SIDE, 20f, 30f, 0)
        viewModel.onViewFillTap("a", at, androidx.compose.ui.unit.IntSize(1000, 1600))
        testDispatcher.scheduler.advanceUntilIdle()
        val image = viewModel.uiState.value.layers
            .single { it.surfaceViewOf == "a" && it.surfaceView == SurfaceView.SIDE }
        // Not on the paint layer's own sheet: on the side image, from the tapped flat point.
        assertTrue(viewModel.recordedStrokesForTest("a").none { it.tool == Tool.FILL })
        val fill = viewModel.recordedStrokesForTest(image.id).single { it.tool == Tool.FILL }
        assertEquals(listOf(Offset(20f, 30f)), fill.path)
        // No lasso: the fill is held to the face (a one-ring region round it).
        assertEquals(1, fill.selection!!.rings.size)
        // A lasso that covers nothing of the surface blocks the fill.
        viewModel.onSelectionEnd(
            listOf(Offset(-500f, -500f), Offset(-400f, -500f), Offset(-400f, -400f)),
            androidx.compose.ui.unit.IntSize(1000, 1600),
        )
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.viewSelectionBlocks("a", SurfaceView.SIDE))
        val n = viewModel.recordedStrokesForTest(image.id).size
        viewModel.onViewFillTap("a", at, androidx.compose.ui.unit.IntSize(1000, 1600))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(n, viewModel.recordedStrokesForTest(image.id).size)
    }

    @Test
    fun `retryModelDownloads waits for the installed-extension scan before looking for pending downloads`() = runTest {
        val scanDone = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { extensionRepository.awaitInitialScan() } coAnswers { scanDone.await() }
        every { extensionRepository.pendingDepthModelInstalls() } returns emptyList()

        viewModel.retryModelDownloads()
        testDispatcher.scheduler.advanceUntilIdle()
        // Cold launch: the scan has not published yet, so the inventory would read empty.
        verify(exactly = 0) { extensionRepository.pendingDepthModelInstalls() }

        scanDone.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        verify(exactly = 1) { extensionRepository.pendingDepthModelInstalls() }
    }

    // ── Export-frame preview in a 3D project (spec/3d-space.md, "Export") ───────────────────────────

    private fun withLayers(threeD: Boolean, vararg extra: Layer) {
        val group = Layer(
            id = "G", name = "G", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP,
            surface = if (threeD) {
                com.hereliesaz.graffitixr.common.scene.SurfaceProfile(listOf(0f, 0f, 0f, 24f, 0f, 0f, 48f, 0f, 20f))
            } else {
                null
            },
        )
        viewModel.dispatchForTest(EditorIntent.SetLayers(listOf(group) + extra))
    }

    private fun assertComposited(times: Int) = verify(exactly = times) {
        exportManager.compositeToDocument(any(), any(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `a project without a 3D group exports at once, with no preview`() = runTest {
        withLayers(threeD = false)
        viewModel.requestExportImage()
        assertFalse(viewModel.uiState.value.exportPreview)
        testDispatcher.scheduler.advanceUntilIdle()
        assertComposited(1)
    }

    @Test
    fun `a 3D project shows the export frame first and exports nothing until confirmed`() = runTest {
        withLayers(threeD = true)
        viewModel.requestExportImage()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.exportPreview)
        assertComposited(0)

        viewModel.confirmExportPreview()
        assertFalse(viewModel.uiState.value.exportPreview)
        testDispatcher.scheduler.advanceUntilIdle()
        assertComposited(1)
    }

    @Test
    fun `cancelling the export frame closes it and exports nothing, even on a later confirm`() = runTest {
        withLayers(threeD = true)
        viewModel.requestExportImage()
        viewModel.cancelExportPreview()
        assertFalse(viewModel.uiState.value.exportPreview)
        viewModel.confirmExportPreview()
        testDispatcher.scheduler.advanceUntilIdle()
        assertComposited(0)
    }

    @Test
    fun `the poster PDF goes through the export frame in a 3D project`() = runTest {
        withLayers(threeD = true)
        viewModel.requestExportPoster(com.hereliesaz.graffitixr.feature.editor.export.PosterSpec())
        assertTrue(viewModel.uiState.value.exportPreview)
        viewModel.cancelExportPreview()
    }

    @Test
    fun `a selected frame's export skips the export frame, since it crops to the frame and not the artboard`() = runTest {
        withLayers(threeD = true, Layer(
            id = "F", name = "F", type = com.hereliesaz.graffitixr.common.model.LayerType.FRAME,
            layoutWidth = 400f, layoutHeight = 200f,
        ))
        viewModel.onLayerActivated("F")
        viewModel.requestExportDesign(com.hereliesaz.graffitixr.common.model.DesignExportFormat.SVG)
        assertFalse(viewModel.uiState.value.exportPreview)
    }

    @Test
    fun `an SVG of the document in a 3D project goes through the export frame`() = runTest {
        withLayers(threeD = true, Layer(id = "L", name = "L"))
        viewModel.onLayerActivated("L")
        viewModel.requestExportDesign(com.hereliesaz.graffitixr.common.model.DesignExportFormat.SVG)
        assertTrue(viewModel.uiState.value.exportPreview)
        viewModel.cancelExportPreview()
    }
}
