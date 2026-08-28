package com.hereliesaz.graffitixr.feature.editor

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    private val slamManager: SlamManager = mockk(relaxed = true)
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
        
        // Mock static methods for Bitmap, Uri, and Toast
        mockkStatic(BitmapFactory::class)
        mockkStatic(android.graphics.Bitmap::class)
        mockkStatic(Uri::class)
        mockkStatic(Toast::class)
        every { Toast.makeText(any(), any<String>(), any()) } returns mockk(relaxed = true)
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
            override val default: CoroutineDispatcher = testDispatcher
            override val unconfined: CoroutineDispatcher = testDispatcher
        }

        viewModel = EditorViewModel(
            projectRepository, settingsRepository, projectManager, exportManager, context,
            slamManager, testDispatcherProvider, opEmitter, extensionRepository, customBrushRepository,
            figmaRepository, projectFileScanner,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(BitmapFactory::class)
        unmockkStatic(android.graphics.Bitmap::class)
        unmockkStatic(Uri::class)
        unmockkStatic(Toast::class)
        unmockkObject(com.hereliesaz.graffitixr.common.util.ImageUtils)
        unmockkObject(TextRasterizer)
        unmockkObject(GoogleFontCache)
        unmockkObject(NativeLibLoader)
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

        val state = viewModel.uiState.value
        assertNotNull(state.liveStrokeBitmap)
        assertTrue("Expected liveStrokeVersion >= 1, got ${state.liveStrokeVersion}", state.liveStrokeVersion >= 1)
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
    fun `onCanvasDoubleTap cycles selection to next layer at tap location`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        // Two overlapping vector layers at center (500, 500)
        val l1 = VectorShape(kind = ShapeKind.RECTANGLE, width = 400f, height = 400f)
        val layer1 = Layer(id = "layer1", name = "1", shapes = listOf(l1))
        val layer2 = Layer(id = "layer2", name = "2", shapes = listOf(l1))
        viewModel.setLayers(listOf(layer1, layer2))
        viewModel.onLayerActivated("layer2") // layer2 is topmost (last in list)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("layer2", viewModel.uiState.value.activeLayerId)

        // Double tap at center: hits = [layer2, layer1]. Active is index 0 (layer2). Next is layer1.
        viewModel.onCanvasDoubleTap(Offset(500f, 500f), 1000f, 1000f)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("layer1", viewModel.uiState.value.activeLayerId)

        // Double tap again at center: Active is index 1 (layer1). Next wraps to layer2.
        viewModel.onCanvasDoubleTap(Offset(500f, 500f), 1000f, 1000f)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("layer2", viewModel.uiState.value.activeLayerId)
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
}
