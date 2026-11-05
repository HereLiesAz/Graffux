@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hull patches carry their paint images (spec/3d-space.md, "Rendering the hull"). */
@Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
class SurfaceHullMeshTest {
    private fun outline(sweep: FloatArray, c: List<Pair<Float, Float>>, place: (Float, Float) -> FloatArray) =
        SurfaceProfile(c.flatMap { place(it.first, it.second).toList() }, sweep[0], sweep[1], sweep[2])

    private val box = listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
    private val cube = outline(floatArrayOf(0f, 0f, 1f), box) { x, y -> floatArrayOf(x, y, 0f) }.copy(
        side = outline(floatArrayOf(1f, 0f, 0f), box) { z, y -> floatArrayOf(0f, y, z) },
        top = outline(floatArrayOf(0f, 1f, 0f), box) { x, z -> floatArrayOf(x, 0f, z) },
    )
    private val hull = SurfaceHull.of(cube)!!

    private fun rectOf(view: SurfaceView): FloatArray {
        val (w, h) = hull.paintSize(view)!!
        return floatArrayOf(0f, 0f, w, h)
    }

    @Test
    fun `every patch has uvs inside its own image`() {
        val all = SurfaceHullMesh.all(hull, Affine3.IDENTITY, ::rectOf)
        // Faces two views share are emitted once, by the earlier view.
        assertTrue(all.keys.containsAll(listOf(SurfaceView.FRONT, SurfaceView.SIDE)))
        for ((view, m) in all) {
            assertEquals("$view stride", 0, m.size % SurfaceHullMesh.STRIDE)
            assertEquals("$view triangles", 0, (m.size / SurfaceHullMesh.STRIDE) % 3)
            for (i in m.indices step SurfaceHullMesh.STRIDE) {
                assertTrue("$view u=${m[i + 3]}", m[i + 3] in -0.001f..1.001f)
                assertTrue("$view v=${m[i + 4]}", m[i + 4] in -0.001f..1.001f)
            }
        }
    }

    @Test
    fun `a point painted by a hit is shown at the same image point by the mesh`() {
        val hit = hull.hit(floatArrayOf(30f, 40f, -500f), floatArrayOf(0f, 0f, 1f), 1f)!!
        val view = hit.view!!
        val (px, py) = hull.paintPoint(hit)!!
        val rect = rectOf(view)
        val m = SurfaceHullMesh.textured(hull, view, Affine3.IDENTITY, rect)!!
        var found = false
        for (i in m.indices step 3 * SurfaceHullMesh.STRIDE) {
            val a = m.copyOfRange(i, i + 5)
            val b = m.copyOfRange(i + 5, i + 10)
            val c = m.copyOfRange(i + 10, i + 15)
            val w = bary(floatArrayOf(hit.x, hit.y, hit.z), a, b, c) ?: continue
            val u = w[0] * a[3] + w[1] * b[3] + w[2] * c[3]
            val v = w[0] * a[4] + w[1] * b[4] + w[2] * c[4]
            assertEquals(px / rect[2], u, 0.01f)
            assertEquals(py / rect[3], v, 0.01f)
            found = true
            break
        }
        assertTrue("the hit lies on a triangle of its view's patch", found)
    }

    @Test
    fun `the image rect scales the uvs and an empty rect or an undrawn view gives no patch`() {
        val r = rectOf(SurfaceView.FRONT)
        val half = floatArrayOf(0f, 0f, r[2] * 2, r[3])
        val full = SurfaceHullMesh.textured(hull, SurfaceView.FRONT, Affine3.IDENTITY, r)!!
        val wide = SurfaceHullMesh.textured(hull, SurfaceView.FRONT, Affine3.IDENTITY, half)!!
        assertEquals(full[3] / 2f, wide[3], 1e-4f)
        assertEquals(full[4], wide[4], 1e-4f)
        assertNull(SurfaceHullMesh.textured(hull, SurfaceView.FRONT, Affine3.IDENTITY, floatArrayOf(0f, 0f, 0f, 5f)))
        val frontOnly = SurfaceHull.of(cube.copy(side = null, top = null))
        assertNotNull(frontOnly)
        assertNull(SurfaceHullMesh.textured(frontOnly!!, SurfaceView.SIDE, Affine3.IDENTITY, r))
    }

    @Test
    fun `a hull exports as one textured mesh per view with matching accessors`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1)
        val patches = SurfaceHullMesh.all(hull, Affine3.IDENTITY, ::rectOf)
        val quads = patches.map { (view, mesh) ->
            SceneGltfExporter.Quad("Layer (${view.name.lowercase()})", png, List(4) { FloatArray(3) }, 1f, mesh)
        }
        val text = SceneGltfExporter.encode(SceneGltfExporter.Scene(1000f, 800f, quads, emptyList(), null))
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val meshes = root["meshes"] as kotlinx.serialization.json.JsonArray
        val images = root["images"] as kotlinx.serialization.json.JsonArray
        assertEquals(patches.size, meshes.size)
        assertEquals("one texture per view", patches.size, images.size)
        val accessors = root["accessors"] as kotlinx.serialization.json.JsonArray
        val counts = accessors.map { (it as kotlinx.serialization.json.JsonObject)["count"].toString().toInt() }
        for ((k, mesh) in patches.values.withIndex()) {
            val vertices = mesh.size / SurfaceHullMesh.STRIDE
            // position, uv, index accessors per mesh, in that order.
            assertEquals(listOf(vertices, vertices, vertices), counts.subList(k * 3, k * 3 + 3))
        }
        assertTrue(text.contains("(front)"))
    }

    private fun bary(p: FloatArray, a: FloatArray, b: FloatArray, c: FloatArray): FloatArray? {
        val v0 = FloatArray(3) { b[it] - a[it] }
        val v1 = FloatArray(3) { c[it] - a[it] }
        val v2 = FloatArray(3) { p[it] - a[it] }
        val d00 = SceneMath.dot(v0, v0)
        val d01 = SceneMath.dot(v0, v1)
        val d11 = SceneMath.dot(v1, v1)
        val d20 = SceneMath.dot(v2, v0)
        val d21 = SceneMath.dot(v2, v1)
        val den = d00 * d11 - d01 * d01
        if (kotlin.math.abs(den) < 1e-9f) return null
        val v = (d11 * d20 - d01 * d21) / den
        val w = (d00 * d21 - d01 * d20) / den
        val u = 1f - v - w
        val e = -1e-3f
        // Coplanar check: the hit must lie on this triangle's plane.
        val n = SceneMath.cross(v0, v1)
        val len = kotlin.math.sqrt(SceneMath.dot(n, n))
        if (len < 1e-9f || kotlin.math.abs(SceneMath.dot(v2, n)) / len > 0.01f) return null
        return if (u >= e && v >= e && w >= e) floatArrayOf(u, v, w) else null
    }
}
