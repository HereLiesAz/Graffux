@file:Suppress("MaxLineLength", "MagicNumber", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.model.ViewSelection
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Benchmark-style measurement of what the editor does on every camera change while a surface selection is
 * active: [SurfaceProjection.of] (rebuilt per camera) plus [SurfaceViewSelection.refresh] (the footprint, i.e. the
 * marching ants' rings). It prints median / p95 timings per scenario and asserts only a loose regression bound
 * (see [BOUND_MS]); it is JVM wall-clock time on the CI host, not a phone, so it says how the cost scales and
 * where it goes, never whether a given device holds 60 fps.
 */
class SurfaceFootprintBenchmarkTest {
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

    /** A 256 x 256 = 65 536-vertex relief (the app's cap) with rolling heights. */
    private fun relief(): ReliefSurface {
        val n = 256
        val depth = FloatArray(n * n) { i ->
            val x = (i % n) / n.toFloat()
            val y = (i / n) / n.toFloat()
            0.5f + 0.4f * sin(x * 9f) * cos(y * 7f)
        }
        return ReliefSurface.build(depth, n, n, 2048, 2048, 100f, 100f, 900f, 700f, ReliefOptions(smoothingRadius = 0))!!
    }

    private class Scenario(val name: String, val owner: SurfaceOwner, val hull: SurfaceHull, val selection: Selection)

    /** A wobbly closed ring of [points] vertices around ([cx], [cy]) with radii ([rx], [ry]): a traced wand contour. */
    private fun blob(cx: Float, cy: Float, rx: Float, ry: Float, points: Int): SelectionRing = SelectionRing(
        List(points) { k ->
            val a = 2.0 * Math.PI * k / points
            val wob = 1f + 0.05f * sin(7.0 * a).toFloat()
            Offset(cx + rx * wob * cos(a).toFloat(), cy + ry * wob * sin(a).toFloat())
        },
    )

    private fun scenarios(): List<Scenario> {
        val out = ArrayList<Scenario>()
        val cubeOwner = SurfaceMath.ownerOf(
            listOf(Layer("g", "G", type = LayerType.GROUP, surface = cube), Layer("p", "P", parentId = "g")), "p",
        )!!
        val cubeHull = SurfaceHull.of(cube)!!
        for (pts in listOf(64, 1024)) {
            val views = SurfaceView.entries.mapNotNull { v ->
                val r = cubeHull.regionRect(v, 0) ?: return@mapNotNull null
                ViewSelection(v, 0, listOf(blob((r[0] + r[2]) / 2, (r[1] + r[3]) / 2, (r[2] - r[0]) * 0.45f, (r[3] - r[1]) * 0.45f, pts)))
            }
            out += Scenario("hull(cube) ring=$pts x ${views.size} views", cubeOwner, cubeHull, wandOf(views))
        }
        // A round outline (96 sides) swept through a box: many more, smaller triangles than the cube.
        val round = SurfaceProfile(
            List(96) { k ->
                val a = 2.0 * Math.PI * k / 96
                listOf(500f + 220f * cos(a).toFloat(), 400f + 220f * sin(a).toFloat(), 0f)
            }.flatten(),
            0f, 0f, 1f,
        ).copy(side = cube.side, top = cube.top)
        val roundOwner = SurfaceMath.ownerOf(
            listOf(Layer("g", "G", type = LayerType.GROUP, surface = round), Layer("p", "P", parentId = "g")), "p",
        )!!
        SurfaceHull.of(round)?.let { roundHull ->
            for (pts in listOf(64, 1024)) {
                val views = SurfaceView.entries.mapNotNull { v ->
                    val r = roundHull.regionRect(v, 0) ?: return@mapNotNull null
                    ViewSelection(v, 0, listOf(blob((r[0] + r[2]) / 2, (r[1] + r[3]) / 2, (r[2] - r[0]) * 0.45f, (r[3] - r[1]) * 0.45f, pts)))
                }
                out += Scenario("hull(round96) ring=$pts x ${views.size} views", roundOwner, roundHull, wandOf(views))
            }
        }
        return out
    }

    /**
     * A relief is not a hull ([SurfaceProfile.hasHull] is false, [SurfaceHull.of] is null), so it has no per-camera
     * footprint pass: its marquee maps the stored flat ring through [SurfaceMath.flatToWorld] inside the draw, for the
     * camera being drawn. This times that mapping for rings on a 65 536-vertex relief.
     */
    @Test fun reliefMarqueeMappingCostPerFrame() {
        val rel = relief()
        check(rel.cols * rel.rows >= 65_000) { "relief is ${rel.cols}x${rel.rows}" }
        val owner = SurfaceMath.ownerOf(
            listOf(Layer("g", "G", type = LayerType.GROUP, surface = rel.toProfile()), Layer("p", "P", parentId = "g")), "p",
        )!!
        assertTrue("a relief must not be a hull", !owner.profile.hasHull && SurfaceHull.of(owner.profile) == null)
        val lines = ArrayList<String>()
        var worst = 0.0
        for (pts in listOf(64, 1024, 8192)) {
            val ring = blob(500f, 400f, 300f, 250f, pts)
            val ms = ArrayList<Double>()
            for (i in 0 until 3 + 11) {
                val cam = SceneCamera(yaw = 0.4f + 0.013f * i, pitch = 0.3f)
                val t0 = System.nanoTime()
                var mapped = 0
                for (p in ring.path) if (SurfaceMath.flatToWorld(p.x, p.y, owner, cam, w, h) != null) mapped++
                val t = (System.nanoTime() - t0) / 1e6
                check(mapped > 0)
                if (i >= 3) ms += t
            }
            worst = maxOf(worst, median(ms))
            lines += "%-40s flatToWorld med=%7.2fms p95=%7.2fms".format("relief 65k ring=$pts", median(ms), p95(ms))
        }
        println("=== ReliefMarqueeMapping (JVM) ===")
        lines.forEach(::println)
        assertTrue("worst median ${"%.0f".format(worst)}ms exceeds $BOUND_MS ms", worst < BOUND_MS)
    }

    private fun wandOf(views: List<ViewSelection>): Selection =
        Selection(
            rings = emptyList(), canvasSize = IntSize(w.toInt(), h.toInt()),
            surface = com.hereliesaz.graffitixr.common.model.SurfaceSelection("p", views),
        )

    private fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]
    private fun p95(xs: List<Double>) = xs.sorted()[(xs.size * 95 / 100).coerceAtMost(xs.size - 1)]

    @Test fun footprintRecomputeCostPerCameraChange() {
        val warm = 3
        val runs = 11
        val cams = List(warm + runs) { SceneCamera(yaw = 0.4f + 0.013f * it, pitch = 0.3f) }
        val lines = ArrayList<String>()
        var worst = 0.0
        for (sc in scenarios()) {
            val projMs = ArrayList<Double>()
            val fpMs = ArrayList<Double>()
            var rings = 0
            var tris = 0
            for ((i, cam) in cams.withIndex()) {
                val t0 = System.nanoTime()
                val proj = SurfaceProjection.of(sc.hull, sc.owner, cam, w, h)
                val t1 = System.nanoTime()
                val next = SurfaceViewSelection.refresh(sc.selection, sc.owner, proj, w, h)
                val t2 = System.nanoTime()
                if (i >= warm) {
                    projMs += (t1 - t0) / 1e6
                    fpMs += (t2 - t1) / 1e6
                }
                rings = next.rings.size
                tris = SurfaceView.entries.sumOf { proj.triangles(it).size }
            }
            val total = projMs.zip(fpMs) { a, b -> a + b }
            worst = maxOf(worst, median(total))
            lines += "%-40s tris=%-7d rings=%-6d projection med=%7.1fms p95=%7.1fms | footprint med=%7.1fms p95=%7.1fms | total med=%7.1fms (frame budget 16.7ms)"
                .format(sc.name, tris, rings, median(projMs), p95(projMs), median(fpMs), p95(fpMs), median(total))
        }
        println("=== SurfaceFootprintBenchmark (JVM, ${Runtime.getRuntime().availableProcessors()} cpus) ===")
        lines.forEach(::println)
        assertTrue("no scenario ran", lines.isNotEmpty())
        // Loose regression bound: far above what is measured, so only an order-of-magnitude regression trips it.
        assertTrue("worst median total ${"%.0f".format(worst)}ms exceeds $BOUND_MS ms", worst < BOUND_MS)
    }

    private companion object {
        const val BOUND_MS = 5_000.0
    }
}
