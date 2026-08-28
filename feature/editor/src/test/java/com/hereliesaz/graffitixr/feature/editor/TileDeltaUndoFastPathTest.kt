package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Item 16's undo fast path: a stamp-brush stroke commit captures a tile delta
 * ([EditorViewModel.commitStampStroke]) and attaches it to its own undo-stack entry
 * ([EditHistory.attachTileDeltas]), so [EditorViewModel.onUndoClicked]/[onRedoClicked] can patch
 * the layer's current bitmap directly ([EditorViewModel.applyTileDeltaFastPath]) instead of
 * replaying every stroke since the layer's base. This drives the real [EditorViewModel] with real
 * [Bitmap]/[android.graphics.Canvas] (Robolectric NATIVE graphics) end-to-end: commit, confirm the
 * fast path actually attached (not just that undo/redo produced correct pixels, which the slow
 * full-replay path would also do), then undo and redo and check the pixels are exactly right both
 * times, including round-tripping back to a *second* stroke's committed state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TileDeltaUndoFastPathTest {

    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val canvasSize = IntSize(48, 48)

    private val brush = AzphaltBrush(name = "Solid", hardness = 1f, opacity = 1f)

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
            every { load("solid") } returns brush
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

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    private fun currentLayerBitmap(): Bitmap = vm.uiState.value.layers.first { it.id == "L" }.bitmap!!

    private fun drawStroke(x: Float, y: Float) {
        vm.onStrokeStart(BrushSample(x, y, uptimeMillis = 0L), canvasSize)
        vm.onStrokePoint(BrushSample(x + 6f, y, uptimeMillis = 16L))
        vm.onStrokeEnd()
    }

    @Test
    fun `undo and redo of a stamp stroke round trip exactly via the tile-delta fast path`() = runTest {
        val original = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
        val originalPixels = pixelsOf(original)
        val layer = Layer(id = "L", name = "Layer", bitmap = original)
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(layer)))
        vm.onLayerActivated("L")
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.selectCustomBrush("solid")
        vm.setActiveTool(Tool.BRUSH)

        drawStroke(12f, 12f)
        val afterFirstStroke = pixelsOf(currentLayerBitmap())
        assertTrue(
            "the first stroke should have actually painted something",
            !afterFirstStroke.contentEquals(originalPixels),
        )
        assertTrue(
            "expected the fast path to attach a non-empty tile delta to the first stroke",
            (vm.topUndoTileDeltaCountForTest() ?: 0) > 0,
        )

        drawStroke(30f, 30f)
        val afterSecondStroke = pixelsOf(currentLayerBitmap())
        assertTrue(
            "the second stroke should have painted somewhere the first one didn't",
            !afterSecondStroke.contentEquals(afterFirstStroke),
        )
        assertTrue(
            "expected the fast path to attach a non-empty tile delta to the second stroke",
            (vm.topUndoTileDeltaCountForTest() ?: 0) > 0,
        )

        // Undo the second stroke -> back to exactly the first stroke's committed pixels.
        vm.onUndoClicked()
        assertArrayEquals(afterFirstStroke, pixelsOf(currentLayerBitmap()))

        // Undo the first stroke -> back to exactly the original, untouched pixels.
        vm.onUndoClicked()
        assertArrayEquals(originalPixels, pixelsOf(currentLayerBitmap()))

        // Redo both -> forward through the same two states again, exactly.
        vm.onRedoClicked()
        assertArrayEquals(afterFirstStroke, pixelsOf(currentLayerBitmap()))
        vm.onRedoClicked()
        assertArrayEquals(afterSecondStroke, pixelsOf(currentLayerBitmap()))
    }
}
