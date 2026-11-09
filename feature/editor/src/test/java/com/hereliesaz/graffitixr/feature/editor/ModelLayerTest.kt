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

/** 3D model layers in the one canvas space (spec/3d-space.md, "3D model layers"). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ModelLayerTest {
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
            every { currentProject } returns this@ModelLayerTest.currentProject
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

    @Test
    fun `import creates a 3D model layer facing the camera through the orbit point`() = runTest(dispatcher) {
        vm.onSceneOrbit(0.6f, -0.25f)
        addModel()
        val layer = model()
        assertTrue(layer.is3DIn(vm.uiState.value.layers))
        assertEquals("head", layer.name)
        assertEquals(layer.id, vm.uiState.value.activeLayerId)
        val camera = vm.uiState.value.sceneCamera
        val expected = SceneMath.viewPlanePose(camera, size.width.toFloat(), size.height.toFloat())!!
        assertEquals(expected.positionZ, layer.positionZ, 1e-3f)
        assertEquals(expected.rotationY, layer.rotationY, 1e-3f)
        assertEquals(expected.scale, layer.scale, 1e-4f)
        // Its centre is under the middle of the view.
        val c = pointAt(0.5f, 0.5f)
        assertEquals(size.width / 2f, c.x, 0.5f)
        assertEquals(size.height / 2f, c.y, 0.5f)
        // One undo step.
        vm.onUndoClicked()
        idle()
        assertTrue(vm.uiState.value.layers.none { it.model != null })
    }

    @Test
    fun `the Move tool transforms the model, one undo step per drag`() = runTest(dispatcher) {
        addModel()
        vm.onTransformClicked()
        idle()
        assertEquals(EditorPanel.TRANSFORM, vm.uiState.value.activePanel)
        val before = model()
        val undo = vm.uiState.value.undoCount
        vm.onTwoFingerStart()
        repeat(3) { vm.onTwoFingerGesture(Offset(10f, 0f), 1.1f, Offset(500f, 400f), 0f) }
        vm.onTwoFingerEnd()
        idle()
        val moved = model()
        assertTrue(moved.offset.x > before.offset.x)
        assertTrue("pinch out brings it nearer", moved.positionZ < before.positionZ)
        assertEquals(undo + 1, vm.uiState.value.undoCount)
        vm.onUndoClicked()
        idle()
        assertEquals(before.offset, model().offset)
        assertEquals(before.positionZ, model().positionZ)
        assertNotNull("the mesh survives the undo", model().model!!.mesh)
    }

    @Test
    fun `painting hits the UV under the touch and is undoable`() = runTest(dispatcher) {
        addModel()
        vm.setActiveColor(androidx.compose.ui.graphics.Color.Red)
        vm.setActiveTool(Tool.BRUSH)
        vm.onLayerActivated("m")
        idle()
        assertEquals(SpatialStrokeTool.DRAW, vm.uiState.value.modelPaintTool)
        val undo = vm.uiState.value.undoCount
        // A miss starts the stroke but paints nothing and records nothing.
        assertTrue(vm.onModelPaintStart(Offset(2f, 2f)))
        vm.onModelPaintEnd()
        assertEquals(undo, vm.uiState.value.undoCount)
        assertTrue(vm.onModelPaintStart(pointAt(0.75f, 0.25f)))
        assertNotNull(vm.uiState.value.modelDraft)
        vm.onModelPaintEnd()
        idle()
        assertNull(vm.uiState.value.modelDraft)
        assertEquals(undo + 1, vm.uiState.value.undoCount)
        val texture = model().model!!.texture!!
        val pixels = texture.pixels()!!
        assertEquals(Color.RED, pixels.getPixel((0.75f * pixels.width).toInt(), (0.25f * pixels.height).toInt()))
        assertEquals(0, Color.alpha(pixels.getPixel((0.25f * pixels.width).toInt(), (0.75f * pixels.height).toInt())))
        vm.onUndoClicked()
        idle()
        assertNull(model().model!!.texture)
        vm.onRedoClicked()
        idle()
        assertSame(texture, model().model!!.texture)
        // The eraser takes it off again, as its own step.
        vm.setActiveTool(Tool.ERASER)
        vm.onLayerActivated("m")
        idle()
        vm.onModelPaintStart(pointAt(0.75f, 0.25f))
        vm.onModelPaintEnd()
        idle()
        val erased = model().model!!.texture!!.pixels()!!
        assertEquals(0, Color.alpha(erased.getPixel((0.75f * erased.width).toInt(), (0.25f * erased.height).toInt())))
    }

    /** Two quads that touch in space but sit apart in the texture: UV islands 0..0.45 and 0.5..0.95. */
    private val twoIslandObj = """
        v -1 -1 0
        v 0 -1 0
        v 0 1 0
        v -1 1 0
        v 0 -1 0
        v 1 -1 0
        v 1 1 0
        v 0 1 0
        vt 0 0
        vt 0.45 0
        vt 0.45 1
        vt 0 1
        vt 0.5 0
        vt 0.95 0
        vt 0.95 1
        vt 0.5 1
        f 1/1 2/2 3/3 4/4
        f 5/5 6/6 7/7 8/8
    """.trimIndent()

    private fun addTwoIslandModel(brushSize: Float) {
        vm.addModelLayer("m", "head.obj", "model_m.obj", ModelMesh(ObjParser.parse(twoIslandObj)))
        idle()
        vm.setActiveColor(androidx.compose.ui.graphics.Color.Red)
        vm.setActiveTool(Tool.BRUSH)
        vm.setBrushSize(brushSize)
        vm.onLayerActivated("m")
        idle()
    }

    /** The canvas point over mesh (x, 0) of the two-island model as placed. */
    private fun pointOnTwoIsland(x: Float): Offset {
        val st = vm.uiState.value
        val mesh = ModelMesh(ObjParser.parse(twoIslandObj))
        val toScene = ModelSpace.meshToScene(listOf(com.hereliesaz.graffitixr.common.scene.PlanePose(
            model().offset.x, model().offset.y, model().positionZ, model().rotationX, model().rotationY, model().rotationZ, model().scale,
        )), mesh, size.width.toFloat(), size.height.toFloat())
        val (px, py) = SceneMath.frame(st.sceneCamera, size.width.toFloat(), size.height.toFloat()).project(toScene.apply(x, 0f, 0f))!!
        return Offset(px, py)
    }

    private fun alphaAt(u: Float, v: Float = 0.5f): Int {
        val pixels = model().model!!.texture!!.pixels()!!
        return Color.alpha(pixels.getPixel((u * pixels.width).toInt(), (v * pixels.height).toInt()))
    }

    @Test
    fun `a big dab near an island edge does not bleed onto the island beside it`() = runTest(dispatcher) {
        // The brush is capped at 200 px: a radius of about 0.19 in UV on this model.
        addTwoIslandModel(brushSize = 200f)
        // u 0.40 is x -0.111 on the left quad; the dab reaches past the 0.05 gap into the right island.
        vm.onModelPaintStart(pointOnTwoIsland(-1f + 0.40f / 0.45f))
        vm.onModelPaintEnd()
        idle()
        assertTrue("paints inside its own island", alphaAt(0.42f) > 0)
        assertTrue("and reaches far across it, so the next two checks mean something", alphaAt(0.25f) > 0)
        assertEquals("the gap between the islands is no one's texels", 0, alphaAt(0.475f))
        assertEquals("the other island is untouched, though within the brush", 0, alphaAt(0.52f))
    }

    /** Islands 0..0.45 and 0.452..0.95: 2 texels (of 1024) between them, less than the padding gutter. */
    private val tightIslandObj = twoIslandObj
        .replace("vt 0.5 0\n", "vt 0.452 0\n").replace("vt 0.5 1", "vt 0.452 1")

    private fun addTightIslandModel() {
        vm.addModelLayer("m", "head.obj", "model_m.obj", ModelMesh(ObjParser.parse(tightIslandObj)))
        idle()
        vm.setActiveColor(androidx.compose.ui.graphics.Color.Red)
        vm.setActiveTool(Tool.BRUSH)
        vm.setBrushSize(200f)
        vm.onLayerActivated("m")
        idle()
    }

    private fun alphaAtPixel(x: Int, y: Int = 512): Int =
        Color.alpha(model().model!!.texture!!.pixels()!!.getPixel(x, y))

    @Test
    fun `a dab at an island edge paints the filter gutter and nothing of the island beside it`() = runTest(dispatcher) {
        addTightIslandModel()
        // u 0.40 is x -0.111 on the left quad; the island ends at texel 460.8.
        vm.onModelPaintStart(pointOnTwoIsland(-1f + 0.40f / 0.45f))
        vm.onModelPaintEnd()
        idle()
        assertTrue("last texel of the island", alphaAtPixel(460) > 0)
        assertTrue("first texel of padding: bilinear sampling at the edge reaches it", alphaAtPixel(461) > 0)
        assertTrue("second texel of padding", alphaAtPixel(462) > 0)
        assertEquals("the neighbouring island's texel, though within the brush", 0, alphaAtPixel(463))
        assertEquals("far side", 0, alphaAtPixel(520))
    }

    @Test
    fun `a seam painted from both sides has no half-coverage texel`() = runTest(dispatcher) {
        addTightIslandModel()
        vm.onModelPaintStart(pointOnTwoIsland(-1f + 0.40f / 0.45f))
        vm.onModelPaintEnd()
        idle()
        vm.onLayerActivated("m")
        vm.onModelPaintStart(pointOnTwoIsland((0.50f - 0.452f) / 0.498f))
        vm.onModelPaintEnd()
        idle()
        for (x in 455..470) assertTrue("texel $x has alpha ${alphaAtPixel(x)}", alphaAtPixel(x) >= 250)
    }

    @Test
    fun `a step across an island boundary does not stripe through the texture between them`() = runTest(dispatcher) {
        addTwoIslandModel(brushSize = 60f)
        // u 0.43 to u 0.52: a 0.09 jump, well under the old quarter-texture guess, so it used to be
        // joined and painted straight through the gap.
        vm.onModelPaintStart(pointOnTwoIsland(-1f + 0.43f / 0.45f))
        vm.onModelPaintMove(pointOnTwoIsland((0.52f - 0.5f) / 0.45f))
        vm.onModelPaintEnd()
        idle()
        assertTrue("starts on the left island", alphaAt(0.43f) > 0)
        assertTrue("ends on the right island", alphaAt(0.52f) > 0)
        assertEquals("nothing is painted across the gap", 0, alphaAt(0.475f))
    }

    /**
     * A strip folded in two and mapped continuously, so it is ONE UV island: a front panel that stops
     * at x = 0.2 (u 0.4 down to 0 as x rises), the fold at x = -1 (u 0.4..0.5), and a back panel
     * behind it that runs on to x = 1 (u 0.5..0.9). Each corner is x y z u v (v in GL convention).
     */
    private val foldedObj: String = run {
        fun face(vararg c: Float) = c.toList().chunked(5)
        val faces = listOf(
            face(0.2f, -1f, 0f, 0f, 0f, -1f, -1f, 0f, 0.4f, 0f, -1f, 1f, 0f, 0.4f, 1f, 0.2f, 1f, 0f, 0f, 1f),
            face(-1f, -1f, 0f, 0.4f, 0f, -1f, -1f, -0.2f, 0.5f, 0f, -1f, 1f, -0.2f, 0.5f, 1f, -1f, 1f, 0f, 0.4f, 1f),
            face(-1f, -1f, -0.2f, 0.5f, 0f, 1f, -1f, -0.2f, 0.9f, 0f, 1f, 1f, -0.2f, 0.9f, 1f, -1f, 1f, -0.2f, 0.5f, 1f),
        )
        buildString {
            var n = 0
            for (f in faces) {
                for (c in f) appendLine("v ${c[0]} ${c[1]} ${c[2]}")
                for (c in f) appendLine("vt ${c[3]} ${1f - c[4]}")
                appendLine("f " + (1..4).joinToString(" ") { "${n + it}/${n + it}" })
                n += 4
            }
        }
    }

    @Test
    fun `a stroke does not paint the hidden fold between a front panel and the back one it drops onto`() =
        runTest(dispatcher) {
            val fold = ModelMesh(ObjParser.parse(foldedObj))
            vm.addModelLayer("m", "head.obj", "model_m.obj", fold)
            idle()
            vm.setActiveColor(androidx.compose.ui.graphics.Color.Red)
            vm.setActiveTool(Tool.BRUSH)
            vm.setBrushSize(30f)
            vm.onLayerActivated("m")
            idle()
            fun at(x: Float, y: Float): Offset {
                val l = model()
                val toScene = ModelSpace.meshToScene(listOf(com.hereliesaz.graffitixr.common.scene.PlanePose(
                    l.offset.x, l.offset.y, l.positionZ, l.rotationX, l.rotationY, l.rotationZ, l.scale,
                )), fold, size.width.toFloat(), size.height.toFloat())
                val (px, py) = SceneMath.frame(vm.uiState.value.sceneCamera, size.width.toFloat(), size.height.toFloat())
                    .project(toScene.apply(x, y, 0f))!!
                return Offset(px, py)
            }
            // Right across the front panel's end at x = 0.2: front (u 0.03) then, with no miss, back (u 0.76).
            vm.onModelPaintStart(at(0.1f, 0f))
            vm.onModelPaintMove(at(0.3f, 0f))
            vm.onModelPaintEnd()
            idle()
            assertTrue("paints where the finger started", alphaAt(0.033f, 0.5f) > 0)
            assertTrue("and where it ended", alphaAt(0.76f, 0.5f) > 0)
            assertEquals("not the fold's texels between them", 0, alphaAt(0.45f, 0.5f))
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

    /**
     * A strip folded in two and mapped continuously, so it is ONE UV island: a front panel that stops
     * at x = 0.2 (u 0.4 down to 0 as x rises), the fold at x = -1 (u 0.4..0.5), and a back panel
     * behind it that runs on to x = 1 (u 0.5..0.9). Each corner is x y z u v (v in GL convention).
     */
    private val foldedObj: String = run {
        fun face(vararg c: Float) = c.toList().chunked(5)
        val faces = listOf(
            face(0.2f, -1f, 0f, 0f, 0f, -1f, -1f, 0f, 0.4f, 0f, -1f, 1f, 0f, 0.4f, 1f, 0.2f, 1f, 0f, 0f, 1f),
            face(-1f, -1f, 0f, 0.4f, 0f, -1f, -1f, -0.2f, 0.5f, 0f, -1f, 1f, -0.2f, 0.5f, 1f, -1f, 1f, 0f, 0.4f, 1f),
            face(-1f, -1f, -0.2f, 0.5f, 0f, 1f, -1f, -0.2f, 0.9f, 0f, 1f, 1f, -0.2f, 0.9f, 1f, -1f, 1f, -0.2f, 0.5f, 1f),
        )
        buildString {
            var n = 0
            for (f in faces) {
                for (c in f) appendLine("v ${c[0]} ${c[1]} ${c[2]}")
                for (c in f) appendLine("vt ${c[3]} ${1f - c[4]}")
                appendLine("f " + (1..4).joinToString(" ") { "${n + it}/${n + it}" })
                n += 4
            }
        }
    }

    @Test
    fun `a stroke does not paint the hidden fold between a front panel and the back one it drops onto`() =
        runTest(dispatcher) {
            val fold = ModelMesh(ObjParser.parse(foldedObj))
            vm.addModelLayer("m", "head.obj", "model_m.obj", fold)
            idle()
            vm.setActiveColor(androidx.compose.ui.graphics.Color.Red)
            vm.setActiveTool(Tool.BRUSH)
            vm.setBrushSize(30f)
            vm.onLayerActivated("m")
            idle()
            fun at(x: Float, y: Float): Offset {
                val l = model()
                val toScene = ModelSpace.meshToScene(listOf(com.hereliesaz.graffitixr.common.scene.PlanePose(
                    l.offset.x, l.offset.y, l.positionZ, l.rotationX, l.rotationY, l.rotationZ, l.scale,
                )), fold, size.width.toFloat(), size.height.toFloat())
                val (px, py) = SceneMath.frame(vm.uiState.value.sceneCamera, size.width.toFloat(), size.height.toFloat())
                    .project(toScene.apply(x, y, 0f))!!
                return Offset(px, py)
            }
            // Right across the front panel's end at x = 0.2: front (u 0.03) then, with no miss, back (u 0.76).
            vm.onModelPaintStart(at(0.1f, 0f))
            vm.onModelPaintMove(at(0.3f, 0f))
            vm.onModelPaintEnd()
            idle()
            assertTrue("paints where the finger started", alphaAt(0.033f, 0.5f) > 0)
            assertTrue("and where it ended", alphaAt(0.76f, 0.5f) > 0)
            assertEquals("not the fold's texels between them", 0, alphaAt(0.45f, 0.5f))
        }

    private fun project(layers: List<OverlayLayer>) = GraffitiProject(id = "p1", name = "P", layers = layers)

    private fun projectDir() = File(RuntimeEnvironment.getApplication().filesDir, "projects/p1").apply { mkdirs() }

    @Test
    fun `a painted model saves its texture and loads back with it`() = runTest(dispatcher) {
        currentProject.value = project(emptyList())
        idle()
        addModel()
        vm.setActiveTool(Tool.BRUSH)
        vm.onLayerActivated("m")
        vm.setActiveColor(androidx.compose.ui.graphics.Color.Blue)
        idle()
        vm.onModelPaintStart(pointAt(0.5f, 0.5f))
        vm.onModelPaintEnd()
        idle()
        // The save wrote the texture and named it on the layer.
        val transforms = mutableListOf<(GraffitiProject) -> GraffitiProject>()
        io.mockk.coVerify(atLeast = 1) { projects.updateProject(capture(transforms)) }
        // Through JSON, as project.json is written: only the file names survive.
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val saved = json.decodeFromString(
            GraffitiProject.serializer(),
            json.encodeToString(GraffitiProject.serializer(), transforms.last()(project(emptyList()))),
        )
        val content = saved.layers.single().model!!
        assertEquals("model_m.obj", content.objFile)
        assertEquals("model_tex_m.png", content.textureFile)
        val png = artifacts.getValue("model_tex_m.png")
        // Reload: the files in the project folder, the manifest as saved.
        File(projectDir(), "model_m.obj").writeText(objText)
        File(projectDir(), "model_tex_m.png").writeBytes(png)
        currentProject.value = null
        idle()
        currentProject.value = saved.copy(id = "p1")
        idle()
        val loaded = model().model!!
        assertNotNull(loaded.mesh)
        val pixels = loaded.texture!!.pixels()!!
        assertEquals(Color.BLUE, pixels.getPixel(pixels.width / 2, pixels.height / 2))
        // The loaded revision is what is on disk: an unchanged save writes nothing.
        artifacts.clear()
        vm.saveProject()
        idle()
        assertTrue(artifacts.keys.toString(), artifacts.isEmpty())
    }

    @Test
    fun `an old project's floating-window model loads as a model layer on top`() = runTest(dispatcher) {
        // What ProjectManager's 7 -> 8 migration makes of modelPath and modelTexturePath.
        File(projectDir(), "model.obj").writeText(objText)
        val paint = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        File(projectDir(), "model_texture.png").outputStream().use { paint.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        currentProject.value = project(listOf(
            OverlayLayer(id = "a", uri = android.net.Uri.parse("")),
            OverlayLayer(id = "old", name = "head", uri = android.net.Uri.parse(""), model = ModelLayerContent("model.obj", "model_texture.png")),
        ))
        idle()
        val layers = vm.uiState.value.layers
        assertEquals("old", layers.last().id)
        val content = layers.last().model!!
        assertNotNull(content.mesh)
        assertEquals(Color.GREEN, content.texture!!.pixels()!!.getPixel(10, 10))
        assertTrue(layers.last().is3DIn(layers))
        // An unchanged save keeps the texture under the name it was migrated with (Codex #684).
        artifacts.clear()
        vm.saveProject()
        idle()
        val transforms = mutableListOf<(GraffitiProject) -> GraffitiProject>()
        io.mockk.coVerify(atLeast = 1) { projects.updateProject(capture(transforms)) }
        val saved = transforms.last()(project(emptyList())).layers.single { it.id == "old" }.model!!
        assertEquals("model_texture.png", saved.textureFile)
        assertTrue(artifacts.keys.toString(), artifacts.isEmpty())
    }

    @Test
    fun `flattening renders through the scene camera, so model layers are kept`() = runTest(dispatcher) {
        currentProject.value = project(emptyList())
        idle()
        addModel()
        vm.onSceneOrbit(0.3f, 0f)
        vm.onFlattenAllLayers()
        idle()
        io.mockk.verify { exportManager.compositeLayers(any(), any(), any(), camera = vm.uiState.value.sceneCamera) }
    }

    @Test
    fun `a flatten under a moved camera sits on the view plane, so it shows as seen, not projected twice`() = runTest(dispatcher) {
        currentProject.value = project(emptyList())
        idle()
        val image = android.graphics.Bitmap.createBitmap(size.width, size.height, android.graphics.Bitmap.Config.ARGB_8888)
        every { exportManager.compositeLayers(any(), any(), any(), camera = any()) } returns image
        addModel()
        vm.onSceneOrbit(0.5f, -0.2f)
        vm.onFlattenAllLayers()
        idle()
        // Rendered at the canvas's own size, through the camera.
        io.mockk.verify { exportManager.compositeLayers(any(), size.width, size.height, camera = vm.uiState.value.sceneCamera) }
        val flat = vm.uiState.value.layers.single()
        assertTrue(flat.is3DIn(vm.uiState.value.layers))
        // Its box projects onto the canvas pixel for pixel: the plane's homography is the identity.
        val w = size.width.toFloat()
        val hh = size.height.toFloat()
        val homography = SceneMath.homography(
            SceneMath.model(com.hereliesaz.graffitixr.common.scene.PlanePose(
                flat.offset.x, flat.offset.y, flat.positionZ, flat.rotationX, flat.rotationY, flat.rotationZ, flat.scale,
            ), w, hh),
            SceneMath.frame(vm.uiState.value.sceneCamera, w, hh),
        )
        listOf(0f to 0f, w to 0f, w / 3f to hh * 0.7f).forEach { (x, y) ->
            val (px, py) = SceneMath.applyHomography(homography, x, y)!!
            assertEquals(x, px, 0.05f)
            assertEquals(y, py, 0.05f)
        }
    }

    @Test
    fun `a tap on a model's surface selects it, and its outline has corners`() = runTest(dispatcher) {
        addModel()
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val layers = listOf(com.hereliesaz.graffitixr.common.model.Layer("under", "U"), model())
        assertEquals("m", CanvasHitTest.topHit(layers, Offset(w / 2f, h / 2f), w, h))
        assertNull(CanvasHitTest.topHit(layers, Offset(3f, 3f), w, h))
        // Under an orbit, through the same ray as painting.
        val camera = com.hereliesaz.graffitixr.common.scene.SceneCamera().orbit(0.5f, 0.2f)
        val pose = SceneMath.viewPlanePose(camera, w, h)!!
        val placed = model().copy(offset = Offset(pose.offsetX, pose.offsetY), positionZ = pose.positionZ,
            rotationX = pose.rotationX, rotationY = pose.rotationY, rotationZ = pose.rotationZ, scale = pose.scale)
        assertEquals("m", CanvasHitTest.topHit(listOf(placed), Offset(w / 2f, h / 2f), w, h, camera = camera))
        assertNotNull(CanvasHitTest.layerScreenCorners(model(), w, h))
    }

    @Test
    fun `glTF export carries the model's mesh and painted texture`() = runTest(dispatcher) {
        addModel()
        vm.setActiveTool(Tool.BRUSH)
        vm.onLayerActivated("m")
        idle()
        vm.onModelPaintStart(pointAt(0.5f, 0.5f))
        vm.onModelPaintEnd()
        idle()
        val scene = buildSceneGltf(vm.uiState.value, size.width.toFloat(), size.height.toFloat(), includeCamera = false)
        val model = scene.models.single()
        assertSame(quad.mesh, model.mesh)
        val png = model.png!!
        assertNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
        assertTrue(com.hereliesaz.graffitixr.common.scene.SceneGltfExporter.encode(scene).contains("TEXCOORD_0"))
    }

    @Test
    fun `co-op sends the paint after a stroke and installs a peer's mesh`() = runTest(dispatcher) {
        File(projectDir(), "model_m.obj").writeText(objText)
        currentProject.value = project(listOf(
            OverlayLayer(id = "m", name = "M", uri = android.net.Uri.parse(""), model = ModelLayerContent("model_m.obj")),
        ))
        idle()
        coopActive = true
        vm.setActiveTool(Tool.BRUSH)
        vm.onLayerActivated("m")
        idle()
        vm.onModelPaintStart(pointAt(0.5f, 0.5f))
        vm.onModelPaintEnd()
        idle()
        val data = emitted.filterIsInstance<Op.ModelData>().last()
        assertEquals("m", data.layerId)
        assertNull(data.objDeflated)
        assertNotNull(data.texturePng)
        // A peer's layer without its mesh gets one from the peer's model data.
        val peerLayer = com.hereliesaz.graffitixr.common.model.Layer("peer", "P", model = ModelLayerContent("model_peer.obj"))
        vm.applySpectatorOp(Op.LayerAdd(peerLayer))
        vm.applySpectatorOp(Op.ModelData("peer", "model_peer.obj", ModelCoopCodec.deflate(objText.toByteArray()), data.texturePng))
        idle()
        val installed = vm.uiState.value.layers.single { it.id == "peer" }.model!!
        assertNotNull(installed.mesh)
        assertNotNull(installed.texture)
        assertTrue(artifacts.containsKey("model_peer.obj"))
        // And nothing was echoed back for it.
        assertTrue(emitted.filterIsInstance<Op.ModelData>().none { it.layerId == "peer" })
    }
}
