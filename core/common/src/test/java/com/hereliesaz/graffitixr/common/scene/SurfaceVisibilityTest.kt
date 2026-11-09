@file:Suppress("MagicNumber", "MaxLineLength", "LongMethod", "CyclomaticComplexMethod", "LongParameterList", "ReturnCount")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paint reaches only what the camera sees (spec/3d-space.md): the visibility of surface points. */
class SurfaceVisibilityTest {
    private val w = 1000f
    private val h = 1600f

    private fun sheet(id: String, z: Float, x1: Float = 1200f, visible: Boolean = true, opacity: Float = 1f, parent: String? = null) =
        Layer(
            id = id, name = id, type = LayerType.GROUP, parentId = parent, isVisible = visible, opacity = opacity,
            surface = SurfaceProfile(listOf(0f, 0f, z, x1, 0f, z), 0f, 1f, 0f),
        )

    private fun outline(sweep: FloatArray, place: (Float, Float) -> FloatArray) = SurfaceProfile(
        listOf(300f to 200f, 700f to 200f, 700f to 600f, 300f to 600f).flatMap { place(it.first, it.second).toList() },
        sweep[0], sweep[1], sweep[2],
    )

    /** A closed box: x 300..700, y 200..600, z 0..400. */
    private val cubeProfile = outline(floatArrayOf(0f, 0f, 1f)) { x, y -> floatArrayOf(x, y, 0f) }.copy(
        side = outline(floatArrayOf(1f, 0f, 0f)) { z, y -> floatArrayOf(0f, y, z - 300f) },
        top = outline(floatArrayOf(0f, 1f, 0f)) { x, z -> floatArrayOf(x, 0f, z - 200f) },
    )
    private fun cube(visible: Boolean = true) = Layer("cube", "cube", type = LayerType.GROUP, surface = cubeProfile, isVisible = visible)

    private fun vis(layers: List<Layer>, cam: SceneCamera = SceneCamera()) = SurfaceVisibility.of(layers, cam, w, h)
    private fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
    private fun eyeDepth(cam: SceneCamera = SceneCamera()) = -SceneMath.frame(cam, w, h).eye[2]

    /** The point on the plane z = [z] in line, from the default eye, with [edge] (a point on the plane z = 0). */
    private fun behind(edge: FloatArray, z: Float): FloatArray {
        val eye = SceneMath.frame(SceneCamera(), w, h).eye
        val k = (z - eye[2]) / (edge[2] - eye[2])
        return FloatArray(3) { eye[it] + (edge[it] - eye[it]) * k }
    }

    @Test fun `a point behind a nearer surface is rejected`() {
        val v = vis(listOf(sheet("near", 0f, x1 = 600f), sheet("far", 200f)))
        assertFalse(v.visible(p(300f, 400f, 200f)))
    }

    @Test fun `a point in the open is accepted`() {
        val v = vis(listOf(sheet("near", 0f, x1 = 600f), sheet("far", 200f)))
        assertTrue(v.visible(p(1000f, 400f, 200f)))
        assertTrue(v.visible(p(300f, 400f, 0f)))
    }

    @Test fun `the far side of a solid is rejected and its front accepted`() {
        val v = vis(listOf(cube()))
        assertTrue(v.visible(p(500f, 400f, 0f)))
        assertFalse("back face", v.visible(p(500f, 400f, 400f)))
        assertFalse("a side wall seen from inside the box's outline", v.visible(p(300f, 400f, 200f)))
    }

    @Test fun `a point behind the eye is rejected`() {
        assertFalse(vis(listOf(cube())).visible(p(500f, 400f, -eyeDepth() - 50f)))
    }

    @Test fun `no surfaces hide nothing`() {
        assertTrue(vis(emptyList()).visible(p(1f, 2f, 3f)))
    }

    @Test fun `a dab centred either side of a silhouette is judged by its centre`() {
        // The near sheet ends at x = 600; far points whose eye ray passes that edge are the silhouette.
        val v = vis(listOf(sheet("near", 0f, x1 = 600f), sheet("far", 200f)))
        val p0 = behind(p(600f, 300f, 0f), 200f)
        assertFalse("centre just hidden", v.visible(p(p0[0] - 8f, p0[1], 200f)))
        assertTrue("centre just seen", v.visible(p(p0[0] + 8f, p0[1], 200f)))
    }

    @Test fun `a point on a surface seen at a grazing angle is accepted and one just below it is not`() {
        val floor = Layer("floor", "floor", type = LayerType.GROUP, surface = SurfaceProfile(listOf(200f, 1000f, 0f, 800f, 1000f, 0f), 0f, 0f, 1f))
        val v = vis(listOf(floor))
        for (z in listOf(300f, 400f, 500f, 550f)) {
            assertTrue("on the floor at z=$z", v.visible(p(500f, 1000f, z)))
            assertFalse("under the floor at z=$z", v.visible(p(500f, 1000f + 10f, z)))
        }
    }

    @Test fun `camera moved then paint uses the camera of the moment`() {
        val layers = listOf(sheet("near", 0f, x1 = 600f), sheet("far", 200f))
        val hidden = p(300f, 400f, 200f)
        assertFalse(vis(layers, SceneCamera()).visible(hidden))
        assertTrue(vis(layers, SceneCamera(yaw = 1.2f)).visible(hidden) || vis(layers, SceneCamera(yaw = -1.2f)).visible(hidden))
    }

    @Test fun `a hidden group, a group under a hidden group and a zero opacity group do not occlude`() {
        val far = sheet("far", 200f)
        val behind = p(300f, 400f, 200f)
        assertFalse(vis(listOf(sheet("near", 0f), far)).visible(behind))
        assertTrue(vis(listOf(sheet("near", 0f, visible = false), far)).visible(behind))
        assertTrue(vis(listOf(sheet("near", 0f, opacity = 0f), far)).visible(behind))
        val parent = Layer("p", "p", type = LayerType.GROUP, isVisible = false)
        assertTrue(vis(listOf(parent, sheet("near", 0f, parent = "p"), far)).visible(behind))
        assertTrue(vis(listOf(cube(visible = false))).visible(p(500f, 400f, 400f)))
    }

    @Test fun `flat layers have no surface to hide`() {
        val flat = Layer("a", "a")
        assertTrue(vis(listOf(flat)).isEmpty)
        assertTrue(vis(listOf(flat)).visible(p(500f, 800f, 300f)))
    }

    @Test fun `a flat point maps to the scene point it shows`() {
        val g = sheet("far", 200f)
        val leaf = Layer("l", "l", parentId = "far")
        val owner = SurfaceMath.ownerOf(listOf(g, leaf), "l")!!
        val s = SurfaceMath.flatToScene(300f, 100f, owner, w, h)!!
        assertEquals(200f, s[2], 1e-3f)
        assertEquals(300f, s[0], 1e-3f)
        val onCanvas = SurfaceMath.flatToWorld(300f, 100f, owner, SceneCamera(), w, h)!!
        val proj = SceneMath.frame(SceneCamera(), w, h).project(s)!!
        assertEquals(onCanvas.first, proj.first, 1e-3f)
    }

    @Test fun `the guard counts a refusal as hidden, not as a cut join`() {
        val hidden0 = SurfaceStrokeGuard.hidden
        val cuts0 = SurfaceStrokeGuard.cuts
        assertFalse(SurfaceStrokeGuard.visibleOk(false))
        assertTrue(SurfaceStrokeGuard.visibleOk(true))
        assertEquals(hidden0 + 1, SurfaceStrokeGuard.hidden)
        assertEquals(cuts0, SurfaceStrokeGuard.cuts)
    }

    @Test fun `a sheet occludes as far as its child image is drawn, not only to the guide grid`() {
        // A 100-wide sheet has a guide reach of ~132, but its full-height child image is drawn to y = 1600.
        val near = Layer(
            id = "near", name = "near", type = LayerType.GROUP,
            surface = SurfaceProfile(listOf(450f, 0f, 0f, 550f, 0f, 0f), 0f, 1f, 0f),
        )
        val child = Layer("c", "c", parentId = "near")
        val layers = listOf(near, child, sheet("far", 200f))
        val behind = p(500f, 800f, 200f)
        val full = { l: Layer -> if (l.id == "c") floatArrayOf(0f, 0f, w, h) else null }
        assertFalse("the drawn image hides it", SurfaceVisibility.of(layers, SceneCamera(), w, h, imageRect = full).visible(behind))
        assertTrue("the guide grid alone does not (the tap pick's extent)", SurfaceVisibility.of(layers, SceneCamera(), w, h, rendered = false, imageRect = full).visible(behind))
        assertTrue("a sheet with no drawn image falls back to the guide extent", SurfaceVisibility.of(layers, SceneCamera(), w, h, imageRect = { null }).visible(behind))
    }

    @Test fun `a surface just inside the near plane on an off-axis ray is clipped and does not occlude`() {
        val frame = SceneMath.frame(SceneCamera(), w, h)
        val nearPlane = SceneMath.nearPlane(frame.focal)
        fun wide(z: Float) = Layer("n", "n", type = LayerType.GROUP, surface = SurfaceProfile(listOf(0f, 0f, z, 6000f, 0f, z), 0f, 1f, 0f))
        val target = p(5000f, 300f, 500f)
        val clipped = wide(frame.eye[2] + 0.99f * nearPlane)
        assertTrue("camera depth 0.99 near is clipped away", vis(listOf(clipped)).visible(target))
        val drawn = wide(frame.eye[2] + 2f * nearPlane)
        assertFalse("camera depth 2 near is drawn and hides it", vis(listOf(drawn)).visible(target))
    }

    // ── Fuzz against an independent triangle oracle ──

    private class Tri(val a: FloatArray, val b: FloatArray, val c: FloatArray)

    private fun quad(p0: FloatArray, p1: FloatArray, p2: FloatArray, p3: FloatArray) = listOf(Tri(p0, p1, p2), Tri(p0, p2, p3))

    /** Möller-Trumbore: the ray parameter of the hit, or null. [dir] is a unit vector. */
    private fun hit(o: FloatArray, dir: FloatArray, t: Tri): Float? {
        fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
        fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        val e1 = sub(t.b, t.a)
        val e2 = sub(t.c, t.a)
        val pv = cross(dir, e2)
        val det = SceneMath.dot(e1, pv)
        if (abs(det) < 1e-9f) return null
        val tv = sub(o, t.a)
        val u = SceneMath.dot(tv, pv) / det
        if (u < 0f || u > 1f) return null
        val qv = cross(tv, e1)
        val v = SceneMath.dot(dir, qv) / det
        if (v < 0f || u + v > 1f) return null
        return SceneMath.dot(e2, qv) / det
    }

    @Test fun `fuzz no accepted point is occluded and none clearly occluded is accepted`() {
        val rnd = Random(11)
        var accepted = 0
        var rejected = 0
        repeat(400) {
            val sheetZ = -150f + rnd.nextFloat() * 700f
            val sheetX1 = 200f + rnd.nextFloat() * 700f
            val layers = listOf(sheet("s", sheetZ, x1 = sheetX1), cube())
            val cam = SceneCamera(
                yaw = (rnd.nextFloat() - 0.5f) * 3f, pitch = (rnd.nextFloat() - 0.5f) * 1.6f,
                distanceScale = 0.5f + rnd.nextFloat() * 1.5f,
            )
            val v = vis(layers, cam)
            val frame = SceneMath.frame(cam, w, h)
            val reach = SurfaceMath.localBounds(layers[0].surface!!)!!.second[1] + SurfaceMath.GRID_MARGIN
            val tris = ArrayList<Tri>()
            tris += quad(p(0f, 0f, sheetZ), p(sheetX1, 0f, sheetZ), p(sheetX1, reach, sheetZ), p(0f, reach, sheetZ))
            val c = listOf(
                quad(p(300f, 200f, 0f), p(700f, 200f, 0f), p(700f, 600f, 0f), p(300f, 600f, 0f)),
                quad(p(300f, 200f, 400f), p(700f, 200f, 400f), p(700f, 600f, 400f), p(300f, 600f, 400f)),
                quad(p(300f, 200f, 0f), p(300f, 200f, 400f), p(300f, 600f, 400f), p(300f, 600f, 0f)),
                quad(p(700f, 200f, 0f), p(700f, 200f, 400f), p(700f, 600f, 400f), p(700f, 600f, 0f)),
                quad(p(300f, 200f, 0f), p(700f, 200f, 0f), p(700f, 200f, 400f), p(300f, 200f, 400f)),
                quad(p(300f, 600f, 0f), p(700f, 600f, 0f), p(700f, 600f, 400f), p(300f, 600f, 400f)),
            )
            c.forEach { tris += it }
            repeat(40) {
                // A random point of the surfaces: on the sheet, or on one of the box's faces.
                val pt = if (rnd.nextBoolean()) {
                    p(rnd.nextFloat() * sheetX1, rnd.nextFloat() * reach, sheetZ)
                } else {
                    val f = c[rnd.nextInt(6)]
                    val t = f[rnd.nextInt(2)]
                    val a = rnd.nextFloat()
                    val b = rnd.nextFloat() * (1f - a)
                    FloatArray(3) { t.a[it] + (t.b[it] - t.a[it]) * a + (t.c[it] - t.a[it]) * b }
                }
                if (frame.toCamera(pt)[2] <= SceneMath.nearPlane(frame.focal) * 2f) return@repeat
                val d = sqrt((0..2).sumOf { ((pt[it] - frame.eye[it]) * (pt[it] - frame.eye[it])).toDouble() }).toFloat()
                val dir = FloatArray(3) { (pt[it] - frame.eye[it]) / d }
                val tol = v.toleranceAt(d)
                val nearest = tris.mapNotNull { hit(frame.eye, dir, it) }.filter { it > 0f }.minOrNull() ?: Float.MAX_VALUE
                val seen = v.visible(pt)
                if (seen) {
                    accepted++
                    assertTrue("accepted a point with a surface ${d - nearest} nearer (cam $cam, point ${pt.toList()})", nearest >= d - 3f * tol)
                } else {
                    rejected++
                    assertTrue("rejected a point nothing hides (nearest $nearest, d $d, point ${pt.toList()})", nearest < d - 0.2f * tol)
                }
            }
        }
        println("fuzz accepted=$accepted rejected=$rejected")
        assertTrue(accepted > 1000 && rejected > 1000)
    }
}
