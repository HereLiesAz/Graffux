package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import com.hereliesaz.graffitixr.data.brush.CustomBrushRepository
import com.hereliesaz.graffitixr.data.figma.FigmaRepository
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Blur/Sharpen/Smudge previously had NO live preview at all: buildStrokePaint's RESAMPLING_TOOLS
 * branch built a fully transparent Paint, so nothing rendered while dragging -- the whole effect
 * only appeared once the stroke committed on finger-up. This drives the real [EditorViewModel]
 * with real [Bitmap]/[Canvas] (Robolectric NATIVE graphics) and checks that dragging alone --
 * before onStrokeEnd is ever called -- already changes what [EditorViewModel.liveStroke] shows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LiveResampleToolsTest {

    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val canvasSize = IntSize(64, 64)

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
        val projects = mockk<ProjectRepository>(relaxed = true) {
            every { currentProject } returns MutableStateFlow(null)
            every { this@mockk.projects } returns MutableStateFlow(emptyList())
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
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val io = dispatcher
            override val default = dispatcher
            override val unconfined = dispatcher
        }

        vm = EditorViewModel(
            projectRepository = projects,
            settingsRepository = settings,
            projectManager = mockk(relaxed = true),
            exportManager = mockk(relaxed = true),
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            slamManager = mockk(relaxed = true),
            dispatchers = dispatchers,
            opEmitter = mockk(relaxed = true),
            extensionRepository = extensions,
            repositoryApiClient = mockk(relaxed = true),
            customBrushRepository = brushes,
            figmaRepository = figma,
            projectFileScanner = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /**
     * Left half a mid-tone orange, right half a mid-tone teal -- a hard edge for Blur/Sharpen/
     * Smudge to visibly act on. Deliberately NOT fully-saturated colours (0/255 in every channel):
     * Sharpen's unsharp mask pushes a pixel further away from its own local blur, and a channel
     * already sitting at 0 or 255 has nowhere left to move once that push is clamped back into
     * range -- exactly the degenerate case a pure-red/pure-blue split would be, and the one that
     * initially made this test wrongly look like Sharpen's live preview did nothing at all.
     */
    private fun splitColorLayer(): Bitmap {
        val bmp = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.rgb(200, 120, 40))
        val canvas = Canvas(bmp)
        canvas.drawRect(
            canvasSize.width / 2f, 0f, canvasSize.width.toFloat(), canvasSize.height.toFloat(),
            Paint().apply { color = Color.rgb(40, 120, 200) },
        )
        return bmp
    }

    private fun dragAcrossTheEdge(tool: Tool): IntArray {
        val base = splitColorLayer()
        val layer = Layer(id = "L", name = "Layer", bitmap = base)
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(layer)))
        vm.onLayerActivated("L")
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.setActiveTool(tool)
        vm.setBrushSize(24f)

        // Dragged across the red/blue boundary at the canvas's own vertical centre (canvasSize ==
        // the bitmap's own size here, so screen and bitmap space coincide 1:1).
        vm.onStrokeStart(BrushSample(16f, 32f, uptimeMillis = 0L), canvasSize)
        vm.onStrokePoint(BrushSample(24f, 32f, uptimeMillis = 16L))
        vm.onStrokePoint(BrushSample(32f, 32f, uptimeMillis = 32L))
        vm.onStrokePoint(BrushSample(40f, 32f, uptimeMillis = 48L))
        vm.onStrokePoint(BrushSample(48f, 32f, uptimeMillis = 64L))

        // The live bitmap, read BEFORE onStrokeEnd is ever called -- this is what the user sees
        // while their finger is still down.
        val live = vm.liveStroke.value.bitmap!!
        val pixels = IntArray(canvasSize.width * canvasSize.height)
        live.getPixels(pixels, 0, canvasSize.width, 0, 0, canvasSize.width, canvasSize.height)

        vm.onStrokeCancel()
        return pixels
    }

    @Test
    fun `blur is visible while dragging, before the stroke ever commits`() {
        val original = IntArray(canvasSize.width * canvasSize.height)
        splitColorLayer().getPixels(original, 0, canvasSize.width, 0, 0, canvasSize.width, canvasSize.height)

        val live = dragAcrossTheEdge(Tool.BLUR)

        assertTrue(
            "expected Blur to change at least some live-preview pixels while dragging",
            !live.contentEquals(original),
        )
    }

    @Test
    fun `sharpen is visible while dragging, before the stroke ever commits`() {
        val original = IntArray(canvasSize.width * canvasSize.height)
        splitColorLayer().getPixels(original, 0, canvasSize.width, 0, 0, canvasSize.width, canvasSize.height)

        val live = dragAcrossTheEdge(Tool.SHARPEN)

        assertTrue(
            "expected Sharpen to change at least some live-preview pixels while dragging",
            !live.contentEquals(original),
        )
    }

    @Test
    fun `color smudge is visible while dragging, before the stroke ever commits`() {
        val original = IntArray(canvasSize.width * canvasSize.height)
        splitColorLayer().getPixels(original, 0, canvasSize.width, 0, 0, canvasSize.width, canvasSize.height)

        val live = dragAcrossTheEdge(Tool.SMUDGE)

        assertTrue(
            "expected Color Smudge to change at least some live-preview pixels while dragging",
            !live.contentEquals(original),
        )
    }
}
