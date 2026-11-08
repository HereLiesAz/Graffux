@file:Suppress("MagicNumber", "MaxLineLength", "CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round trip for paint on a group surface: touch -> eye ray -> surface -> flat paint point -> back to the
 * screen with the same camera must land within a pixel or two of the touch. Anything else paints away
 * from the finger.
 */
class FingerRoundTripTest {
    private val w = 1000f
    private val h = 800f
    private val rnd = Random(7)

    private fun curved() = SurfaceProfile(
        listOf(200f, 300f, 0f, 400f, 300f, 60f, 600f, 300f, 0f, 800f, 300f, -80f),
        0f, 1f, 0f,
    )

    private fun flatProfile() = SurfaceProfile(listOf(200f, 300f, 0f, 800f, 300f, 0f), 0f, 1f, 0f)

    private fun cam() = SceneCamera(
        yaw = (rnd.nextFloat() - 0.5f) * 3f, pitch = (rnd.nextFloat() - 0.5f) * 2.4f,
        distanceScale = 0.3f + rnd.nextFloat() * 3f,
        panX = (rnd.nextFloat() - 0.5f) * 400f, panY = (rnd.nextFloat() - 0.5f) * 400f,
    )

    private fun owner(profile: SurfaceProfile, pose: com.hereliesaz.graffitixr.common.scene.PlanePose, inner: PlanePose): SurfaceOwner {
        val g = Layer("g", "G", type = LayerType.GROUP, surface = profile).copy(
            offset = androidx.compose.ui.geometry.Offset(pose.offsetX, pose.offsetY),
            rotationX = pose.rotationX, rotationY = pose.rotationY, rotationZ = pose.rotationZ, positionZ = pose.positionZ,
        )
        val p = Layer("p", "P", parentId = "g").copy(
            offset = androidx.compose.ui.geometry.Offset(inner.offsetX, inner.offsetY),
        )
        return SurfaceMath.ownerOf(listOf(g, p), "p")!!
    }

    private fun run(profile: SurfaceProfile, name: String) {
        var worst = 0f
        var bad = 0
        var hits = 0
        repeat(4000) {
            val pose = PlanePose(rotationX = (rnd.nextFloat() - 0.5f) * 90f, rotationY = (rnd.nextFloat() - 0.5f) * 90f, rotationZ = (rnd.nextFloat() - 0.5f) * 90f)
            val o = owner(profile, pose, PlanePose())
            val c = cam()
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val flat = SurfaceMath.worldToFlat(x, y, o, c, w, h) ?: return@repeat
            hits++
            val back = SurfaceMath.flatToWorld(flat.first, flat.second, o, c, w, h)
            val d = if (back == null) Float.POSITIVE_INFINITY else hypot(back.first - x, back.second - y)
            if (!(d < 2f)) { bad++; if (d > worst || d.isNaN()) worst = d }
        }
        println("$name hits=$hits bad=$bad worst=$worst")
        assertTrue("$name: $bad of $hits touches paint away from the finger (worst $worst px)", bad == 0)
    }

    @Test fun flatSurfaceRoundTrips() = run(flatProfile(), "flat")
    @Test fun curvedSurfaceRoundTrips() = run(curved(), "curved")
}

/** A stroke across a silhouette: consecutive touches hit near and far faces; the straight stroke joining them is nowhere near the finger. */
class SilhouetteJumpTest {
    private val w = 1000f
    private val h = 800f
    private fun outline(sweep: FloatArray, place: (Float, Float) -> FloatArray) = SurfaceProfile(
        listOf(300f to 200f, 700f to 200f, 700f to 600f, 300f to 600f).flatMap { place(it.first, it.second).toList() },
        sweep[0], sweep[1], sweep[2],
    )
    private val cube = outline(floatArrayOf(0f, 0f, 1f)) { x, y -> floatArrayOf(x, y, 0f) }.copy(
        side = outline(floatArrayOf(1f, 0f, 0f)) { z, y -> floatArrayOf(0f, y, z - 300f) },
        top = outline(floatArrayOf(0f, 1f, 0f)) { x, z -> floatArrayOf(x, 0f, z - 300f) },
    )
    private val group = Layer("g", "G", type = LayerType.GROUP, surface = cube)
    private val leaf = Layer("p", "P", parentId = "g")
    private val owner = SurfaceMath.ownerOf(listOf(group, leaf), "p")!!
    private val hull = SurfaceHull.of(cube)!!

    private fun back(cam: SceneCamera, f: SurfaceMath.ViewFlat): Pair<Float, Float>? {
        val (ux, uy) = SurfaceMath.flatToUnrolled(f.x, f.y, owner.innerChain, w, h)
        val p = hull.pointAt(f.view, ux, uy) ?: return null
        val at = SceneMath.chainModel(owner.ownerChain, w, h).apply(p[0], p[1], p[2])
        return SceneMath.frame(cam, w, h).project(at)
    }

    @Test
    fun joinsAcrossSilhouettesExistAndTheGuardCutsThem() {
        val rnd = Random(3)
        var unguarded = 0
        var cut = 0
        var falseCut = 0
        var samples = 0
        repeat(1500) {
            val cam = SceneCamera(yaw = (rnd.nextFloat() - 0.5f) * 3f, pitch = (rnd.nextFloat() - 0.5f) * 1.6f)
            val x0 = rnd.nextFloat() * w
            val y0 = rnd.nextFloat() * h
            val a = rnd.nextFloat() * 6.28f
            var prev: SurfaceMath.ViewFlat? = null
            var px = 0f
            var py = 0f
            for (k in 0 until 120) {
                val x = x0 + k * 4f * kotlin.math.cos(a)
                val y = y0 + k * 4f * kotlin.math.sin(a)
                val cur = SurfaceMath.worldToViewFlat(x, y, owner, cam, w, h, hull)
                if (cur == null || cur.region != 0) { prev = null; continue }
                samples++
                val p = prev
                if (p != null && p.view == cur.view) {
                    val m = back(cam, SurfaceMath.ViewFlat(cur.view, (p.x + cur.x) / 2f, (p.y + cur.y) / 2f)) ?: return@repeat
                    // Ground truth, independent of the guard: walk the straight flat stroke and see how far
                    // from the touch step it is painted.
                    var worst = 0f
                    for (i in 1..7) {
                        val f = i / 8f
                        val q = back(cam, SurfaceMath.ViewFlat(cur.view, p.x + (cur.x - p.x) * f, p.y + (cur.y - p.y) * f)) ?: continue
                        val tx = px + (x - px) * f
                        val ty = py + (y - py) * f
                        worst = maxOf(worst, hypot(q.first - tx, q.second - ty))
                    }
                    val bad = worst > 12f
                    val ok = SurfaceStrokeGuard.joinOk(px, py, x, y, m.first, m.second, 0f)
                    if (bad) unguarded++
                    if (bad && !ok) cut++
                    if (!bad && !ok) falseCut++
                }
                prev = cur; px = x; py = y
            }
        }
        println("samples=$samples jumps(far flat step for 4px touch)=$unguarded cut=$cut falseCuts=$falseCut")
        assertTrue("a 4px touch step can be painted >12px from the finger on a hull (evidence of the cause)", unguarded > 0)
        assertTrue("the guard cuts every such jump ($cut of $unguarded)", cut == unguarded)
        assertTrue("and cuts no smooth step ($falseCut)", falseCut == 0)
    }
}
