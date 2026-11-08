@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.is3DIn
import com.hereliesaz.graffitixr.common.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Group surfaces (spec/3d-space.md, "Group surfaces"): capture, ray hits, arc-length paint. */
class SurfaceProfileTest {
    private val w = 1000f
    private val h = 800f
    private val camera = SceneCamera()
    private val identity = listOf(PlanePose())

    private fun line(from: Pair<Float, Float>, to: Pair<Float, Float>, n: Int = 40) = (0..n).map {
        val f = it / n.toFloat()
        (from.first + (to.first - from.first) * f) to (from.second + (to.second - from.second) * f)
    }

    private fun arc(r: Float = 200f, n: Int = 90) = (0..n).map {
        val a = PI.toFloat() * it / n
        (500f + r * cos(a)) to (500f - r * sin(a))
    }

    private val ell = line(200f to 200f, 200f to 600f) + line(200f to 600f, 600f to 600f).drop(1)

    @Test
    fun `a straight stroke captures a flat plane swept away from the viewer`() {
        val p = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)!!
        assertEquals(2, p.count)
        assertTrue(p.isFlat)
        assertEquals(600f, p.length, 0.5f)
        assertEquals(0f, p.point(0)[2], 1e-3f)
        assertEquals(1f, p.sweepZ, 1e-5f)
    }

    @Test
    fun `a curved stroke keeps its bend and its arc length`() {
        val p = SurfaceMath.capture(arc(), camera, w, h, identity)!!
        assertFalse(p.isFlat)
        assertTrue(p.count in 4..SurfaceMath.MAX_POINTS)
        assertEquals(PI.toFloat() * 200f, p.length, 4f)
    }

    @Test
    fun `an L-shaped stroke is two legs and a corner`() {
        val p = SurfaceMath.capture(ell, camera, w, h, identity)!!
        assertEquals(3, p.count)
        assertEquals(200f, p.point(1)[0], 0.5f)
        assertEquals(600f, p.point(1)[1], 0.5f)
        assertEquals(800f, p.length, 1f)
    }

    @Test
    fun `a tap is no profile`() {
        assertNull(SurfaceMath.capture(listOf(10f to 10f, 10.5f to 10f), camera, w, h, identity))
    }

    @Test
    fun `a ray meets a curved surface where it shows`() {
        val p = SurfaceMath.capture(arc(), camera, w, h, identity)!!
        val cam = SceneMath.frame(camera, w, h)
        for (touch in listOf(400f to 380f, 560f to 340f, 470f to 370f)) {
            val hit = SurfaceMath.hit(touch.first, touch.second, p, Affine3.IDENTITY, cam)
            assertNotNull("touch $touch", hit)
            val shown = cam.project(p.at(hit!!.u, hit.t))!!
            assertEquals(touch.first, shown.first, 0.5f)
            assertEquals(touch.second, shown.second, 0.5f)
            assertTrue(hit.t > 0f)
        }
    }

    @Test
    fun `paint maps by arc length, so the second leg starts where the first ends`() {
        val group = Layer("g", "G", type = LayerType.GROUP, surface = SurfaceMath.capture(ell, camera, w, h, identity))
        val child = Layer("c", "C", parentId = "g")
        val owner = SurfaceMath.ownerOf(listOf(group, child), "c")!!
        val onLeg = SurfaceMath.worldToFlat(400f, 600f, owner, camera, w, h)!!
        assertEquals(600f, onLeg.first, 1f)
        assertEquals(0f, onLeg.second, 1f)
        val back = SurfaceMath.flatToWorld(onLeg.first, onLeg.second, owner, camera, w, h)!!
        assertEquals(400f, back.first, 0.5f)
        assertEquals(600f, back.second, 0.5f)
    }

    @Test
    fun `a ray that misses the surface has no point`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)!!
        val group = Layer("g", "G", type = LayerType.GROUP, surface = floor)
        val owner = SurfaceMath.ownerOf(listOf(group, Layer("c", "C", parentId = "g")), "c")!!
        // Above the horizon line of a floor that recedes from the viewer.
        assertNull(SurfaceMath.worldToFlat(500f, 100f, owner, camera, w, h))
    }

    @Test
    fun `a group's pose moves its surface and every layer on it`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)!!
        val moved = Layer("g", "G", type = LayerType.GROUP, surface = floor, offset = androidx.compose.ui.geometry.Offset(0f, -100f))
        val owner = SurfaceMath.ownerOf(listOf(moved, Layer("c", "C", parentId = "g")), "c")!!
        val shown = SurfaceMath.flatToWorld(300f, 0f, owner, camera, w, h)!!
        assertEquals(500f, shown.first, 0.5f)
        assertEquals(500f, shown.second, 0.5f)
    }

    @Test
    fun `2D groups stay on the flat path and out of the depth pass`() {
        val layers = listOf(Layer("g", "G", type = LayerType.GROUP), Layer("c", "C", parentId = "g"))
        assertTrue(SceneRenderPath.isFlat(layers, camera))
        assertTrue(SceneRenderPath.sceneLayerIds(layers, camera).isEmpty())
        assertEquals(DepthComposite.EMPTY, DepthComposite.plan(layers, camera))
        assertNull(SurfaceMath.ownerOf(layers, "c"))
    }

    @Test
    fun `a profiled group takes its layers to the scene path`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)
        val layers = listOf(Layer("g", "G", type = LayerType.GROUP, surface = floor), Layer("c", "C", parentId = "g"))
        assertFalse(SceneRenderPath.isFlat(layers, camera))
        assertEquals(setOf("c"), DepthComposite.plan(layers, camera).surfaceIds)
    }

    @Test
    fun `an adjustment layer on a surface is drawn in the stack, not dropped`() {
        val wall = SurfaceMath.capture(ell, camera, w, h, identity)
        val adj = Layer(
            "adj", "Light", parentId = "g",
            adjustment = com.hereliesaz.graffitixr.common.photo.PhotoAdjustment(com.hereliesaz.graffitixr.common.photo.PhotoTool.LIGHT),
        )
        val layers = listOf(Layer("g", "G", type = LayerType.GROUP, surface = wall), Layer("c", "C", parentId = "g"), adj)
        val plan = DepthComposite.plan(layers, camera)
        assertFalse("adj" in plan.surfaceIds)
        assertFalse("adj" in plan)
        assertTrue("c" in plan.surfaceIds)
    }

    @Test
    fun `on a flat surface a blend mode or clip keeps the layer on the plane path`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)!!
        assertTrue(floor.isFlat)
        fun layers(surface: SurfaceProfile) = listOf(
            Layer("g", "G", type = LayerType.GROUP, surface = surface),
            Layer("plain", "Plain", parentId = "g"),
            Layer("mult", "Multiply", parentId = "g", blendMode = androidx.compose.ui.graphics.BlendMode.Multiply),
            Layer("clip", "Clip", parentId = "g", clipToLayerBelow = true),
        )
        val flat = DepthComposite.plan(layers(floor), camera)
        assertEquals(setOf("plain"), flat.surfaceIds)
        // A curved surface has no plane to fall back to: those stay on it.
        val curved = DepthComposite.plan(layers(SurfaceMath.capture(ell, camera, w, h, identity)!!), camera)
        assertEquals(setOf("plain", "mult", "clip"), curved.surfaceIds)
    }

    @Test
    fun `animation playback takes the live path for a surface group`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)
        val layers = listOf(Layer("g", "G", type = LayerType.GROUP, surface = floor), Layer("c", "C", parentId = "g"))
        assertTrue(needsPerspectiveRender(layers, camera))
        assertFalse(needsPerspectiveRender(listOf(Layer("a", "A")), camera))
        assertTrue(needsPerspectiveRender(listOf(Layer("a", "A", rotationX = 10f)), camera))
    }

    @Test
    fun `is3DIn over a prebuilt map agrees with the list form`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)
        val layers = listOf(
            Layer("g", "G", type = LayerType.GROUP, surface = floor), Layer("c", "C", parentId = "g"),
            Layer("f", "F"), Layer("t", "T", rotationY = 5f),
        )
        val byId = layers.associateBy { it.id }
        layers.forEach { assertEquals(it.id, it.is3DIn(layers), it.is3DIn(byId)) }
        assertEquals(SurfaceMath.ownerOf(layers, "c")?.group?.id, SurfaceMath.ownerOf(byId, "c")?.group?.id)
    }

    @Test
    fun `strips cover the image's unrolled bounds and stop at the profile's ends`() {
        val p = SurfaceMath.capture(ell, camera, w, h, identity)!!
        val mesh = SurfaceMath.mesh(p, -50f, 900f, 0f, 300f)
        // Two strips (one per leg), two triangles each, five floats a vertex.
        assertEquals(2 * 6 * 5, mesh.size)
        assertEquals(0f, (0 until mesh.size / 5).minOf { mesh[it * 5 + 3] }, 1e-3f)
        assertEquals(p.length, (0 until mesh.size / 5).maxOf { mesh[it * 5 + 3] }, 1e-3f)
    }

    @Test
    fun `glTF export emits a curved group's mesh`() {
        val p = SurfaceMath.capture(arc(), camera, w, h, identity)!!
        val mesh = SurfaceMath.mesh(p, 0f, p.length, 0f, 200f)
        val json = SceneGltfExporter.encode(
            SceneGltfExporter.Scene(w, h, listOf(SceneGltfExporter.Quad("curve", byteArrayOf(1, 2, 3), emptyList(), 1f, mesh)), emptyList(), null),
        )
        val n = mesh.size / 5
        assertTrue(json.contains("\"count\":$n,\"type\":\"VEC3\""))
        assertTrue(n > 6)
    }

    @Test
    fun `every visible group shows its grid, and only while the toggle is on`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)
        val layers = listOf(
            Layer("a", "A", type = LayerType.GROUP, surface = floor), Layer("a1", "A1", parentId = "a"),
            Layer("b", "B", type = LayerType.GROUP, surface = floor), Layer("b1", "B1", parentId = "b"),
            Layer("hid", "Hidden", type = LayerType.GROUP, surface = floor, isVisible = false),
            Layer("zero", "Zero", type = LayerType.GROUP, surface = floor, opacity = 0f),
            Layer("under", "Under", type = LayerType.GROUP, surface = floor, parentId = "hid"),
            Layer("noSurface", "Plain", type = LayerType.GROUP),
            Layer("flat", "Flat"),
        )
        assertEquals(listOf("a", "b"), SurfaceMath.gridGroupIds(layers, visible = true))
        assertEquals(emptyList<String>(), SurfaceMath.gridGroupIds(layers, visible = false))
    }

    @Test
    fun `each group's mesh follows its own surface, photo depth over skin over sheet`() {
        fun rect(a: Float) = listOf(0f to 0f, a to 0f, a to a, 0f to a)
        fun prof(sweep: FloatArray, place: (Float, Float) -> FloatArray) =
            SurfaceProfile(rect(100f).flatMap { place(it.first, it.second).toList() }, sweep[0], sweep[1], sweep[2])
        val front = prof(floatArrayOf(0f, 0f, 1f)) { x, y -> floatArrayOf(x, y, 0f) }
        val sheet = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)!!
        val skin = front.copy(side = prof(floatArrayOf(1f, 0f, 0f)) { z, y -> floatArrayOf(0f, y, z) })
        val cam = SceneMath.frame(camera, w, h)
        fun lines(p: SurfaceProfile) = SurfaceMath.gridLines(p, Affine3.IDENTITY, cam).map { it.toList() }
        // A single profile is its swept sheet (a hull would stop at its 100-unit box); the same front
        // with a side drawn is the hull's skin. Each is computed from its own profile alone.
        val sheetLines = lines(sheet)
        val frontLines = lines(front)
        val skinLines = lines(skin)
        assertTrue(sheetLines.isNotEmpty() && frontLines.isNotEmpty() && skinLines.isNotEmpty())
        assertTrue(frontLines != skinLines)
        assertEquals(sheetLines, lines(sheet))
        assertEquals(skinLines, lines(skin))
    }

    @Test
    fun `the grid follows the surface in perspective`() {
        val p = SurfaceMath.capture(ell, camera, w, h, identity)!!
        val cam = SceneMath.frame(camera, w, h)
        val lines = SurfaceMath.gridLines(p, Affine3.IDENTITY, cam)
        assertTrue(lines.size > 10)
        // The first profile line is the stroke itself; sweep lines run toward the vanishing point.
        val first = lines.first()
        assertEquals(200f, first[0], 0.5f)
        assertEquals(200f, first[1], 0.5f)
        val sweep = lines.last()
        assertEquals(p.length, 800f, 1f)
        assertTrue(kotlin.math.abs(sweep[2] - w / 2f) < kotlin.math.abs(sweep[0] - w / 2f))
        assertTrue(kotlin.math.abs(sweep[3] - h / 2f) < kotlin.math.abs(sweep[1] - h / 2f))
    }

    // ── Grid extent: the grid shows the surface, not an infinite plane ─────────────────────────

    private fun sweepEnd(p: SurfaceProfile, lines: List<FloatArray>, t: Float): Boolean {
        val cam = SceneMath.frame(camera, w, h)
        val a = p.at(p.length, t)
        val end = SurfaceMath.segment(a, a, cam)!!
        return lines.any { l -> abs(l[2] - end[0]) < 0.5f && abs(l[3] - end[1]) < 0.5f }
    }

    @Test
    fun `a single profile's grid is bounded to its box plus a margin`() {
        val p = SurfaceMath.capture(ell, camera, w, h, identity)!!
        val extent = SurfaceMath.gridExtent(p)
        // The profile's box is 400 x 400 (and no deep): the grid reaches its largest side plus a margin.
        assertEquals(400f + SurfaceMath.GRID_MARGIN, extent, 2f)
        val lines = SurfaceMath.gridLines(p, Affine3.IDENTITY, SceneMath.frame(camera, w, h))
        assertTrue("a sweep line ends at the extent", sweepEnd(p, lines, extent))
        // No profile line lies past the extent: the far one is the closing line at the extent itself.
        val farthest = SceneMath.frame(camera, w, h).let { cam ->
            val a = p.at(0f, extent + SurfaceMath.GRID_SPACING)
            SurfaceMath.segment(a, a, cam)!!
        }
        assertFalse(lines.any { l -> abs(l[0] - farthest[0]) < 0.5f && abs(l[1] - farthest[1]) < 0.5f })
    }

    @Test
    fun `an explicit sweep extent bounds the lines and closes the grid at it`() {
        val p = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)!!
        val cam = SceneMath.frame(camera, w, h)
        val lines = SurfaceMath.gridLines(p, Affine3.IDENTITY, cam, sweepExtent = 100f)
        // Profile lines at t = 0, 64 and the closing 100; sweep lines at u = 0, 64 ... and the end.
        val profileLines = 3
        val sweepLines = (p.length / SurfaceMath.GRID_SPACING).toInt() + 1 + 1
        assertEquals(profileLines + sweepLines, lines.size)
        assertTrue(sweepEnd(p, lines, 100f))
    }

    @Test
    fun `a multi-view group's grid is bounded to the hull's box`() {
        fun rect(a: Float, b: Float) = listOf(0f to 0f, a to 0f, a to b, 0f to b)
        fun prof(sweep: FloatArray, pts: List<Pair<Float, Float>>, place: (Float, Float) -> FloatArray) =
            SurfaceProfile(pts.flatMap { place(it.first, it.second).toList() }, sweep[0], sweep[1], sweep[2])
        val front = prof(floatArrayOf(0f, 0f, 1f), rect(100f, 100f)) { x, y -> floatArrayOf(x, y, 0f) }
        val hull = front.copy(side = prof(floatArrayOf(1f, 0f, 0f), rect(300f, 100f)) { z, y -> floatArrayOf(0f, y, z) })
        val extent = SurfaceMath.gridExtent(hull)
        // The side view is 300 deep along the front's sweep: the grid reaches the solid's far end.
        assertEquals(300f + SurfaceMath.GRID_MARGIN, extent, 2f)
        assertTrue(extent < SurfaceMath.GRID_MAX_LINES * SurfaceMath.GRID_SPACING)
        // The mesh is the solid's own faces: it no longer runs past the hull on the front sheet's sweep.
        val lines = SurfaceMath.gridLines(hull, Affine3.IDENTITY, SceneMath.frame(camera, w, h))
        assertTrue(lines.isNotEmpty())
    }

    private fun box(a: Float, b: Float) = listOf(0f to 0f, a to 0f, a to b, 0f to b)
    private fun view(sweep: FloatArray, pts: List<Pair<Float, Float>>, place: (Float, Float) -> FloatArray) =
        SurfaceProfile(pts.flatMap { place(it.first, it.second).toList() }, sweep[0], sweep[1], sweep[2])

    @Test
    fun `the wire mesh of a multi-view group covers the faces every view draws`() {
        val front = view(floatArrayOf(0f, 0f, 1f), box(200f, 200f)) { x, y -> floatArrayOf(x, y, 0f) }
        val side = view(floatArrayOf(1f, 0f, 0f), box(200f, 200f)) { z, y -> floatArrayOf(0f, y, z) }
        val top = view(floatArrayOf(0f, 1f, 0f), box(200f, 200f)) { x, z -> floatArrayOf(x, 0f, z) }
        fun onPlane(segs: List<FloatArray>, axis: Int, at: Float) =
            segs.any { s -> abs(s[axis] - at) < 0.5f && abs(s[axis + 3] - at) < 0.5f }

        val frontOnly = SurfaceHullGrid.segments(SurfaceHull.of(front)!!)
        assertTrue("front sheet is z = 0", onPlane(frontOnly, 2, 0f))

        val cube = front.copy(side = side, top = top)
        val segs = SurfaceHullGrid.segments(SurfaceHull.of(cube)!!)
        assertTrue("front face z = 0", onPlane(segs, 2, 0f))
        assertTrue("side face x = 0", onPlane(segs, 0, 0f))
        assertTrue("top face y = 0", onPlane(segs, 1, 0f))
        assertTrue("far cap z = 200", onPlane(segs, 2, 200f))
        assertTrue("far cap x = 200", onPlane(segs, 0, 200f))
        assertTrue("bottom y = 200", onPlane(segs, 1, 200f))
        // Nothing past the solid.
        assertTrue(segs.all { s -> s.all { it in -0.5f..200.5f } })

        // The projected mesh (what the canvas draws) follows the same hull.
        val cam = SceneMath.frame(camera, w, h)
        val projected = SurfaceMath.gridLines(cube, Affine3.IDENTITY, cam)
        assertTrue(projected.size > SurfaceMath.gridLines(front, Affine3.IDENTITY, cam).size)
    }

    @Test
    fun `the wire mesh is the outer skin of the one solid the three views define`() {
        // Front: an L (notch cut from the top right); side and top: squares. One solid, one skin.
        val l = listOf(0f to 0f, 200f to 0f, 200f to 100f, 100f to 100f, 100f to 200f, 0f to 200f)
        val front = view(floatArrayOf(0f, 0f, 1f), l) { x, y -> floatArrayOf(x, y, 0f) }
        val side = view(floatArrayOf(1f, 0f, 0f), box(200f, 200f)) { z, y -> floatArrayOf(0f, y, z) }
        val top = view(floatArrayOf(0f, 1f, 0f), box(200f, 200f)) { x, z -> floatArrayOf(x, 0f, z) }
        val hull = SurfaceHull.of(front.copy(side = side, top = top))!!
        val segs = SurfaceHullGrid.segments(hull)
        assertTrue(segs.isNotEmpty())

        fun inside(x: Float, y: Float, z: Float) = hull.contains(x, y, z)
        val probe = 1.5f
        for (s in segs) {
            val pts = listOf(
                floatArrayOf(s[0], s[1], s[2]), floatArrayOf(s[3], s[4], s[5]),
                floatArrayOf((s[0] + s[3]) / 2, (s[1] + s[4]) / 2, (s[2] + s[5]) / 2),
            )
            for (p in pts) {
                // Consistent with all three profiles: in the solid (on its boundary).
                assertTrue("vertex ${p.toList()} is outside the solid", inside(p[0], p[1], p[2]))
                // On the skin, not an interior sheet: some step off it leaves the solid.
                val dirs = (-1..1).flatMap { a -> (-1..1).flatMap { b -> (-1..1).map { c -> intArrayOf(a, b, c) } } }
                val leaves = dirs.any { d ->
                    (d[0] != 0 || d[1] != 0 || d[2] != 0) &&
                        !inside(p[0] + d[0] * probe, p[1] + d[1] * probe, p[2] + d[2] * probe)
                }
                assertTrue("vertex ${p.toList()} is interior, not on the skin", leaves)
            }
        }
        // No sheet drawn twice.
        val keys = segs.map { s ->
            val a = s.take(3).map { Math.round(it) }
            val b = s.drop(3).map { Math.round(it) }
            if (a.toString() <= b.toString()) "$a$b" else "$b$a"
        }
        assertEquals("duplicated segments", keys.size, keys.toSet().size)
    }

    // A flat tessellated patch: x, y, z, u, t per corner, triangles in a list.
    private fun tri(vararg c: FloatArray): FloatArray = c.flatMap { it.toList() }.toFloatArray()
    private fun vtx(x: Float, y: Float, z: Float, u: Float, t: Float) = floatArrayOf(x, y, z, u, t)

    @Test
    fun `a grid line along a tessellation edge is kept, continuous across slabs`() {
        // One face split into slabs at u = 0, 64, 128: the line at 64 is the slabs' shared edge.
        fun quad(u0: Float, u1: Float) = tri(
            vtx(u0, 0f, 0f, u0, 0f), vtx(u1, 0f, 0f, u1, 0f), vtx(u1, 50f, 0f, u1, 50f),
            vtx(u0, 0f, 0f, u0, 0f), vtx(u1, 50f, 0f, u1, 50f), vtx(u0, 50f, 0f, u0, 50f),
        )
        val mesh = quad(0f, 64f) + quad(64f, 128f)
        val segs = SurfaceHullGrid.fromMeshes(listOf(mesh), 64f)
        val at64 = segs.filter { abs(it[0] - 64f) < 0.2f && abs(it[3] - 64f) < 0.2f }
        assertEquals("one interior line at u = 64", 1, at64.size)
        assertEquals(50f, abs(at64[0][1] - at64[0][4]), 0.2f)
    }

    @Test
    fun `a crease between two faces of one mesh is kept and a triangulation diagonal is not`() {
        // Floor (z = 0) and wall (y = 50, rising in z) share the edge y = 50, z = 0, in ONE mesh.
        val floor = tri(vtx(0f, 0f, 0f, 0f, 0f), vtx(50f, 0f, 0f, 50f, 0f), vtx(50f, 50f, 0f, 50f, 50f)) +
            tri(vtx(0f, 0f, 0f, 0f, 0f), vtx(50f, 50f, 0f, 50f, 50f), vtx(0f, 50f, 0f, 0f, 50f))
        val wall = tri(vtx(0f, 50f, 0f, 0f, 60f), vtx(50f, 50f, 0f, 50f, 60f), vtx(50f, 50f, 50f, 50f, 110f)) +
            tri(vtx(0f, 50f, 0f, 0f, 60f), vtx(50f, 50f, 50f, 50f, 110f), vtx(0f, 50f, 50f, 0f, 110f))
        val segs = SurfaceHullGrid.fromMeshes(listOf(floor + wall), 640f)
        fun has(a: FloatArray, b: FloatArray) = segs.any { s ->
            (a.indices.all { abs(s[it] - a[it]) < 0.2f } && b.indices.all { abs(s[3 + it] - b[it]) < 0.2f }) ||
                (a.indices.all { abs(s[3 + it] - a[it]) < 0.2f } && b.indices.all { abs(s[it] - b[it]) < 0.2f })
        }
        assertTrue("the crease", has(floatArrayOf(0f, 50f, 0f), floatArrayOf(50f, 50f, 0f)))
        assertFalse("floor diagonal", has(floatArrayOf(0f, 0f, 0f), floatArrayOf(50f, 50f, 0f)))
        assertFalse("wall diagonal", has(floatArrayOf(0f, 50f, 0f), floatArrayOf(50f, 50f, 50f)))
    }

    @Test
    fun `a square front outline keeps its longitudinal corners`() {
        val side = 200f
        val sq = listOf(0f to 0f, side to 0f, side to side, 0f to side)
        val front = SurfaceProfile(sq.flatMap { listOf(it.first, it.second, 0f) }, 0f, 0f, 1f)
        val hull = SurfaceHull.of(front.copy(side = SurfaceProfile(sq.flatMap { listOf(0f, it.second, it.first) }, 1f, 0f, 0f)))!!
        val segs = SurfaceHullGrid.segments(hull)
        // The corner at (200, 200) runs the whole depth, 64 not dividing 200: only a crease puts it there.
        assertTrue(segs.any { s -> abs(s[0] - side) < 0.5f && abs(s[1] - side) < 0.5f && abs(s[3] - side) < 0.5f && abs(s[4] - side) < 0.5f && abs(s[2] - s[5]) > 100f })
    }
}
