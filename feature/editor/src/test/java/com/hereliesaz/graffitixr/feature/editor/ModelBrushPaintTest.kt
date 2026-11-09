@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.common.mesh.ObjParser
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.is3DIn
import com.hereliesaz.graffitixr.common.scene.ModelLayerContent
import com.hereliesaz.graffitixr.common.scene.ModelMesh
import com.hereliesaz.graffitixr.common.scene.ModelSpace
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import com.hereliesaz.graffitixr.data.brush.CustomBrushRepository
import com.hereliesaz.graffitixr.data.figma.FigmaRepository
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The brush sliders (hardness, flow, opacity) on a 3D model layer's surface (spec/3d-space.md). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ModelBrushPaintTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val size = IntSize(1000, 800)
    private val currentProject = MutableStateFlow<GraffitiProject?>(null)
    private val artifacts = HashMap<String, ByteArray>()
    private lateinit var projects: ProjectRepository
    private val emitted = ArrayList<Op>()
    private val exportManager = mockk<com.hereliesaz.graffitixr.feature.editor.export.ExportManager>(relaxed = true)
    private var coopActive = false

    private val objText = """
        v -1 -1 0
        v 1 -1 0
        v 1 1 0
        v -1 1 0
        vt 0 0
        vt 1 0
        vt 1 1
        vt 0 1
        f 1/1 2/2 3/3 4/4
    """.trimIndent()
    private val quad = ModelMesh(ObjParser.parse(objText))

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
        projects = mockk(relaxed = true) {
            every { currentProject } returns this@ModelBrushPaintTest.currentProject
            every { this@mockk.projects } returns MutableStateFlow(emptyList())
        }
        val name = slot<String>()
        val bytes = slot<ByteArray>()
        coEvery { projects.saveArtifact(any(), capture(name), capture(bytes)) } answers {
            artifacts[name.captured] = bytes.captured
            "/artifacts/${name.captured}"
        }
        val emitter = mockk<OpEmitter>(relaxed = true)
        every { emitter.isActive } answers { coopActive }
        every { emitter.emit(any()) } answers { emitted += firstArg<Op>() }
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
            exportManager = exportManager,
            context = RuntimeEnvironment.getApplication(),
            liquifyEngine = mockk(relaxed = true),
            dispatchers = dispatchers,
            opEmitter = emitter,
            extensionRepository = mockk<ExtensionRepository>(relaxed = true) { every { installed } returns MutableStateFlow(emptyList()) },
            repositoryApiClient = mockk(relaxed = true),
            customBrushRepository = mockk<CustomBrushRepository>(relaxed = true) { every { brushes } returns MutableStateFlow(emptyList()) },
            figmaRepository = mockk<FigmaRepository>(relaxed = true) { every { isAuthenticated } returns MutableStateFlow(false) },
            projectFileScanner = mockk(relaxed = true),
        )
        vm.onCanvasSizeChanged(size)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun idle() = dispatcher.scheduler.advanceUntilIdle()

    private fun model() = vm.uiState.value.layers.single { it.model != null }

    private fun addModel(id: String = "m") {
        vm.addModelLayer(id, "head.obj", "model_$id.obj", quad)
        idle()
    }

    /** The canvas point over (u, v) of the quad (GL v, top = 0) for the layer as placed. */
    private fun pointAt(u: Float, vGl: Float): Offset {
        val st = vm.uiState.value
        val toScene = ModelSpace.meshToScene(listOf(com.hereliesaz.graffitixr.common.scene.PlanePose(
            model().offset.x, model().offset.y, model().positionZ, model().rotationX, model().rotationY, model().rotationZ, model().scale,
        )), quad, size.width.toFloat(), size.height.toFloat())
        val p = toScene.apply(u * 2f - 1f, (1f - vGl) * 2f - 1f, 0f)
        val (x, y) = SceneMath.frame(st.sceneCamera, size.width.toFloat(), size.height.toFloat()).project(p)!!
        return Offset(x, y)
    }

    /** The alpha of the quad's texture at (u, v), after one stroke painted with the current brush. */
    private fun quadAlphaAt(u: Float, v: Float = 0.5f): Int {
        val pixels = model().model!!.texture!!.pixels()!!
        return Color.alpha(pixels.getPixel((u * pixels.width).toInt(), (v * pixels.height).toInt()))
    }

    private fun startBrushOnQuad(size: Float) {
        addModel()
        vm.setActiveColor(androidx.compose.ui.graphics.Color.Red)
        vm.setActiveTool(Tool.BRUSH)
        vm.setBrushSize(size)
        vm.onLayerActivated("m")
        idle()
    }

    @Test
    fun `flow is each dab's own strength`() = runTest(dispatcher) {
        startBrushOnQuad(100f)
        vm.setBrushFlow(0.2f)
        vm.onModelPaintStart(pointAt(0.5f, 0.5f))
        vm.onModelPaintEnd()
        idle()
        // One dab of a hard brush at flow 0.2: a fifth of full.
        assertEquals(51f, quadAlphaAt(0.5f).toFloat(), 3f)
    }

    @Test
    fun `opacity caps a stroke however often it crosses itself`() = runTest(dispatcher) {
        startBrushOnQuad(100f)
        vm.setBrushOpacity(0.5f)
        vm.onModelPaintStart(pointAt(0.3f, 0.5f))
        vm.onModelPaintMove(pointAt(0.7f, 0.5f))
        vm.onModelPaintMove(pointAt(0.3f, 0.5f)) // back over the same ground
        vm.onModelPaintEnd()
        idle()
        // The middle was painted by many overlapping dabs on two passes, and is still half opaque.
        assertEquals(128f, quadAlphaAt(0.5f).toFloat(), 3f)
        assertEquals(128f, quadAlphaAt(0.3f).toFloat(), 3f)
    }

    @Test
    fun `a stroke at full opacity is opaque`() = runTest(dispatcher) {
        startBrushOnQuad(100f)
        vm.onModelPaintStart(pointAt(0.3f, 0.5f))
        vm.onModelPaintMove(pointAt(0.7f, 0.5f))
        vm.onModelPaintEnd()
        idle()
        assertEquals(255, quadAlphaAt(0.5f))
    }

    @Test
    fun `an opacity stroke leaves what was painted before it`() = runTest(dispatcher) {
        startBrushOnQuad(100f)
        vm.onModelPaintStart(pointAt(0.3f, 0.5f))
        vm.onModelPaintEnd()
        idle()
        assertEquals(255, quadAlphaAt(0.3f))
        vm.setBrushOpacity(0.5f)
        vm.onLayerActivated("m")
        vm.onModelPaintStart(pointAt(0.7f, 0.5f))
        vm.onModelPaintEnd()
        idle()
        assertEquals("the earlier stroke is untouched", 255, quadAlphaAt(0.3f))
        assertEquals("the new one is half", 128f, quadAlphaAt(0.7f).toFloat(), 3f)
    }

    @Test
    fun `feathering softens the edge of a dab`() = runTest(dispatcher) {
        // 100 px is a radius of about 0.104 in UV on this model; 0.59 is 0.9 of it from the centre.
        startBrushOnQuad(100f)
        vm.onModelPaintStart(pointAt(0.5f, 0.5f))
        vm.onModelPaintEnd()
        idle()
        assertEquals("hard by default", 255, quadAlphaAt(0.59f))

        vm.onUndoClicked()
        idle()
        vm.setBrushFeathering(1f)
        vm.onLayerActivated("m")
        vm.onModelPaintStart(pointAt(0.5f, 0.5f))
        vm.onModelPaintEnd()
        idle()
        val soft = quadAlphaAt(0.59f)
        assertTrue("feathered edge is partly transparent, was $soft", soft in 1..200)
    }

    @Test
    fun `the eraser takes paint off in proportion to the flow`() = runTest(dispatcher) {
        startBrushOnQuad(100f)
        vm.onModelPaintStart(pointAt(0.5f, 0.5f))
        vm.onModelPaintEnd()
        idle()
        vm.setActiveTool(Tool.ERASER)
        vm.setBrushFlow(0.5f)
        vm.onLayerActivated("m")
        vm.onModelPaintStart(pointAt(0.5f, 0.5f))
        vm.onModelPaintEnd()
        idle()
        // Half taken off a fully opaque centre.
        assertEquals(128f, quadAlphaAt(0.5f).toFloat(), 3f)
    }
}
