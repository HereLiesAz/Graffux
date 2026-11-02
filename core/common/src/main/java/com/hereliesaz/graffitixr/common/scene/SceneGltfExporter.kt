@file:Suppress("MagicNumber", "LongMethod", "LongParameterList", "MaxLineLength", "CyclomaticComplexMethod")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.mesh.SpatialGltfExporter
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * The whole 3D space as one self-contained glTF 2.0 JSON file (spec/3d-space.md, "Export"): each
 * paint layer a textured quad at its placement, each 3D strokes layer its tube mesh, each 3D model
 * layer its mesh with UVs and painted texture, and
 * optionally the camera.
 *
 * glTF axes are y up, z toward the viewer, metres; one metre is one spatial-ink unit
 * (`focal / 3` scene pixels), origin at the canvas centre on the picture plane. A 3D strokes layer
 * that was never moved therefore keeps its strokes' original coordinates.
 */
object SceneGltfExporter {
    /** A paint layer: its pixels as PNG and its quad's scene corners, TL, TR, BR, BL. */
    class Quad(
        val name: String,
        val png: ByteArray,
        val corners: List<FloatArray>,
        val opacity: Float,
        /**
         * Paint on a group surface (spec/3d-space.md, "Group surfaces"): triangles of x, y, z in
         * scene pixels then the image's u, v, emitted as a mesh in place of [corners]' quad.
         */
        val surface: FloatArray? = null,
    )

    /** A 3D strokes layer: its strokes and the map from stroke units to scene pixels. */
    class Strokes(val name: String, val strokes: List<SpatialStroke>, val strokeToScene: Affine3)

    /**
     * A 3D model layer: its mesh, the map from mesh coordinates to scene pixels, and its painted
     * texture as PNG (the paint over the model's base colour; null = unpainted, the base colour).
     */
    class Model(
        val name: String,
        val mesh: com.hereliesaz.graffitixr.common.mesh.Mesh,
        val meshToScene: Affine3,
        val png: ByteArray?,
        val opacity: Float,
    )

    class Scene(
        val width: Float,
        val height: Float,
        val quads: List<Quad>,
        val strokes: List<Strokes>,
        val camera: SceneCamera?,
        val models: List<Model> = emptyList(),
    )

    /** The base colour an unpainted model shows (RGB 0..1), as on the canvas. */
    val MODEL_BASE_COLOR = floatArrayOf(0.82f, 0.84f, 0.88f)

    fun encode(scene: Scene): String {
        val focal = SceneMath.focalLength(scene.height)
        val unit = focal / SceneMath.STROKE_UNITS_AT_DEFAULT_DISTANCE
        val cx = scene.width / 2f
        val cy = scene.height / 2f
        fun point(p: FloatArray) = floatArrayOf((p[0] - cx) / unit, -(p[1] - cy) / unit, -p[2] / unit)
        fun vector(v: FloatArray) = floatArrayOf(v[0], -v[1], -v[2])

        val bin = ByteArrayOutputStream()
        val views = ArrayList<String>()
        val accessors = ArrayList<String>()
        val meshes = ArrayList<String>()
        val materials = ArrayList<String>()
        val textures = ArrayList<String>()
        val images = ArrayList<String>()
        val nodes = ArrayList<String>()

        fun align() { while (bin.size() % 4 != 0) bin.write(0) }
        fun view(bytes: ByteArray, target: Int?): Int {
            align()
            val offset = bin.size()
            bin.write(bytes)
            views += buildString {
                append("""{"buffer":0,"byteOffset":$offset,"byteLength":${bytes.size}""")
                if (target != null) append(""","target":$target""")
                append("}")
            }
            return views.lastIndex
        }
        fun floats(values: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { b.putFloat(it) }
            return b.array()
        }
        fun ints(values: IntArray): ByteArray {
            val b = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { b.putInt(it) }
            return b.array()
        }
        fun accessor(view: Int, componentType: Int, count: Int, type: String, bounds: String = ""): Int {
            accessors += """{"bufferView":$view,"componentType":$componentType,"count":$count,"type":"$type"$bounds}"""
            return accessors.lastIndex
        }
        fun bounds(positions: FloatArray): String {
            val min = FloatArray(3) { Float.POSITIVE_INFINITY }
            val max = FloatArray(3) { Float.NEGATIVE_INFINITY }
            for (i in positions.indices) {
                min[i % 3] = minOf(min[i % 3], positions[i])
                max[i % 3] = maxOf(max[i % 3], positions[i])
            }
            return ""","min":[${min.joinToString(",")}],"max":[${max.joinToString(",")}]"""
        }

        for (quad in scene.quads) {
            val surface = quad.surface?.takeIf { it.size >= 15 }
            val pos: Int
            val uv: Int
            val idx: Int
            if (surface != null) {
                val n = surface.size / 5
                val positions = FloatArray(n * 3)
                val uvs = FloatArray(n * 2)
                for (i in 0 until n) {
                    point(floatArrayOf(surface[i * 5], surface[i * 5 + 1], surface[i * 5 + 2])).copyInto(positions, i * 3)
                    uvs[i * 2] = surface[i * 5 + 3]
                    uvs[i * 2 + 1] = surface[i * 5 + 4]
                }
                pos = accessor(view(floats(positions), 34962), 5126, n, "VEC3", bounds(positions))
                uv = accessor(view(floats(uvs), 34962), 5126, n, "VEC2")
                idx = accessor(view(ints(IntArray(n) { it }), 34963), 5125, n, "SCALAR")
            } else {
                require(quad.corners.size == 4) { "A layer quad has four corners" }
                val positions = FloatArray(12).also { out ->
                    quad.corners.forEachIndexed { i, c -> point(c).copyInto(out, i * 3) }
                }
                pos = accessor(view(floats(positions), 34962), 5126, 4, "VEC3", bounds(positions))
                uv = accessor(view(floats(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)), 34962), 5126, 4, "VEC2")
                idx = accessor(view(ints(intArrayOf(0, 3, 1, 1, 3, 2)), 34963), 5125, 6, "SCALAR")
            }
            images += """{"bufferView":${view(quad.png, null)},"mimeType":"image/png"}"""
            textures += """{"source":${images.lastIndex}}"""
            materials += """{"name":${json(quad.name)},"pbrMetallicRoughness":{"baseColorTexture":{"index":${textures.lastIndex}},""" +
                """"baseColorFactor":[1,1,1,${quad.opacity.coerceIn(0f, 1f)}],"metallicFactor":0.0,"roughnessFactor":1.0},""" +
                """"doubleSided":true,"alphaMode":"BLEND","extensions":{"KHR_materials_unlit":{}}}"""
            meshes += """{"name":${json(quad.name)},"primitives":[{"attributes":{"POSITION":$pos,"TEXCOORD_0":$uv},"indices":$idx,"material":${materials.lastIndex},"mode":4}]}"""
            nodes += """{"name":${json(quad.name)},"mesh":${meshes.lastIndex}}"""
        }

        for (layer in scene.strokes) {
            val mesh = SpatialGltfExporter.buildMesh(layer.strokes)
            if (mesh.indices.isEmpty()) continue
            val positions = FloatArray(mesh.positions.size)
            for (i in 0 until mesh.positions.size / 3) {
                val p = layer.strokeToScene.apply(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2])
                point(p).copyInto(positions, i * 3)
            }
            val pos = accessor(view(floats(positions), 34962), 5126, positions.size / 3, "VEC3", bounds(positions))
            val col = accessor(view(floats(mesh.colors), 34962), 5126, mesh.colors.size / 4, "VEC4")
            val idx = accessor(view(ints(mesh.indices), 34963), 5125, mesh.indices.size, "SCALAR")
            materials += """{"name":"Ink","pbrMetallicRoughness":{"metallicFactor":0.0,"roughnessFactor":1.0},""" +
                """"doubleSided":true,"alphaMode":"BLEND","extensions":{"KHR_materials_unlit":{}}}"""
            meshes += """{"name":${json(layer.name)},"primitives":[{"attributes":{"POSITION":$pos,"COLOR_0":$col},"indices":$idx,"material":${materials.lastIndex},"mode":4}]}"""
            nodes += """{"name":${json(layer.name)},"mesh":${meshes.lastIndex}}"""
        }
        for (model in scene.models) {
            val m = model.mesh
            val stride = com.hereliesaz.graffitixr.common.mesh.Mesh.FLOATS_PER_VERTEX
            val count = m.vertexCount
            if (count == 0 || m.indices.isEmpty()) continue
            val positions = FloatArray(count * 3)
            val normals = FloatArray(count * 3)
            val uvs = FloatArray(count * 2)
            for (i in 0 until count) {
                val o = i * stride
                point(model.meshToScene.apply(m.vertices[o], m.vertices[o + 1], m.vertices[o + 2])).copyInto(positions, i * 3)
                val n = model.meshToScene.applyVector(m.vertices[o + 3], m.vertices[o + 4], m.vertices[o + 5])
                SceneMath.normalize(vector(n)).copyInto(normals, i * 3)
                uvs[i * 2] = m.vertices[o + 6]
                uvs[i * 2 + 1] = m.vertices[o + 7]
            }
            val pos = accessor(view(floats(positions), 34962), 5126, count, "VEC3", bounds(positions))
            val nor = accessor(view(floats(normals), 34962), 5126, count, "VEC3")
            val uv = accessor(view(floats(uvs), 34962), 5126, count, "VEC2")
            val idx = accessor(view(ints(m.indices), 34963), 5125, m.indices.size, "SCALAR")
            val alpha = model.opacity.coerceIn(0f, 1f)
            val blend = if (alpha < 1f) ",\"alphaMode\":\"BLEND\"" else ""
            val png = model.png
            materials += if (png != null) {
                images += """{"bufferView":${view(png, null)},"mimeType":"image/png"}"""
                textures += """{"source":${images.lastIndex}}"""
                """{"name":${json(model.name)},"pbrMetallicRoughness":{"baseColorTexture":{"index":${textures.lastIndex}},""" +
                    """"baseColorFactor":[1,1,1,$alpha],"metallicFactor":0.0,"roughnessFactor":1.0}$blend}"""
            } else {
                val c = MODEL_BASE_COLOR
                """{"name":${json(model.name)},"pbrMetallicRoughness":{"baseColorFactor":[${c[0]},${c[1]},${c[2]},$alpha],""" +
                    """"metallicFactor":0.0,"roughnessFactor":1.0}$blend}"""
            }
            meshes += """{"name":${json(model.name)},"primitives":[{"attributes":{"POSITION":$pos,"NORMAL":$nor,"TEXCOORD_0":$uv},"indices":$idx,"material":${materials.lastIndex},"mode":4}]}"""
            nodes += """{"name":${json(model.name)},"mesh":${meshes.lastIndex}}"""
        }
        require(nodes.isNotEmpty()) { "Nothing in the scene to export" }

        var cameras = ""
        scene.camera?.let { camera ->
            val frame = SceneMath.frame(camera, scene.width, scene.height)
            val eye = point(frame.eye)
            // A glTF camera looks down its local -Z with +Y up.
            val right = vector(frame.right)
            val up = vector(floatArrayOf(-frame.down[0], -frame.down[1], -frame.down[2]))
            val back = vector(floatArrayOf(-frame.forward[0], -frame.forward[1], -frame.forward[2]))
            val q = quaternion(right, up, back)
            val yfov = SceneCamera.FOV_Y_DEGREES * PI.toFloat() / 180f
            val near = SceneMath.nearPlane(focal) / unit
            cameras = ""","cameras":[{"type":"perspective","perspective":{"yfov":$yfov,""" +
                """"aspectRatio":${scene.width / scene.height},"znear":$near}}]"""
            nodes += """{"name":"Camera","camera":0,"translation":[${eye.joinToString(",")}],"rotation":[${q.joinToString(",")}]}"""
        }

        align()
        val bytes = bin.toByteArray()
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return buildString {
            append("""{"asset":{"version":"2.0","generator":"Graffux 3D scene"},""")
            append(""""extensionsUsed":["KHR_materials_unlit"],""")
            append(""""scene":0,"scenes":[{"nodes":[${nodes.indices.joinToString(",")}]}],""")
            append(""""nodes":[${nodes.joinToString(",")}],""")
            append(""""meshes":[${meshes.joinToString(",")}],""")
            append(""""materials":[${materials.joinToString(",")}]""")
            if (textures.isNotEmpty()) {
                append(""","textures":[${textures.joinToString(",")}],"images":[${images.joinToString(",")}]""")
                append(""","samplers":[{"magFilter":9729,"minFilter":9729}]""")
            }
            append(cameras)
            append(""","buffers":[{"byteLength":${bytes.size},"uri":"data:application/octet-stream;base64,$encoded"}],""")
            append(""""bufferViews":[${views.joinToString(",")}],""")
            append(""""accessors":[${accessors.joinToString(",")}]}""")
        }
    }

    /** Unit quaternion (x, y, z, w) of the rotation whose matrix columns are [x], [y], [z]. */
    internal fun quaternion(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray {
        val m00 = x[0]; val m11 = y[1]; val m22 = z[2]
        val trace = m00 + m11 + m22
        return if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            floatArrayOf((y[2] - z[1]) / s, (z[0] - x[2]) / s, (x[1] - y[0]) / s, 0.25f * s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            floatArrayOf(0.25f * s, (y[0] + x[1]) / s, (z[0] + x[2]) / s, (y[2] - z[1]) / s)
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            floatArrayOf((y[0] + x[1]) / s, 0.25f * s, (z[1] + y[2]) / s, (z[0] - x[2]) / s)
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            floatArrayOf((z[0] + x[2]) / s, (z[1] + y[2]) / s, 0.25f * s, (x[1] - y[0]) / s)
        }
    }

    private fun json(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in '\u0000'..'\u001f' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
