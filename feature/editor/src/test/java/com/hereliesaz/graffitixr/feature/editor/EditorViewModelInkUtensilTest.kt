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
import com.hereliesaz.graffitixr.common.model.Op
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
class EditorViewModelInkUtensilTest {

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

        val testDispatcherProvider = object : DispatcherProvider {
            override val main: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
            override val unconfined: CoroutineDispatcher = testDispatcher
        }

        viewModel = EditorViewModel(
            projectRepository, settingsRepository, projectManager, exportManager, context,
            slamManager, testDispatcherProvider, opEmitter, extensionRepository,
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
        unmockkStatic(Toast::class)
        unmockkObject(com.hereliesaz.graffitixr.common.util.ImageUtils)
        unmockkObject(TextRasterizer)
        unmockkObject(GoogleFontCache)
        unmockkObject(NativeLibLoader)
    }

    // ── Jetpack Ink utensils ─────────────────────────────────────────────────────────────────────

    /** A raster tool activates only once there's a layer to paint on (see setActiveTool). */
    private fun withLayer() {
        viewModel.onAddLayer(Uri.parse("content://test/image.png"))
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `no Ink utensil is in hand by default, so the Brush does not route through Ink`() {
        withLayer()
        assertNull(viewModel.activeInkUtensil.value)
        viewModel.setActiveTool(Tool.BRUSH)
        assertFalse(viewModel.usesJetpackInk())
    }

    @Test
    fun `the brush catalogue lists every Ink utensil`() {
        assertEquals(
            com.hereliesaz.graffitixr.common.model.InkUtensil.entries.toList(),
            viewModel.inkUtensils,
        )
    }

    @Test
    fun `selecting each Ink utensil routes the Brush through Ink with that family`() {
        withLayer()
        for (utensil in com.hereliesaz.graffitixr.common.model.InkUtensil.entries) {
            viewModel.selectInkUtensil(utensil)
            val state = viewModel.uiState.value
            assertEquals(utensil, viewModel.activeInkUtensil.value)
            assertEquals(Tool.BRUSH, state.activeTool)
            assertEquals(utensil.displayName, state.activeBrushName)
            assertTrue(viewModel.usesJetpackInk())
            // The stamp brush is out of hand, so the stamp pipeline has nothing to paint with.
            assertNull(viewModel.activeBrushForPreview())
        }
    }

    @Test
    fun `an Ink utensil only routes while the Brush tool is in hand`() {
        withLayer()
        viewModel.selectInkUtensil(com.hereliesaz.graffitixr.common.model.InkUtensil.MARKER)
        viewModel.setActiveTool(Tool.ERASER)
        assertFalse(viewModel.usesJetpackInk())
        viewModel.setActiveTool(Tool.BRUSH)
        assertTrue(viewModel.usesJetpackInk())
    }

    @Test
    fun `picking a built-in brush after an Ink utensil leaves Ink and restores the stamp brush`() {
        viewModel.selectInkUtensil(com.hereliesaz.graffitixr.common.model.InkUtensil.HIGHLIGHTER)
        val round = com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes.presets.first()
        viewModel.selectBuiltInBrush(round.name)

        assertNull(viewModel.activeInkUtensil.value)
        assertFalse(viewModel.usesJetpackInk())
        assertEquals(round.name, viewModel.uiState.value.activeBrushName)
        assertEquals(round, viewModel.activeBrushForPreview())
    }

    @Test
    fun `returning to the default brush via selectBrushExtension(null) leaves Ink`() {
        viewModel.selectInkUtensil(com.hereliesaz.graffitixr.common.model.InkUtensil.DASHED_LINE)
        viewModel.selectBrushExtension(null)
        assertNull(viewModel.activeInkUtensil.value)
        assertFalse(viewModel.usesJetpackInk())
    }

    @Test
    fun `the Ink utensil no longer depends on any settings toggle`() {
        // The retired Settings > Jetpack Ink toggle is not read: selection alone routes through Ink.
        withLayer()
        viewModel.selectInkUtensil(com.hereliesaz.graffitixr.common.model.InkUtensil.PEN)
        assertTrue(viewModel.usesJetpackInk())
    }

    @Test
    fun `a finished Ink stroke carries the utensil it started with, not the current selection`() {
        withLayer()
        viewModel.selectInkUtensil(com.hereliesaz.graffitixr.common.model.InkUtensil.PEN)
        // Switched after lift, before Ink's finished callback.
        viewModel.selectInkUtensil(com.hereliesaz.graffitixr.common.model.InkUtensil.HIGHLIGHTER)
        every { opEmitter.isActive } returns true
        val stroke = mockk<androidx.ink.strokes.Stroke>(relaxed = true)
        val layerId = viewModel.uiState.value.activeLayerId!!

        // Ink's input batch is native (no host-JVM library), so drive the commit past its read-out.
        viewModel.commitInkStroke(
            stroke, listOf(Offset(1f, 1f)), listOf(1f),
            com.hereliesaz.graffitixr.common.model.InkUtensil.PEN, IntSize(100, 100),
        ) {}

        val sent = mutableListOf<Op>()
        io.mockk.verify { opEmitter.emit(capture(sent)) }
        val complete = sent.filterIsInstance<Op.StrokeComplete>().single()
        assertEquals("ink.pen", complete.stroke.inkUtensilId)
        assertEquals(
            com.hereliesaz.graffitixr.common.model.InkUtensil.PEN,
            viewModel.inkLedgerStrokes(layerId).single().inkUtensil,
        )
    }

    @Test
    fun `a peer's reconstructed Ink stroke joins the Ink ledger the Figma SVG export reads`() {
        withLayer()
        val layerId = viewModel.uiState.value.activeLayerId!!
        val ink = com.hereliesaz.graffitixr.feature.editor.ink.InkStrokes
        mockkObject(ink)
        try {
            every { ink.brush(any(), any(), any(), any()) } returns mockk(relaxed = true)
            every { ink.strokeFromPoints(any(), any(), any()) } returns mockk(relaxed = true)
            viewModel.applySpectatorOp(
                Op.StrokeComplete(
                    layerId,
                    com.hereliesaz.graffitixr.common.model.BrushStroke(
                        points = listOf(1f, 1f, 5f, 5f),
                        pressures = listOf(1f, 1f),
                        inkUtensilId = "ink.marker",
                    ),
                ),
            )
            testDispatcher.scheduler.advanceUntilIdle()

            val recorded = viewModel.inkLedgerStrokes(layerId).single()
            assertEquals(com.hereliesaz.graffitixr.common.model.InkUtensil.MARKER, recorded.inkUtensil)
            assertNotNull(recorded.inkStroke)
        } finally {
            unmockkObject(ink)
        }
    }

    @Test
    fun `a peer stroke that falls back to the round brush stays out of the Ink ledger`() {
        withLayer()
        val layerId = viewModel.uiState.value.activeLayerId!!
        viewModel.applySpectatorOp(
            Op.StrokeComplete(
                layerId,
                com.hereliesaz.graffitixr.common.model.BrushStroke(points = listOf(1f, 1f, 5f, 5f)),
            ),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.inkLedgerStrokes(layerId).isEmpty())
    }
}
