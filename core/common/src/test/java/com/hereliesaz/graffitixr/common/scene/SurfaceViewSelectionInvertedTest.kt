@file:Suppress("MaxLineLength", "MagicNumber", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "NestedBlockDepth", "LongMethod")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An inverted lasso on a multi-view surface selects everything *outside* the lasso on every view the
 * surface shows, bounded to the faces that face the camera like any other selection (spec/3d-space.md,
 * "Selection on a multi-view surface"). Regression: the lasso-to-view cut judged an inverted lasso by whether
 * an additive ring reached the view, which an inverted lasso never satisfies, so it was a miss on every view.
 */
class SurfaceViewSelectionInvertedTest {
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
    private val w = 1000f
    private val h = 800f
    private val canvas = IntSize(1000, 800)
    private val cam = SceneCamera(yaw = 0.6f, pitch = 0.3f)
    private val projection = SurfaceProjection.of(hull, owner, cam, w, h)

    private val touches = buildList {
        // Off the 10px lattice, so no probe sits exactly on a vertex or an edge of the triangulation.
        var y = 2.3f
        while (y < h) {
            var x = 3.7f
            while (x < w) {
                SurfaceMath.worldToViewFlat(x, y, owner, cam, w, h, hull)?.let { add(Offset(x, y) to it) }
                x += 10f
            }
            y += 10f
        }
    }

    /** Not within 12px of a crease, where the face under the viewer changes (a touch exactly on a seam is luck). */
    private fun interior(at: Offset, f: SurfaceMath.ViewFlat) = listOf(Offset(12f, 0f), Offset(-12f, 0f), Offset(0f, 12f), Offset(0f, -12f)).all {
        val o = SurfaceMath.worldToViewFlat(at.x + it.x, at.y + it.y, owner, cam, w, h, hull)
        o != null && o.view == f.view && o.region == f.region
    }

    private val views = touches.map { it.second.view }.distinct()

    /** A touch near the middle of [view]'s sheet, so a lasso around it lies on the face. */
    private fun middleOf(view: SurfaceView): Offset {
        val own = touches.filter { it.second.view == view && it.second.region == 0 }.map { it.first }
        val c = Offset(own.sumOf { it.x.toDouble() }.toFloat() / own.size, own.sumOf { it.y.toDouble() }.toFloat() / own.size)
        return own.minBy { (it - c).getDistance() }
    }

    private fun box(c: Offset, r: Float) = listOf(c + Offset(-r, -r), c + Offset(r, -r), c + Offset(r, r), c + Offset(-r, r))

    /** A touch on a facing sheet, not within [margin] of a lasso edge (whatever [edges] are) in canvas space. */
    private fun probes(view: SurfaceView, edges: List<Pair<Offset, Float>>) = touches.filter { (at, f) ->
        f.view == view && f.region == 0 && interior(at, f) &&
            edges.none { (c, r) -> kotlin.math.abs(at.x - c.x) in (r - 12f)..(r + 12f) && kotlin.math.abs(at.y - c.y) <= r + 12f || kotlin.math.abs(at.y - c.y) in (r - 12f)..(r + 12f) && kotlin.math.abs(at.x - c.x) <= r + 12f }
    }

    private fun inBox(at: Offset, c: Offset, r: Float) = kotlin.math.abs(at.x - c.x) < r && kotlin.math.abs(at.y - c.y) < r

    private fun cut(sel: Selection, v: SurfaceView) = SurfaceViewSelection.forView(sel, v, owner, hull, cam, w, h, projection)

    private fun flatOf(f: SurfaceMath.ViewFlat) = Offset(f.x, f.y)

    /** The centre, in the view's flat space, of every triangle of [view] that turns away from the camera. */
    private fun turnedAway(view: SurfaceView) = projection.triangles(view).filter { !it.facing }.mapNotNull { t ->
        val u = (t.px[0] + t.px[1] + t.px[2]) / 3f
        val v = (t.py[0] + t.py[1] + t.py[2]) / 3f
        SurfaceMath.unrolledToFlat(u, v, owner.innerChain, w, h)?.let { Offset(it.first, it.second) }
    }

    @Test
    fun `an inverted lasso selects everything outside it on every view, and nothing under it`() {
        assertTrue("the camera shows more than one face", views.size >= 2)
        val spot = middleOf(views[0])
        val r = 40f
        val lasso = Selection.ofPolygon(box(spot, r), canvas, inverted = true)
        var outside = 0
        var inside = 0
        for (v in views) {
            val sel = cut(lasso, v)
            assertNotNull("an inverted lasso is not a miss on $v", sel)
            for ((at, f) in probes(v, listOf(spot to r))) {
                val under = inBox(at, spot, r)
                assertEquals("$at on $v (under the lasso: $under)", !under, SelectionGeometry.contains(sel!!, flatOf(f)))
                if (under) inside++ else outside++
            }
        }
        assertTrue("both sides probed ($outside / $inside)", outside > 100 && inside > 5)
    }

    @Test
    fun `an inverted lasso is bounded to the faces that face the camera`() {
        val spot = middleOf(views[0])
        val lasso = Selection.ofPolygon(box(spot, 40f), canvas, inverted = true)
        var away = 0
        for (v in views) {
            val sel = cut(lasso, v)!!
            for (p in turnedAway(v)) {
                assertTrue("a face turned away on $v is not selected through the near one", !SelectionGeometry.contains(sel, p))
                away++
            }
        }
        assertTrue("some faces turn away ($away)", away > 0)
    }

    @Test
    fun `an inverted lasso with Add selects the added area back, with Remove it leaves it out`() {
        val a = middleOf(views[0])
        val b = a + Offset(20f, 10f)
        val big = 90f
        val small = 25f
        val inverted = Selection.ofPolygon(box(a, big), canvas, inverted = true)
        // Add onto an inverted selection adds to what is selected: the area is given back.
        val added = SelectionGeometry.compose(inverted, box(b, small), canvas, SelectionOp.ADD)!!
        // Remove cuts from what is selected: the outside loses the new area as well.
        val removed = SelectionGeometry.compose(inverted, box(a + Offset(-120f, 0f), small), canvas, SelectionOp.REMOVE)!!
        val edgesAdd = listOf(a to big, b to small)
        val edgesRem = listOf(a to big, a + Offset(-120f, 0f) to small)
        var seen = 0
        for (v in views) {
            val sa = cut(added, v)
            val sr = cut(removed, v)
            assertNotNull("inverted + Add is not a miss on $v", sa)
            assertNotNull("inverted + Remove is not a miss on $v", sr)
            for ((at, f) in probes(v, edgesAdd)) {
                val selected = !inBox(at, a, big) || inBox(at, b, small)
                assertEquals("add: $at on $v", selected, SelectionGeometry.contains(sa!!, flatOf(f)))
                seen++
            }
            for ((at, f) in probes(v, edgesRem)) {
                val selected = !inBox(at, a, big) && !inBox(at, a + Offset(-120f, 0f), small)
                assertEquals("remove: $at on $v", selected, SelectionGeometry.contains(sr!!, flatOf(f)))
                seen++
            }
        }
        assertTrue(seen > 200)
    }

    @Test
    fun `an inverted lasso that covers the whole picture selects nothing, an empty one everything that faces`() {
        val all = Selection.ofPolygon(box(Offset(500f, 400f), 900f), canvas, inverted = true)
        val none = Selection.ofPolygon(box(Offset(-500f, -500f), 20f), canvas, inverted = true)
        for (v in views) {
            val sa = cut(all, v)
            for ((_, f) in touches.filter { it.second.view == v && it.second.region == 0 && interior(it.first, it.second) }) {
                assertTrue("covered: nothing outside it on $v", sa == null || !SelectionGeometry.contains(sa, flatOf(f)))
            }
            val sn = cut(none, v)
            assertNotNull("a lasso away from the surface leaves all of $v selected", sn)
            for ((_, f) in touches.filter { it.second.view == v && it.second.region == 0 && interior(it.first, it.second) }) {
                assertTrue(SelectionGeometry.contains(sn!!, flatOf(f)))
            }
        }
    }
}
