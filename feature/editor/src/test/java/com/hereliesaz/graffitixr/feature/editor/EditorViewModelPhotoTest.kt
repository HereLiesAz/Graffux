package com.hereliesaz.graffitixr.feature.editor

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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
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
import org.junit.Assert.assertNotEquals
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
/**
 * Photo adjustment layers through the view-model: a slider gesture makes its tab's layer directly
 * above the selected one (once), sets it, is one undo step; Reset and compare; a paint tool never
 * lands on an adjustment layer.
 */
class EditorViewModelPhotoTest {

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
    private val customBrushRepository: com.hereliesaz.graffitixr.data.brush.CustomBrushRepository =
        mockk(relaxed = true)
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
        mockkObject(TextRasterizer)
        mockkObject(GoogleFontCache)

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
        unmockkObject(TextRasterizer)
        unmockkObject(GoogleFontCache)
        unmockkObject(NativeLibLoader)
    }

    private val light = com.hereliesaz.graffitixr.common.photo.PhotoTool.LIGHT
    private val exposure = com.hereliesaz.graffitixr.common.photo.PhotoSliders.EXPOSURE

    private fun twoLayers() {
        viewModel.onAddLayer(Uri.parse("content://test/a.png"))
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onAddLayer(Uri.parse("content://test/b.png"))
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun layers() = viewModel.uiState.value.layers

    @Test
    fun `a slider gesture makes the tab's layer directly above the selected layer, once`() {
        twoLayers()
        val (bottom, top) = layers().map { it.id }
        viewModel.onLayerActivated(bottom)
        viewModel.onPhotoGestureStart(light)
        viewModel.onPhotoSlider(light, exposure, 0.35f)
        viewModel.onPhotoSlider(light, exposure, 0.5f)
        viewModel.onPhotoGestureEnd()
        val ids = layers().map { it.id }
        assertEquals(3, ids.size)
        assertEquals(bottom, ids[0])
        assertEquals(top, ids[2])
        val adj = layers()[1]
        assertEquals(light, adj.adjustment?.tool)
        assertEquals("Light", adj.name)
        assertEquals(0.5f, adj.adjustment?.value(exposure))
        assertEquals(adj.id, viewModel.uiState.value.activeLayerId)
        // The next gesture on the same tab edits the same layer.
        viewModel.onPhotoGestureStart(light)
        viewModel.onPhotoSlider(light, exposure, 1f)
        viewModel.onPhotoGestureEnd()
        assertEquals(3, layers().size)
        assertEquals(1f, layers()[1].adjustment?.value(exposure))
    }

    @Test
    fun `each gesture is one undo step and the first one takes the layer with it`() {
        twoLayers()
        viewModel.onPhotoGestureStart(light)
        viewModel.onPhotoSlider(light, exposure, 0.35f)
        viewModel.onPhotoGestureEnd()
        viewModel.onPhotoGestureStart(light)
        viewModel.onPhotoSlider(light, exposure, 0.8f)
        viewModel.onPhotoGestureEnd()
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0.35f, layers().single { it.adjustment != null }.adjustment?.value(exposure))
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(layers().none { it.adjustment != null })
        viewModel.onRedoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0.35f, layers().single { it.adjustment != null }.adjustment?.value(exposure))
    }

    @Test
    fun `reset, slider reset and compare`() {
        twoLayers()
        viewModel.onPhotoGestureStart(light)
        viewModel.onPhotoSlider(light, exposure, 0.35f)
        viewModel.onPhotoSlider(light, com.hereliesaz.graffitixr.common.photo.PhotoSliders.CONTRAST, 20f)
        viewModel.onPhotoGestureEnd()
        viewModel.onPhotoSliderReset(light, exposure)
        val adj = layers().single { it.adjustment != null }.adjustment!!
        assertEquals(0f, adj.value(exposure))
        assertEquals(20f, adj.value(com.hereliesaz.graffitixr.common.photo.PhotoSliders.CONTRAST))
        viewModel.onPhotoReset(light)
        assertTrue(layers().single { it.adjustment != null }.adjustment!!.isNeutral)
        viewModel.setPhotoCompare(true)
        assertTrue(viewModel.uiState.value.photoCompare)
        viewModel.setPhotoCompare(false)
        assertFalse(viewModel.uiState.value.photoCompare)
    }

    @Test
    fun `a paint tool picked on an adjustment layer goes to the layer under it`() {
        twoLayers()
        val top = layers().last().id
        viewModel.onLayerActivated(top)
        viewModel.onPhotoGestureStart(light)
        viewModel.onPhotoSlider(light, exposure, 0.35f)
        viewModel.onPhotoGestureEnd()
        viewModel.setActiveTool(Tool.BRUSH)
        assertEquals(top, viewModel.uiState.value.activeLayerId)
        assertEquals(Tool.BRUSH, viewModel.uiState.value.activeTool)
    }

    @Test
    fun `crop changes are undoable document settings and survive with the layers`() {
        twoLayers()
        viewModel.applyCrop { it.copy(flipH = true) }
        viewModel.onCropGestureStart()
        viewModel.setDocumentCrop(viewModel.uiState.value.documentCrop.copy(straighten = -3f))
        viewModel.onCropGestureEnd()
        assertEquals(-3f, viewModel.uiState.value.documentCrop.straighten)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0f, viewModel.uiState.value.documentCrop.straighten)
        assertTrue(viewModel.uiState.value.documentCrop.flipH)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.documentCrop.flipH)
        viewModel.onRedoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.documentCrop.flipH)
        viewModel.setCropEditing(true)
        assertTrue(viewModel.uiState.value.cropEditing)
    }

    @Test
    fun `heal keeps every spot on one Heal layer, and red-eye on its own`() {
        twoLayers()
        viewModel.onHealSpot(100f, 120f, 20f, 0.4f, 1f, clone = false)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onHealSpot(300f, 320f, 20f, 0.4f, 1f, clone = true)
        testDispatcher.scheduler.advanceUntilIdle()
        val heals = layers().filter { it.adjustment?.tool == com.hereliesaz.graffitixr.common.photo.PhotoTool.HEAL }
        assertEquals(1, heals.size)
        val spots = heals.single().adjustment!!.healSpots
        assertEquals(listOf(100f, 300f), spots.map { it.x })
        assertEquals(1f, heals.single().adjustment!!.values[AdjustmentLayerRenderer.HEAL_CLONE_KEY])
        viewModel.onPhotoGestureStart(com.hereliesaz.graffitixr.common.photo.PhotoTool.HEAL)
        viewModel.moveHealSource(0, 50f, 60f)
        viewModel.onPhotoGestureEnd()
        val healed = layers().single { it.adjustment?.tool == com.hereliesaz.graffitixr.common.photo.PhotoTool.HEAL }
        assertEquals(50f, healed.adjustment!!.healSpots[0].sourceX)
        viewModel.onRedEyeSpot(10f, 10f, 5f)
        viewModel.onRedEyeSpot(30f, 10f, 5f)
        val red = layers().filter { it.adjustment?.tool == com.hereliesaz.graffitixr.common.photo.PhotoTool.RED_EYE }
        assertEquals(1, red.size)
        assertEquals(2, red.single().adjustment!!.redEyeSpots.size)
    }

    @Test
    fun `a Local stroke makes a mask revision that undo takes back`() {
        twoLayers()
        viewModel.onPhotoGestureStart(light)
        viewModel.onPhotoSlider(light, exposure, 0.5f)
        viewModel.onPhotoGestureEnd()
        val id = layers().single { it.adjustment != null }.id
        viewModel.onMaskStrokeStart(100f, 100f, 20f, 0.5f, 1f, erase = false)
        viewModel.onMaskStrokeTo(140f, 100f, 20f, 0.5f, 1f, erase = false)
        viewModel.onMaskStrokeEnd()
        val mask = layers().single { it.id == id }.adjustment!!.mask
        assertTrue(mask.enabled)
        assertEquals(1, mask.revision)
        val bytes = viewModel.adjustmentMasks.get(id, 1)!!
        // 1080 x 1920 world (the test display reports none): painted at (120, 100), empty far away.
        assertEquals(255, bytes[100 * 1080 + 120].toInt() and 0xFF)
        assertEquals(0, bytes[1000 * 1080 + 900].toInt() and 0xFF)
        viewModel.onMaskInvert()
        assertTrue(layers().single { it.id == id }.adjustment!!.mask.inverted)
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(layers().single { it.id == id }.adjustment!!.mask.enabled)
        viewModel.onRedoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, layers().single { it.id == id }.adjustment!!.mask.revision)
    }

    @Test
    fun `a Local stroke sends its mask pixels to peers, and a peer's mask is installed`() {
        twoLayers()
        viewModel.onPhotoGestureStart(light)
        viewModel.onPhotoSlider(light, exposure, 0.5f)
        viewModel.onPhotoGestureEnd()
        testDispatcher.scheduler.advanceUntilIdle()
        every { opEmitter.isActive } returns true
        val id = layers().single { it.adjustment != null }.id
        viewModel.onMaskStrokeStart(100f, 100f, 20f, 0.5f, 1f, erase = false)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onMaskStrokeTo(140f, 100f, 20f, 0.5f, 1f, erase = false)
        viewModel.onMaskStrokeEnd()
        testDispatcher.scheduler.advanceUntilIdle()
        val sent = mutableListOf<Op>()
        io.mockk.verify { opEmitter.emit(capture(sent)) }
        val data = sent.filterIsInstance<Op.AdjustmentMaskData>().last { it.layerId == id }
        assertEquals(1, data.revision)
        val painted = CoopMaskCodec.inflate(data.deflated, data.width * data.height)!!
        assertTrue(painted.contentEquals(viewModel.adjustmentMasks.get(id, 1)!!))

        // The other side: a peer's revision 7 lands in this device's store.
        val theirs = ByteArray(data.width * data.height) { 9 }
        viewModel.applySpectatorOp(
            Op.AdjustmentMaskData(id, 7, data.width, data.height, CoopMaskCodec.deflate(theirs)),
        )
        assertTrue(theirs.contentEquals(viewModel.adjustmentMasks.get(id, 7)!!))
    }

    @Test
    fun `a peer's text formatting reaches the text layer and redraws it`() {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.dispatchForTest(
            EditorIntent.AppendLayer(Layer(id = "t", name = "t", textParams = TextLayerParams(text = "hi"))),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        val styled = TextLayerParams(text = "hi", fontName = "Lobster", fontSizeDp = 90f, isItalic = true)
        val peerLayer = layers().single { it.id == "t" }.copy(textParams = styled)
        viewModel.applySpectatorOp(
            Op.LayerPropsChange("t", peerLayer.toLayerProps()),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(styled, layers().single { it.id == "t" }.textParams)
        io.mockk.verify { TextRasterizer.rasterize(styled, any(), any(), any(), any()) }
    }
}
