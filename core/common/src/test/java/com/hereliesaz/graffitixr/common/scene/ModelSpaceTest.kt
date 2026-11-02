@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.mesh.ObjParser
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.is3DLayer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 3D model layer's maths (spec/3d-space.md, "3D model layers"). */
class ModelSpaceTest {
    private val w = 1000f
    private val h = 800f

    /** A unit quad in the OBJ's xy plane, facing +z (toward an OBJ viewer), UVs across it. */
    private val quad = ModelMesh(
        ObjParser.parse(
            """
            v -1 -1 0
            v 1 -1 0
            v 1 1 0
            v -1 1 0
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            f 1/1 2/2 3/3 4/4
            """.trimIndent(),
        ),
    )

    @Test
    fun `the mesh is framed on the box centre at a fixed share of the canvas, upright and facing the viewer`() {
        val toLocal = ModelSpace.meshToLocal(quad, w, h)
        val centre = toLocal.apply(0f, 0f, 0f)
        assertEquals(w / 2f, centre[0], 1e-3f)
        assertEquals(h / 2f, centre[1], 1e-3f)
        assertEquals(0f, centre[2], 1e-3f)
        // Radius 1 maps to 0.3 of the shorter side; OBJ up is screen up (scene y down).
        val top = toLocal.apply(0f, 1f, 0f)
        assertEquals(h / 2f - ModelSpace.FRAME_FRACTION * h, top[1], 1e-3f)
        // OBJ +z (toward the viewer) is nearer the camera: negative scene z.
        assertTrue(toLocal.apply(0f, 0f, 1f)[2] < 0f)
    }

    @Test
    fun `a touch hits the UV under it, through the default camera and through an orbit`() {
        val frame = SceneMath.frame(SceneCamera(), w, h)
        val toScene = ModelSpace.meshToScene(listOf(PlanePose()), quad, w, h)
        val half = ModelSpace.FRAME_FRACTION * h
        // Three quarters across, a quarter down from the top: u 0.75, v 0.75 (the parser flips v to GL's).
        val hit = ModelSpace.pick(quad, w / 2f + half / 2f, h / 2f - half / 2f, toScene, frame)!!
        assertEquals(0.75f, hit.u, 1e-3f)
        assertEquals(1f - 0.75f, hit.v, 1e-3f)
        assertNull(ModelSpace.pick(quad, 5f, 5f, toScene, frame))
        // A model placed by the view-plane rule under an orbited camera is hit at the centre too.
        val camera = SceneCamera().orbit(0.7f, -0.3f)
        val pose = SceneMath.viewPlanePose(camera, w, h)!!
        val placed = ModelSpace.meshToScene(listOf(pose), quad, w, h)
        val centre = ModelSpace.pick(quad, w / 2f, h / 2f, placed, SceneMath.frame(camera, w, h))!!
        assertEquals(0.5f, centre.u, 1e-3f)
        assertEquals(0.5f, centre.v, 1e-3f)
    }

    @Test
    fun `the view-plane pose faces the model at the camera`() {
        val camera = SceneCamera().orbit(1.1f, 0.4f)
        val frame = SceneMath.frame(camera, w, h)
        val pose = SceneMath.viewPlanePose(camera, w, h)!!
        val toScene = ModelSpace.meshToScene(listOf(pose), quad, w, h)
        // The quad's front normal (OBJ +z) points back at the eye.
        val n = SceneMath.normalize(toScene.applyVector(0f, 0f, 1f))
        assertEquals(-1f, SceneMath.dot(n, frame.forward), 1e-3f)
        // Its centre is on the orbit target.
        val c = toScene.apply(0f, 0f, 0f)
        for (i in 0..2) assertEquals(frame.target[i], c[i], 1e-2f)
    }

    @Test
    fun `texels per pixel follow the projected size`() {
        val frame = SceneMath.frame(SceneCamera(), w, h)
        val toScene = ModelSpace.meshToScene(listOf(PlanePose()), quad, w, h)
        // The quad spans 2 * 0.3 * h pixels and the whole 1024 texture.
        val k = ModelSpace.texelsPerPixel(quad, 0, toScene, frame, 1024, 1024)
        assertEquals(1024f / (2 * ModelSpace.FRAME_FRACTION * h), k, 1e-2f)
    }

    @Test
    fun `a model layer is a 3D layer and always a depth-pass member`() {
        val model = Layer("m", "M", model = ModelLayerContent("model_m.obj"))
        assertTrue(model.is3DLayer)
        val plan = DepthComposite.plan(listOf(Layer("a", "A"), model), SceneCamera())
        assertEquals(listOf("m"), plan.memberIds)
        assertTrue(DepthComposite.plan(listOf(Layer("a", "A")), SceneCamera()).isEmpty)
    }

    @Test
    fun `file names are checked before they reach the project folder`() {
        assertTrue(ModelLayerContent.isSafeFileName("model_1f-2.obj"))
        assertFalse(ModelLayerContent.isSafeFileName("../x.obj"))
        assertFalse(ModelLayerContent.isSafeFileName("a/b.obj"))
        assertFalse(ModelLayerContent.isSafeFileName(".hidden"))
        assertFalse(ModelLayerContent.isSafeFileName(""))
    }

    @Test
    fun `glTF carries the model's mesh, UVs and painted texture at the layer's placement`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 9)
        val pose = PlanePose(positionZ = 200f, rotationY = 20f)
        val scene = SceneGltfExporter.Scene(
            w, h, emptyList(), emptyList(), null,
            models = listOf(
                SceneGltfExporter.Model("Head", quad.mesh, ModelSpace.meshToScene(listOf(pose), quad, w, h), png, 1f),
                SceneGltfExporter.Model("Bare", quad.mesh, ModelSpace.meshToScene(listOf(PlanePose()), quad, w, h), null, 1f),
            ),
        )
        val gltf = Json.parseToJsonElement(SceneGltfExporter.encode(scene)).jsonObject
        val meshes = gltf["meshes"]!!.jsonArray
        assertEquals(listOf("Head", "Bare"), meshes.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        val attributes = meshes[0].jsonObject["primitives"]!!.jsonArray[0].jsonObject["attributes"]!!.jsonObject
        assertNotNull(attributes["POSITION"])
        assertNotNull(attributes["NORMAL"])
        assertNotNull(attributes["TEXCOORD_0"])
        // The painted one has its texture image; the bare one only a base colour.
        assertEquals(1, gltf["images"]!!.jsonArray.size)
        val materials = gltf["materials"]!!.jsonArray
        assertNotNull(materials[0].jsonObject["pbrMetallicRoughness"]!!.jsonObject["baseColorTexture"])
        assertNull(materials[1].jsonObject["pbrMetallicRoughness"]!!.jsonObject["baseColorTexture"])
        // Placed: the bounds sit behind the picture plane (glTF z toward the viewer is negative).
        val pos = attributes["POSITION"]!!.jsonPrimitive.content.toInt()
        val max = gltf["accessors"]!!.jsonArray[pos].jsonObject["max"]!!.jsonArray
        assertTrue(max[2].jsonPrimitive.content.toFloat() < 0f)
    }
}
