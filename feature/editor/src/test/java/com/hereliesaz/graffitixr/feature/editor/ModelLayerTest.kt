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
import com.hereliesaz.graffitixr.common.model.is3DLayer
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
        assertTrue(layer.plane3D)
        assertTrue(layer.is3DLayer)
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
            OverlayLayer(id = "old", name = "head", uri = android.net.Uri.parse(""), plane3D = true, model = ModelLayerContent("model.obj", "model_texture.png")),
        ))
        idle()
        val layers = vm.uiState.value.layers
        assertEquals("old", layers.last().id)
        val content = layers.last().model!!
        assertNotNull(content.mesh)
        assertEquals(Color.GREEN, content.texture!!.pixels()!!.getPixel(10, 10))
        assertTrue(layers.last().is3DLayer)
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
            OverlayLayer(id = "m", name = "M", uri = android.net.Uri.parse(""), plane3D = true, model = ModelLayerContent("model_m.obj")),
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
        val peerLayer = com.hereliesaz.graffitixr.common.model.Layer("peer", "P", plane3D = true, model = ModelLayerContent("model_peer.obj"))
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
