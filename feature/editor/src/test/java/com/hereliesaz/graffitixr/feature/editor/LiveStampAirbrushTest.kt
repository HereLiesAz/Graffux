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
 * Item 13's "risk 1" — the live-preview's incremental repaint bookkeeping never showed airbrush
 * held-run build-up while dragging, only once the stroke committed — is now resolved for the live
 * preview itself (see [EditorViewModel.stampHeldStampedCount]'s doc comment for the incremental
 * design and its one remaining documented residual gap). This drives the real ViewModel with a
 * real [Bitmap]/[android.graphics.Canvas] (Robolectric NATIVE graphics), not [DrawingEngine]
 * directly, so it's exercising the actual live-preview integration [AirbrushWiringTest] (commit
 * path) and [AirbrushEngineTest] (the primitive) do not reach.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LiveStampAirbrushTest {

    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val canvasSize = IntSize(64, 64)

    private val airbrush = AzphaltBrush(
        name = "Airbrush",
        hardness = 1f,
        // Low opacity so a single dab never saturates alpha to 255 outright -- the point of this
        // test is to observe repeated held dabs building up *more* coverage than one dab alone,
        // via BrushStamps.buildUp's asymptotic accumulation, the same curve alpha build-up already
        // uses everywhere else in this codebase.
        opacity = 0.15f,
        airbrushDabsPerSecond = 50f,
        airbrushStillnessRadiusPx = 20f,
    )
    private val noAirbrush = airbrush.copy(airbrushDabsPerSecond = 0f)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        RenderTestBase.stubNativeLibs()

        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { backgroundColor } returns MutableStateFlow(0)
            every { inputSampleRateHz } returns MutableStateFlow(0) // no throttling in this test
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
            every { load("airbrush") } returns airbrush
            every { load("no-airbrush") } returns noAirbrush
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

    /** Opacity actually deposited at [x],[y] on the live preview, 0 if there's none yet. */
    private fun liveAlphaAt(x: Int, y: Int): Int =
        Color.alpha(vm.uiState.value.liveStrokeBitmap!!.getPixel(x, y))

    private fun holdStillAndReturnPeakAlpha(brushSelectorId: String): Int {
        // Transparent, not opaque white: painting onto an already-opaque destination leaves its
        // alpha channel at 255 regardless of the deposited paint's own opacity (SRC_OVER keeps
        // dst alpha saturated), which would make alpha useless as a build-up signal here.
        val base = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
        val layer = Layer(id = "L", name = "Layer", bitmap = base)
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(layer)))
        vm.onLayerActivated("L")
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.selectCustomBrush(brushSelectorId)
        vm.setActiveTool(Tool.BRUSH)

        val cx = canvasSize.width / 2f
        val cy = canvasSize.height / 2f
        vm.onStrokeStart(BrushSample(cx, cy, uptimeMillis = 0L), canvasSize)
        // Five more samples at the same position, 40ms apart -- well past the 50 dabs/sec (20ms)
        // emit interval, and well inside the 20px stillness radius, so every one after the first
        // pair should be able to emit at least one more held dab (when airbrush is on).
        for (i in 1..5) {
            vm.onStrokePoint(BrushSample(cx, cy, uptimeMillis = i * 40L))
        }
        val alpha = liveAlphaAt(cx.toInt(), cy.toInt())
        vm.onStrokeEnd()
        return alpha
    }

    @Test
    fun `holding still with airbrush enabled builds up more opacity in the live preview than without`() = runTest {
        val withAirbrush = holdStillAndReturnPeakAlpha("airbrush")
        val without = holdStillAndReturnPeakAlpha("no-airbrush")

        assertTrue(
            "expected airbrush hold to deposit more live-preview opacity ($withAirbrush) " +
                "than the same hold without airbrush ($without)",
            withAirbrush > without,
        )
    }
}
