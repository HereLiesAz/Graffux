package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.ImpastoMaterialConfig
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
import com.hereliesaz.graffitixr.common.model.Op
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The mutation paths that must invalidate a layer's GPU-resident copy (wgpu) actually do. Each test
 * records the layer's generation for its current bitmap -- what a stroke would bind -- then runs a
 * mutation and checks that generation is no longer current. The generation is checked directly,
 * not re-derived from the bitmap, so these fail if the explicit invalidation is missing even where
 * the bitmap-identity backstop would have caught it later.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ResidentLayerInvalidationTest {

    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val canvasSize = IntSize(48, 48)

    private val brush = AzphaltBrush(name = "Solid", hardness = 1f, opacity = 1f)
    private val impastoBrush = brush

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
            every { load("impasto") } returns impastoBrush
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

    private fun layerBitmap(): Bitmap = vm.uiState.value.layers.first { it.id == "L" }.bitmap!!

    /** The generation a stroke starting now would bind. */
    private fun boundGeneration(): Long = vm.layerResidencyForTest().generationFor("L", layerBitmap())

    private fun stale(generation: Long) = !vm.layerResidencyForTest().isCurrent("L", generation)

    private fun drawStroke(x: Float, y: Float) {
        vm.onStrokeStart(BrushSample(x, y, uptimeMillis = 0L), canvasSize)
        vm.onStrokePoint(BrushSample(x + 6f, y, uptimeMillis = 16L))
        vm.onStrokeEnd()
    }

    private fun setUpLayer() {
        val original = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.TRANSPARENT)
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(Layer(id = "L", name = "Layer", bitmap = original))))
        vm.putLayerBaseForTest("L", original)
        vm.onLayerActivated("L")
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvasSize))
        vm.selectCustomBrush("solid")
        vm.setActiveTool(Tool.BRUSH)
    }

    @Test
    fun `undo marks the layer stale`() = runTest {
        setUpLayer()
        drawStroke(12f, 12f)
        drawStroke(30f, 30f)
        val generation = boundGeneration()
        vm.onUndoClicked()
        assertTrue("undo must invalidate the resident copy", stale(generation))
    }

    @Test
    fun `redo marks the layer stale`() = runTest {
        setUpLayer()
        drawStroke(12f, 12f)
        vm.onUndoClicked()
        val generation = boundGeneration()
        vm.onRedoClicked()
        assertTrue("redo must invalidate the resident copy", stale(generation))
    }

    @Test
    fun `a stroke commit without a GPU binding never keeps the old generation`() = runTest {
        setUpLayer()
        val generation = boundGeneration()
        drawStroke(12f, 12f)
        // No wgpu engine in a unit test, so there is no resident ticket to adopt the commit: the
        // committed bitmap is a new object and must map to a new generation.
        assertFalse(boundGeneration() == generation)
    }

    @Test
    fun `clearing the layer marks it stale`() = runTest {
        setUpLayer()
        val generation = boundGeneration()
        vm.onClearLayer()
        assertTrue(stale(generation))
    }

    @Test
    fun `co-op ops that change pixels mark the layer stale, layout-only ops do not`() = runTest {
        setUpLayer()
        val generation = boundGeneration()
        vm.applySpectatorOp(Op.LayerTransform("L", listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        assertFalse("a transform does not change the layer's pixels", stale(generation))
        vm.applySpectatorOp(Op.LayerBitmapReplace("L", ByteArray(0)))
        assertTrue("a peer's bitmap replace must invalidate", stale(generation))
    }
}
