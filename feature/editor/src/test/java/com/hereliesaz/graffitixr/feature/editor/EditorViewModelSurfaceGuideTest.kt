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
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.SurfaceDrawNotice
import com.hereliesaz.graffitixr.common.model.surfaceDrawArmed
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.scene.GuideEnd
import com.hereliesaz.graffitixr.common.scene.GuideStepState
import com.hereliesaz.graffitixr.common.scene.GuideTool
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SurfaceGuideReason
import com.hereliesaz.graffitixr.common.scene.SurfaceView
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
 * The guided surface flow (spec/guided-surface.md), phase 1: when it is offered, the strict step dependency, drafts
 * held until Done, one undo step per committed surface, and Cancel changing nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelSurfaceGuideTest {

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
        // The project flow's first emission lands before any test sets its own layers.
        testDispatcher.scheduler.advanceUntilIdle()
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


    private fun state() = viewModel.uiState.value
    private fun layers() = state().layers
    private fun settle() = testDispatcher.scheduler.advanceUntilIdle()

    private fun plane(id: String, parent: String? = null) = Layer(id = id, name = id, parentId = parent)
    private fun group(id: String) = Layer(id = id, name = id, type = LayerType.GROUP)

    private fun open(vararg all: Layer, active: String = all.first().id) {
        viewModel.onCanvasSizeChanged(IntSize(1000, 1600))
        viewModel.setLayers(all.toList())
        viewModel.onLayerActivated(active)
        settle()
        viewModel.onOpenSurfaceGuide()
        settle()
    }

    private fun circle(cx: Float = 500f, cy: Float = 800f, r: Float = 200f) = (0..32).map {
        val a = 2.0 * Math.PI * it / 32
        Offset(cx + (r * Math.cos(a)).toFloat(), cy + (r * Math.sin(a)).toFloat())
    }

    private fun box(w: Float = 300f, h: Float = 200f) = listOf(
        Offset(500f - w, 800f - h), Offset(500f + w, 800f - h), Offset(500f + w, 800f + h),
        Offset(500f - w, 800f + h), Offset(500f - w, 800f - h),
    )

    private fun line() = listOf(Offset(300f, 800f), Offset(700f, 900f))

    /** Draw on the current step and let the capture land. */
    private fun drawStep(stroke: List<Offset>) {
        viewModel.onSurfaceGuideDraw()
        assertTrue(state().surfaceGuide!!.capturing)
        viewModel.onSurfaceProfileDrawn(stroke)
        settle()
    }

    private fun surfaceOf(id: String) = layers().first { it.id == id }.surface

    // ── When it is offered (rule 1) ──

    @Test
    fun `an empty layer opens the guide on the camera's perspective and arms nothing`() {
        open(plane("a"))
        val guide = state().surfaceGuide
        assertNotNull(guide)
        assertEquals(1, guide!!.step)
        assertEquals(SurfaceView.FRONT, guide.currentView)
        assertTrue(guide.drafts.isEmpty())
        assertEquals(false, guide.capturing)
        assertNull(state().surfaceGuideReason)
        assertEquals(false, state().surfaceDrawArmed)
        assertEquals(SceneCamera(), state().sceneCamera)
        assertNull(layers().single().surface)
    }

    @Test
    fun `the guide opens on the perspective the camera shows and turns the camera there`() {
        viewModel.onCanvasSizeChanged(IntSize(1000, 1600))
        viewModel.setSceneCameraForTest(SceneCamera(yaw = (Math.PI / 2).toFloat()))
        open(plane("a"))
        assertEquals(SurfaceView.SIDE, state().surfaceGuide!!.currentView)
        assertEquals(SurfaceView.SIDE.camera(SceneCamera(yaw = (Math.PI / 2).toFloat())), state().sceneCamera)
    }

    @Test
    fun `a project with content is not offered the guide and nothing changes`() {
        val withArt = plane("art").copy(uri = Uri.parse("content://x/photo.png"))
        val before = listOf(plane("a"), withArt)
        viewModel.onCanvasSizeChanged(IntSize(1000, 1600))
        viewModel.setLayers(before)
        viewModel.onLayerActivated("a")
        settle()
        val undo = state().undoCount
        viewModel.onOpenSurfaceGuide()
        settle()
        assertNull(state().surfaceGuide)
        assertEquals(SurfaceGuideReason.CONTENT, state().surfaceGuideReason)
        assertEquals(before, layers())
        assertEquals(undo, state().undoCount)
    }

    @Test
    fun `content in a hidden, locked or nested layer counts`() {
        val hidden = plane("art", "g").copy(uri = Uri.parse("content://x/photo.png"), isVisible = false, isImageLocked = true)
        open(group("g"), plane("a"), hidden, active = "a")
        assertNull(state().surfaceGuide)
        assertEquals(SurfaceGuideReason.CONTENT, state().surfaceGuideReason)
    }

    @Test
    fun `no layer, no canvas and a locked target each say why`() {
        viewModel.onOpenSurfaceGuide()
        assertEquals(SurfaceGuideReason.UNAVAILABLE, state().surfaceGuideReason)
        viewModel.onCanvasSizeChanged(IntSize(1000, 1600))
        viewModel.onOpenSurfaceGuide()
        settle()
        assertEquals(SurfaceGuideReason.NO_LAYER, state().surfaceGuideReason)
        viewModel.setLayers(listOf(plane("a").copy(isImageLocked = true)))
        viewModel.onLayerActivated("a")
        settle()
        viewModel.onOpenSurfaceGuide()
        assertEquals(SurfaceGuideReason.LOCKED, state().surfaceGuideReason)
        assertNull(state().surfaceGuide)
    }

    private fun bitmapOf(painted: Boolean): Bitmap = mockk<Bitmap>(relaxed = true).also { b ->
        every { b.width } returns 4
        every { b.height } returns 4
        every { b.isRecycled } returns false
        every { b.getPixels(any(), any(), any(), any(), any(), any(), any()) } answers {
            (firstArg<IntArray>()).fill(if (painted) 0xFF000000.toInt() else 0)
        }
    }

    @Test
    fun `a pixel scan runs off the main thread, says checking meanwhile and then decides`() {
        viewModel.warmScans = false
        viewModel.onCanvasSizeChanged(IntSize(1000, 1600))
        viewModel.setLayers(listOf(plane("a").copy(bitmap = bitmapOf(painted = true))))
        viewModel.onLayerActivated("a")
        settle()
        viewModel.onOpenSurfaceGuide()
        // Nothing has been scanned yet: the guide is not offered and says it is checking.
        assertEquals(SurfaceGuideReason.CHECKING, state().surfaceGuideReason)
        assertNull(state().surfaceGuide)
        settle()
        assertEquals(SurfaceGuideReason.CONTENT, state().surfaceGuideReason)
        assertNull(state().surfaceGuide)

        viewModel.setLayers(listOf(plane("b").copy(bitmap = bitmapOf(painted = false))))
        viewModel.onLayerActivated("b")
        settle()
        viewModel.onOpenSurfaceGuide()
        assertEquals(SurfaceGuideReason.CHECKING, state().surfaceGuideReason)
        settle()
        assertNotNull(state().surfaceGuide)
        assertNull(state().surfaceGuideReason)
    }

    @Test
    fun `a blank sketch layer with its file uri, as the app makes it, is empty to the guide`() {
        viewModel.onCanvasSizeChanged(IntSize(1000, 1600))
        val app = plane("b").copy(isSketch = true, uri = Uri.parse("file:///layer_b.png"), bitmap = bitmapOf(painted = false))
        viewModel.setLayers(listOf(app))
        viewModel.onLayerActivated("b")
        settle()
        viewModel.onOpenSurfaceGuide()
        settle()
        assertNotNull("offered on a real new project", state().surfaceGuide)
        assertNull(state().surfaceGuideReason)
    }

    @Test
    fun `a painted sketch layer with its file uri is content to the guide`() {
        viewModel.onCanvasSizeChanged(IntSize(1000, 1600))
        val app = plane("b").copy(isSketch = true, uri = Uri.parse("file:///layer_b.png"), bitmap = bitmapOf(painted = true))
        viewModel.setLayers(listOf(app))
        viewModel.onLayerActivated("b")
        settle()
        viewModel.onOpenSurfaceGuide()
        settle()
        assertEquals(SurfaceGuideReason.CONTENT, state().surfaceGuideReason)
    }

    @Test
    fun `a scan that cannot read the bitmap is never counted as empty`() {
        viewModel.warmScans = false
        val broken = bitmapOf(painted = false)
        every { broken.getPixels(any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("recycled")
        viewModel.onCanvasSizeChanged(IntSize(1000, 1600))
        viewModel.setLayers(listOf(plane("a").copy(bitmap = broken)))
        viewModel.onLayerActivated("a")
        settle()
        viewModel.onOpenSurfaceGuide()
        settle()
        assertNull(state().surfaceGuide)
        assertEquals(SurfaceGuideReason.UNREADABLE, state().surfaceGuideReason)
    }

    @Test
    fun `a scan answer for layers that changed meanwhile is dropped`() {
        viewModel.warmScans = false
        viewModel.onCanvasSizeChanged(IntSize(1000, 1600))
        viewModel.setLayers(listOf(plane("a").copy(bitmap = bitmapOf(painted = false))))
        viewModel.onLayerActivated("a")
        settle()
        viewModel.onOpenSurfaceGuide()
        assertEquals(SurfaceGuideReason.CHECKING, state().surfaceGuideReason)
        viewModel.setLayers(listOf(plane("a").copy(bitmap = bitmapOf(painted = false)), plane("late")))
        settle()
        assertNull(state().surfaceGuide)
        assertNull(state().surfaceGuideReason)
    }

    @Test
    fun `content that appears while the guide is open closes it as a cancel with nothing changed`() {
        open(plane("a"))
        assertNotNull(state().surfaceGuide)
        viewModel.setLayers(layers() + plane("peer").copy(uri = Uri.parse("content://x/photo.png")))
        settle()
        assertNull(state().surfaceGuide)
        assertEquals(SurfaceGuideReason.CONTENT, state().surfaceGuideReason)
        assertEquals(GuideEnd.CANCELLED, state().surfaceGuideEnd)
        assertEquals(SceneCamera(), state().sceneCamera)
    }

    @Test
    fun `the target vanishing closes the guide`() {
        open(plane("a"), plane("b"))
        viewModel.setLayers(listOf(plane("b")))
        settle()
        assertNull(state().surfaceGuide)
    }

    // ── The steps (rule 2) ──

    @Test
    fun `skip on the first step ends the flow, creating nothing and leaving everything as it was`() {
        open(plane("a"))
        val layersBefore = layers()
        val undo = state().undoCount
        viewModel.onSurfaceGuideSkip()
        assertNull(state().surfaceGuide)
        assertEquals(GuideEnd.NOTHING, state().surfaceGuideEnd)
        assertEquals(layersBefore, layers())
        assertEquals(undo, state().undoCount)
        assertEquals(SceneCamera(), state().sceneCamera)
    }

    @Test
    fun `the second step is presented only after a first exists and next is disabled on an empty step`() {
        open(plane("a"))
        viewModel.onSurfaceGuideNext()
        assertEquals(1, state().surfaceGuide!!.step)
        viewModel.onSurfaceGuideKeep()
        assertEquals(1, state().surfaceGuide!!.step)
        drawStep(circle())
        assertEquals(GuideStepState.DRAFT, state().surfaceGuide!!.stateOf(1))
        assertNull("a draft is not the document", layers().single().surface)
        viewModel.onSurfaceGuideNext()
        assertEquals(2, state().surfaceGuide!!.step)
        // The camera turned to the second perspective for the step.
        assertEquals(SurfaceView.SIDE, state().surfaceGuide!!.currentView)
        assertEquals(SurfaceView.SIDE.camera(SceneCamera()), state().sceneCamera)
        // The third is not presented until the second has a surface.
        viewModel.onSurfaceGuideNext()
        assertEquals(2, state().surfaceGuide!!.step)
        viewModel.onSurfaceGuideBack()
        assertEquals(1, state().surfaceGuide!!.step)
        assertEquals(SurfaceView.FRONT.camera(SceneCamera()), state().sceneCamera)
    }

    @Test
    fun `done commits a closed first surface as a solid wrapped in a group, one undo step, and cancel-free`() {
        open(plane("a"))
        drawStep(circle())
        val undo = state().undoCount
        assertNull(layers().single().surface)
        viewModel.onSurfaceGuideDone()
        settle()
        assertNull(state().surfaceGuide)
        assertEquals(GuideEnd.COMMIT, state().surfaceGuideEnd)
        val group = layers().single { it.type == LayerType.GROUP }
        assertEquals("a", layers().single { it.id == "a" }.id)
        assertEquals(group.id, layers().single { it.id == "a" }.parentId)
        assertTrue(group.surface!!.solid)
        assertTrue(group.surface!!.hasHull)
        assertEquals(undo + 1, state().undoCount)
        assertEquals(SceneCamera(), state().sceneCamera)
        assertEquals(SurfaceDrawNotice.DONE, state().surfaceDrawNotice)
        // One undo takes the group and its surface away together.
        viewModel.onUndoClicked()
        settle()
        assertEquals(listOf("a"), layers().map { it.id })
        assertNull(layers().single().parentId)
    }

    @Test
    fun `an open first surface is today's swept surface, not a solid`() {
        open(group("g"), plane("c", "g"), active = "g")
        viewModel.onSurfaceGuideChooseTool(GuideTool.LINE)
        drawStep(line())
        viewModel.onSurfaceGuideDone()
        settle()
        val surface = surfaceOf("g")!!
        assertEquals(false, surface.solid)
        assertEquals(false, surface.hasHull)
        assertEquals(1, state().undoCount - 0)
    }

    @Test
    fun `three surfaces commit as three undo steps, undone one at a time`() {
        open(group("g"), plane("c", "g"), active = "g")
        val undo = state().undoCount
        drawStep(circle())
        viewModel.onSurfaceGuideKeep()
        drawStep(box())
        viewModel.onSurfaceGuideKeep()
        drawStep(box(200f, 150f))
        assertEquals(3, state().surfaceGuide!!.step)
        viewModel.onSurfaceGuideDone()
        settle()
        assertEquals(undo + 3, state().undoCount)
        val full = surfaceOf("g")!!
        assertEquals(listOf(SurfaceView.FRONT, SurfaceView.SIDE, SurfaceView.TOP), full.drawnViews)
        viewModel.onUndoClicked()
        settle()
        assertEquals(listOf(SurfaceView.FRONT, SurfaceView.SIDE), surfaceOf("g")!!.drawnViews)
        viewModel.onUndoClicked()
        settle()
        assertEquals(listOf(SurfaceView.FRONT), surfaceOf("g")!!.drawnViews)
        viewModel.onUndoClicked()
        settle()
        assertNull(surfaceOf("g"))
        viewModel.onRedoClicked()
        settle()
        assertEquals(listOf(SurfaceView.FRONT), surfaceOf("g")!!.drawnViews)
    }

    @Test
    fun `skip on the second step commits the first only and offers no third`() {
        open(group("g"), plane("c", "g"), active = "g")
        val undo = state().undoCount
        drawStep(circle())
        viewModel.onSurfaceGuideKeep()
        viewModel.onSurfaceGuideSkip()
        settle()
        assertNull(state().surfaceGuide)
        assertEquals(undo + 1, state().undoCount)
        assertEquals(listOf(SurfaceView.FRONT), surfaceOf("g")!!.drawnViews)
    }

    @Test
    fun `skip on the third step commits the second mirrored as a real third`() {
        open(group("g"), plane("c", "g"), active = "g")
        val undo = state().undoCount
        drawStep(circle())
        viewModel.onSurfaceGuideKeep()
        drawStep(box())
        viewModel.onSurfaceGuideKeep()
        viewModel.onSurfaceGuideSkip()
        settle()
        assertEquals(undo + 3, state().undoCount)
        val surface = surfaceOf("g")!!
        assertEquals(listOf(SurfaceView.FRONT, SurfaceView.SIDE, SurfaceView.TOP), surface.drawnViews)
        assertNotNull(surface.top)
        assertEquals(1f, surface.top!!.sweepY, 1e-4f)
    }

    @Test
    fun `any first perspective makes that perspective the base`() {
        for (first in listOf(SurfaceView.SIDE, SurfaceView.TOP)) {
            open(group("g"), plane("c", "g"), active = "g")
            viewModel.onSurfaceGuideChooseFirstView(first)
            assertEquals(first, state().surfaceGuide!!.currentView)
            drawStep(circle())
            viewModel.onSurfaceGuideDone()
            settle()
            val surface = surfaceOf("g")!!
            assertEquals(first, surface.baseView)
            assertTrue(surface.solid)
            viewModel.onFlattenSurface("g")
            settle()
        }
    }

    @Test
    fun `the first surface on a group with no surface can be the flat sheet, then a second bounds it`() {
        open(group("g"), plane("c", "g"), active = "g")
        viewModel.onSurfaceGuideFlatSheet()
        val sheet = state().surfaceGuide!!.drafts.getValue(SurfaceView.FRONT)
        assertTrue(sheet.flatSheet)
        assertNull(surfaceOf("g"))
        viewModel.onSurfaceGuideKeep()
        drawStep(box())
        viewModel.onSurfaceGuideDone()
        settle()
        val surface = surfaceOf("g")!!
        assertEquals(false, surface.solid)
        assertNotNull(surface.reach)
        assertNotNull(surface.side)
    }

    // ── Cancel, drafts and the tools ──

    @Test
    fun `cancel from every state changes nothing in the document, the camera, the history or the tools`() {
        val actions = listOf<() -> Unit>(
            {},
            { drawStep(circle()) },
            { drawStep(circle()); viewModel.onSurfaceGuideKeep() },
            { drawStep(circle()); viewModel.onSurfaceGuideKeep(); drawStep(box()) },
            { viewModel.onSurfaceGuideDraw() },
        )
        for (action in actions) {
            viewModel.onCanvasSizeChanged(IntSize(1000, 1600))
            val start = listOf(group("g"), plane("c", "g"))
            viewModel.setLayers(start)
            viewModel.onLayerActivated("g")
            settle()
            val layers = layers()
            val camera = state().sceneCamera
            val undo = state().undoCount
            val redo = state().redoCount
            val tool = state().activeTool
            val mode = state().shapeToolMode
            viewModel.onOpenSurfaceGuide()
            settle()
            action()
            viewModel.onSurfaceGuideCancel()
            settle()
            assertNull(state().surfaceGuide)
            assertEquals(GuideEnd.CANCELLED, state().surfaceGuideEnd)
            assertEquals(layers, layers())
            assertEquals(camera, state().sceneCamera)
            assertEquals(undo, state().undoCount)
            assertEquals(redo, state().redoCount)
            assertEquals(tool, state().activeTool)
            assertEquals(mode, state().shapeToolMode)
            assertNull(state().surfaceReturnCamera)
            assertEquals(false, state().surfaceDrawArmed)
        }
    }

    @Test
    fun `done with nothing drawn changes nothing, like cancel`() {
        open(plane("a"))
        val before = layers()
        viewModel.onSurfaceGuideDone()
        assertNull(state().surfaceGuide)
        assertEquals(GuideEnd.NOTHING, state().surfaceGuideEnd)
        assertEquals(before, layers())
    }

    @Test
    fun `the guide holds its own tool, vector first, and never writes the app's tool or shape mode`() {
        open(plane("a"))
        assertEquals(GuideTool.ELLIPSE, state().surfaceGuide!!.tool)
        val tool = state().activeTool
        val mode = state().shapeToolMode
        viewModel.onSurfaceGuideChooseTool(GuideTool.PEN)
        drawStep(circle())
        viewModel.onSurfaceGuideKeep()
        assertEquals("sticky across steps", GuideTool.PEN, state().surfaceGuide!!.tool)
        viewModel.onSurfaceGuideDone()
        settle()
        assertEquals(tool, state().activeTool)
        assertEquals(mode, state().shapeToolMode)
        assertTrue(state().activeTool != Tool.PEN)
    }

    @Test
    fun `a tap too short to be a curve changes nothing and the step stays armed`() {
        open(plane("a"))
        viewModel.onSurfaceGuideDraw()
        viewModel.onSurfaceProfileDrawn(listOf(Offset(10f, 10f)))
        settle()
        val guide = state().surfaceGuide!!
        assertTrue(guide.capturing)
        assertTrue(guide.drafts.isEmpty())
        assertEquals(SurfaceDrawNotice.TOO_SHORT, state().surfaceDrawNotice)
    }

    @Test
    fun `back out of an armed stroke cancels the stroke and keeps the guide`() {
        open(plane("a"))
        viewModel.onSurfaceGuideDraw()
        viewModel.onCancelSurfaceDraw()
        assertNotNull(state().surfaceGuide)
        assertEquals(false, state().surfaceGuide!!.capturing)
    }

    @Test
    fun `the old arming entry points do nothing while the guide is open`() {
        open(group("g"), plane("c", "g"), active = "g")
        viewModel.onArmSurfaceDraw("g")
        viewModel.onArmSurfaceView("g", SurfaceView.SIDE)
        assertNull(state().surfaceDrawGroupId)
        assertNotNull(state().surfaceGuide)
    }

    @Test
    fun `an existing group's surface starts as drawn and Edit replaces only its own perspective`() {
        open(group("g"), plane("c", "g"), active = "g")
        drawStep(circle())
        viewModel.onSurfaceGuideKeep()
        drawStep(box())
        viewModel.onSurfaceGuideDone()
        settle()
        val before = surfaceOf("g")!!
        val undo = state().undoCount
        viewModel.onOpenSurfaceGuide()
        settle()
        val guide = state().surfaceGuide!!
        assertEquals(GuideStepState.EXISTING, guide.stateOf(1))
        assertEquals(GuideStepState.EXISTING, guide.stateOf(2))
        assertEquals(GuideStepState.NOT_DRAWN, guide.stateOf(3))
        // Keep the first, edit the second.
        viewModel.onSurfaceGuideKeep()
        drawStep(box(150f, 100f))
        viewModel.onSurfaceGuideDone()
        settle()
        val after = surfaceOf("g")!!
        assertEquals(before.points, after.points)
        assertTrue(before.side != after.side)
        assertEquals(undo + 1, state().undoCount)
    }
}
