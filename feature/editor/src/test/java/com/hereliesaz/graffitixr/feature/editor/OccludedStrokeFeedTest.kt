@file:Suppress("MagicNumber", "MaxLineLength", "ComplexCondition")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.common.scene.SurfaceStrokeGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stroke on a surface paints only what the camera sees (spec/3d-space.md, "Paint reaches only what the
 * camera sees"): a sample behind another surface is dropped like a miss, and a stroke that goes behind a
 * fold and comes back resumes as a new segment, without counting a join cut.
 */
class OccludedStrokeFeedTest {
    private val w = 1000f
    private val h = 1600f

    private fun sheet(id: String, z: Float, x1: Float) = Layer(
        id = id, name = id, type = LayerType.GROUP, surface = SurfaceProfile(listOf(0f, 0f, z, x1, 0f, z), 0f, 1f, 0f),
    )

    private fun plane(camera: SceneCamera = SceneCamera(), near: Layer = sheet("near", 0f, 600f)): ActivePlaneMapping {
        val layers = listOf(near, sheet("far", 200f, 1200f), Layer(id = "leaf", name = "leaf", parentId = "far"))
        return ActivePlaneMapping.of(
            EditorUiState(
                layers = layers, activeLayerId = "leaf", canvasSize = IntSize(w.toInt(), h.toInt()), sceneCamera = camera,
                viewportOffset = Offset.Zero, viewportZoom = 1f, viewportRotation = 0f,
            ),
        )
    }

    /** Where the far sheet's point (x, 100) shows on the canvas. */
    private fun touch(x: Float, camera: SceneCamera = SceneCamera()): Offset =
        SceneMath.frame(camera, w, h).project(floatArrayOf(x, 100f, 200f))!!.let { Offset(it.first, it.second) }

    private class Run(plane: ActivePlaneMapping, radius: Float = 4f) {
        val segments = mutableListOf<MutableList<Offset>>()
        val feed = PlaneStrokeFeed<Offset>(
            toLayer = { plane.toFlatByFootprint(it, radius) },
            start = { segments += mutableListOf(it) },
            point = { segments.last() += it },
            end = {},
            cancel = {},
            segmentEnd = {},
            sampleOk = { raw, mapped -> plane.flatOnFinger(raw, mapped, radius) },
            joinOk = { pr, pm, r, m -> plane.flatJoinOnFinger(pr, pm, r, m, radius) },
        )
        fun stroke(touches: List<Offset>) {
            touches.forEachIndexed { i, t -> if (i == 0) feed.onStart(t) else feed.onPoint(t) }
            feed.onEnd()
        }
    }

    private fun xs(from: Float, to: Float, step: Float) = generateSequence(from) { it + step }.takeWhile { if (step > 0) it <= to else it >= to }.toList()

    @Test fun `a stroke wholly in view is one segment`() {
        val run = Run(plane())
        run.stroke(xs(900f, 700f, -20f).map { touch(it) })
        assertEquals(1, run.segments.size)
        assertEquals(11, run.segments[0].size)
    }

    @Test fun `a stroke behind a fold and back out splits, dropping the hidden samples`() {
        val cuts = SurfaceStrokeGuard.cuts
        val run = Run(plane())
        // In view (x 900..700), behind the near sheet (x < ~610), then in view again.
        run.stroke((xs(900f, 100f, -20f) + xs(120f, 900f, 20f)).map { touch(it) })
        assertEquals("one segment out, one on re-entry", 2, run.segments.size)
        assertTrue(run.segments.all { it.size > 3 })
        assertEquals("a hidden sample is a miss, not a join cut", cuts, SurfaceStrokeGuard.cuts)
    }

    @Test fun `a single step over a hidden stretch is cut at the join, not bridged`() {
        // A narrow occluder shadows far x ~ 335..665 only; both ends of the step are in view, the middle is not.
        val strip = Layer(
            id = "near", name = "near", type = LayerType.GROUP,
            surface = SurfaceProfile(listOf(350f, 0f, 0f, 650f, 0f, 0f), 0f, 1f, 0f),
        )
        val cuts = SurfaceStrokeGuard.cuts
        val run = Run(plane(near = strip))
        run.stroke(listOf(touch(300f), touch(700f)))
        assertEquals("each end alone", 2, run.segments.size)
        assertEquals("a hidden middle is not a gap in the surface", cuts, SurfaceStrokeGuard.cuts)
        val open = Run(plane(near = strip.copy(isVisible = false)))
        open.stroke(listOf(touch(300f), touch(700f)))
        assertEquals("with the occluder hidden it is one stroke", 1, open.segments.size)
    }

    @Test fun `moving the camera changes what is painted`() {
        val hidden = touch(300f)
        val behind = Run(plane())
        behind.stroke(listOf(hidden, hidden + Offset(2f, 0f)))
        assertEquals(0, behind.segments.size)
        val cam = SceneCamera(yaw = 1.2f)
        val moved = Run(plane(cam))
        moved.stroke(listOf(touch(300f, cam), touch(300f, cam) + Offset(2f, 0f)))
        assertEquals(1, moved.segments.size)
    }

    @Test fun `a hidden near group does not hide the far one`() {
        val near = sheet("near", 0f, 600f).copy(isVisible = false)
        val run = Run(plane(near = near))
        run.stroke(xs(500f, 300f, -20f).map { touch(it) })
        assertEquals(1, run.segments.size)
    }

    // ── Finished Ink strokes go through the same guards ──

    @Test fun `a finished ink stroke behind a fold drops its hidden samples and splits`() {
        val p = plane()
        val path = (xs(900f, 100f, -20f) + xs(120f, 900f, 20f)).map { touch(it) }
        val segs = p.inkSegments(path, 4f)
        assertEquals("one segment out, one on re-entry", 2, segs.size)
        val kept = segs.sumOf { it.samples.size }
        assertTrue("hidden samples are dropped", kept < path.size - 5)
        val open = plane(near = sheet("near", 0f, 600f).copy(isVisible = false)).inkSegments(path, 4f)
        assertEquals(1, open.size)
        assertEquals(path.size, open[0].samples.size)
    }

    @Test fun `a finished ink step over a hidden stretch is cut at the join`() {
        val strip = Layer(
            id = "near", name = "near", type = LayerType.GROUP,
            surface = SurfaceProfile(listOf(350f, 0f, 0f, 650f, 0f, 0f), 0f, 1f, 0f),
        )
        assertEquals(2, plane(near = strip).inkSegments(listOf(touch(300f), touch(700f)), 4f).size)
        assertEquals(1, plane(near = strip.copy(isVisible = false)).inkSegments(listOf(touch(300f), touch(700f)), 4f).size)
    }

    // ── A cap's join is cut where another surface hides its middle ──

    @Test fun `a cap join with a surface hiding its middle is cut, and not when nothing hides it`() {
        // A front outline plus a top view swept slightly off vertical: the box closes the z end that no outline bounds.
        val hullGroup = listOf(0.196f, -0.196f).map { tilt ->
            val top = SurfaceProfile(listOf(300f, 600f, -40f, 700f, 600f, -40f, 700f, 600f, 40f, 300f, 600f, 40f), 0f, -0.98f, tilt)
            val prof = SurfaceProfile(listOf(300f, 200f, 0f, 700f, 200f, 0f, 700f, 600f, 0f, 300f, 600f, 0f), 0f, 0f, 1f).copy(top = top)
            Layer(id = "hull", name = "hull", type = LayerType.GROUP, surface = prof)
        }.first { g ->
            val cap = com.hereliesaz.graffitixr.common.scene.SurfaceHull.of(g.surface!!)!!.capMeshByView().values.firstOrNull()
            cap != null && cap[2] < 0f
        }
        val hull = com.hereliesaz.graffitixr.common.scene.SurfaceHull.of(hullGroup.surface!!)!!
        val zc = hull.capMeshByView().values.first()[2]
        val cam = SceneCamera()
        fun px(x: Float) = SceneMath.frame(cam, w, h).project(floatArrayOf(x, 300f, zc))!!.let { Offset(it.first, it.second) }
        fun planeWith(occluder: Layer) = ActivePlaneMapping.of(
            EditorUiState(
                layers = listOf(occluder, hullGroup, Layer(id = "leaf", name = "leaf", parentId = "hull")),
                activeLayerId = "leaf", canvasSize = IntSize(w.toInt(), h.toInt()), sceneCamera = cam,
                viewportOffset = Offset.Zero, viewportZoom = 1f, viewportRotation = 0f,
            ),
        )
        val strip = Layer(
            id = "occ", name = "occ", type = LayerType.GROUP,
            surface = SurfaceProfile(listOf(420f, 250f, zc - 50f, 580f, 250f, zc - 50f), 0f, 1f, 0f),
        )
        val p = planeWith(strip)
        val a = p.toViewFlatByFootprint(px(350f), 0f)!!
        val b = p.toViewFlatByFootprint(px(650f), 0f)!!
        assertTrue("both touches are on the cap", a.region != 0 && b.region != 0 && a.region == b.region)
        assertTrue("ends in view", p.viewFlatOnFingerWorld(p.world(px(350f)), a, 4f) && p.viewFlatOnFingerWorld(p.world(px(650f)), b, 4f))
        fun join(m: ActivePlaneMapping) = m.viewJoinOnFingerWorld(
            m.world(px(350f)), Offset(a.x, a.y), m.world(px(650f)), b.view, b.region, Offset(b.x, b.y), 4f,
        )
        assertTrue("a cap join is cut where a surface hides its middle", !join(p))
        assertTrue("and kept when nothing does", join(planeWith(strip.copy(isVisible = false))))
    }

    // ── A crease another surface hides is not bridged ──

    private class Creased(val plane: ActivePlaneMapping, val a: Offset, val b: Offset)

    private fun creased(hide: Boolean): Creased {
        fun outline(sweep: FloatArray, place: (Float, Float) -> FloatArray) = SurfaceProfile(
            listOf(300f to 200f, 700f to 200f, 700f to 600f, 300f to 600f).flatMap { place(it.first, it.second).toList() },
            sweep[0], sweep[1], sweep[2],
        )
        val cubeProfile = outline(floatArrayOf(0f, 0f, 1f)) { x, y -> floatArrayOf(x, y, 0f) }.copy(
            side = outline(floatArrayOf(1f, 0f, 0f)) { z, y -> floatArrayOf(0f, y, z - 300f) },
            top = outline(floatArrayOf(0f, 1f, 0f)) { x, z -> floatArrayOf(x, 0f, z - 200f) },
        )
        for (yaw in listOf(0.7f, -0.7f)) for (cx in listOf(300f, 700f)) {
            val cam = SceneCamera(yaw = yaw)
            val frame = SceneMath.frame(cam, w, h)
            val crease = floatArrayOf(cx, 400f, 0f)
            val c = frame.project(crease)!!
            val k = (-60f - frame.eye[2]) / (crease[2] - frame.eye[2])
            val q = FloatArray(3) { frame.eye[it] + (crease[it] - frame.eye[it]) * k }
            val strip = Layer(
                id = "occ", name = "occ", type = LayerType.GROUP, isVisible = hide,
                surface = SurfaceProfile(listOf(q[0] - 15f, q[1] - 10f, -60f, q[0] + 15f, q[1] - 10f, -60f), 0f, 1f, 0f),
            )
            val plane = ActivePlaneMapping.of(
                EditorUiState(
                    layers = listOf(strip, Layer("cube", "cube", type = LayerType.GROUP, surface = cubeProfile), Layer("leaf", "leaf", parentId = "cube")),
                    activeLayerId = "leaf", canvasSize = IntSize(w.toInt(), h.toInt()), sceneCamera = cam,
                    viewportOffset = Offset.Zero, viewportZoom = 1f, viewportRotation = 0f,
                ),
            )
            val a = Offset(c.first - 40f, c.second)
            val b = Offset(c.first + 40f, c.second)
            val fa = plane.toViewFlatByFootprint(a, 0f)
            val fb = plane.toViewFlatByFootprint(b, 0f)
            if (fa != null && fb != null && fa.view != fb.view && fa.region == 0 && fb.region == 0) return Creased(plane, a, b)
        }
        error("no camera sees two faces of the box across a crease")
    }

    @Test fun `a crease another surface hides is cut, not bridged, for a raster stroke`() {
        for (hide in listOf(true, false)) {
            val (plane, a, b) = creased(hide).let { Triple(it.plane, it.a, it.b) }
            var segments = 0
            var points = 0
            val feed = ViewStrokeFeed<com.hereliesaz.graffitixr.common.azphalt.BrushSample>(
                toView = { s ->
                    plane.toViewFlatByFootprint(Offset(s.x, s.y), 0f)?.let { f -> ViewSample(f.view, s, s.copy(x = f.x, y = f.y), f.region) }
                },
                bridge = { x, y -> plane.bridgeViews(x, y) },
                start = { _, _ -> segments++ },
                point = { points++ },
                end = {}, cancel = {}, segmentEnd = {},
                sampleOk = { v -> plane.viewFlatOnFinger(Offset(v.raw.x, v.raw.y), com.hereliesaz.graffitixr.common.scene.SurfaceMath.ViewFlat(v.view, v.flat.x, v.flat.y, v.region), 2f) },
                joinOk = { x, y -> plane.viewJoinOnFinger(Offset(x.raw.x, x.raw.y), x, Offset(y.raw.x, y.raw.y), y, 2f) },
            )
            fun sample(o: Offset) = com.hereliesaz.graffitixr.common.azphalt.BrushSample(o.x, o.y)
            feed.onStart(sample(a))
            feed.onPoint(sample(b))
            feed.onEnd()
            assertEquals(2, segments)
            if (hide) assertEquals("a hidden crease paints no bridge", 0, points) else assertTrue("a seen crease is bridged", points > 0)
        }
    }

    @Test fun `a crease another surface hides is cut, not bridged, for a finished ink stroke`() {
        val hidden = creased(true)
        val cut = hidden.plane.inkSegments(listOf(hidden.a, hidden.b), 0f)
        assertEquals(2, cut.size)
        assertTrue(cut.all { it.samples.size == 1 })
        val open = creased(false)
        val bridged = open.plane.inkSegments(listOf(open.a, open.b), 0f)
        assertEquals(2, bridged.size)
        assertTrue(bridged.all { it.samples.size == 2 })
    }
}
