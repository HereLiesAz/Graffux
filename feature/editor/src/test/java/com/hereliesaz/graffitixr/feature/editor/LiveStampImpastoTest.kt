package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Item 12's live-preview follow-up: Impasto shading must be visible while dragging, not just once
 * a stroke commits (see [EditorViewModel.stampLiveHeightMap]'s doc comment for the regional-
 * reshade design this requires to stay cheap). This drives the real [EditorViewModel] with a real
 * [Bitmap]/[android.graphics.Canvas] (Robolectric NATIVE graphics), exercising the actual live
 * integration — [ImpastoEngineTest] covers the primitive (including [ImpastoEngine.shadeInto]
 * itself), and [DrawingEngineImpastoTest] covers the commit path; neither reaches this.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LiveStampImpastoTest {

    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val canvasSize = IntSize(64, 64)

    private val impastoBrush = AzphaltBrush(
        name = "Impasto",
        hardness = 1f,
        opacity = 1f,
        impastoThicknessRate = 0.9f,
    )
    private val noImpasto = impastoBrush.copy(impastoThicknessRate = 0f)

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
            every { load("impasto") } returns impastoBrush
            every { load("no-impasto") } returns noImpasto
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

    private fun dragStrokeAndSnapshotLivePixels(brushSelectorId: String): IntArray {
        val base = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.WHITE)
        val layer = Layer(id = "L", name = "Layer", bitmap = base)
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(layer)))
        vm.onLayerActivated("L")
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.selectCustomBrush(brushSelectorId)
        vm.setActiveTool(Tool.BRUSH)

        // A short dragged path (not a single tap) so the stroke has real height variation to
        // shade against, not just one isolated circular bump.
        vm.onStrokeStart(BrushSample(20f, 32f, uptimeMillis = 0L), canvasSize)
        vm.onStrokePoint(BrushSample(28f, 32f, uptimeMillis = 16L))
        vm.onStrokePoint(BrushSample(36f, 32f, uptimeMillis = 32L))
        vm.onStrokePoint(BrushSample(44f, 32f, uptimeMillis = 48L))

        val live = vm.uiState.value.liveStrokeBitmap!!
        val pixels = IntArray(canvasSize.width * canvasSize.height)
        live.getPixels(pixels, 0, canvasSize.width, 0, 0, canvasSize.width, canvasSize.height)
        vm.onStrokeEnd()
        return pixels
    }

    @Test
    fun `impasto shading is visible in the live preview while dragging, not only after commit`() {
        val withImpasto = dragStrokeAndSnapshotLivePixels("impasto")
        val without = dragStrokeAndSnapshotLivePixels("no-impasto")

        assertTrue(
            "expected Impasto shading to change at least some live-preview pixels relative to the flat stroke",
            !withImpasto.contentEquals(without),
        )
    }
}
