@file:Suppress("MaxLineLength", "MagicNumber", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "NestedBlockDepth", "LongMethod", "LargeClass", "TooManyFunctions")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.SavedSelection
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.model.ViewSelection
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plain lasso drawn on a multi-view surface follows the surface like the wand does (spec/3d-space.md,
 * "Selection on a multi-view surface"): it is cut into the view images when it is drawn, and its on-screen
 * footprint is derived from those regions for whatever camera is current.
 */
class SurfaceViewSelectionLassoFollowTest {
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
    private val cam1 = SceneCamera(yaw = 0.6f, pitch = 0.3f)
    private val cam2 = SceneCamera(yaw = -0.6f, pitch = 0.3f)
    private val behind = SceneCamera(yaw = 0.6f + Math.PI.toFloat(), pitch = 0.3f)

    private fun projection(c: SceneCamera) = SurfaceProjection.of(hull, owner, c, w, h)

    // Off the 10px lattice so no probe sits exactly on a vertex or an edge of the triangulation.
    private fun touches(c: SceneCamera) = buildList {
        var y = 2.3f
        while (y < h) {
            var x = 3.7f
            while (x < w) {
                SurfaceMath.worldToViewFlat(x, y, owner, c, w, h, hull)?.let { add(Offset(x, y) to it) }
                x += 10f
            }
            y += 10f
        }
    }

    private fun interior(c: SceneCamera, at: Offset, f: SurfaceMath.ViewFlat) = listOf(Offset(12f, 0f), Offset(-12f, 0f), Offset(0f, 12f), Offset(0f, -12f)).all {
        val o = SurfaceMath.worldToViewFlat(at.x + it.x, at.y + it.y, owner, c, w, h, hull)
        o != null && o.view == f.view && o.region == f.region
    }

    private fun shown(c: SceneCamera) = touches(c).filter { it.second.region == 0 }.map { it.second.view }.distinct()

    private fun middleOf(c: SceneCamera, view: SurfaceView): Offset {
        val own = touches(c).filter { it.second.view == view && it.second.region == 0 && interior(c, it.first, it.second) }.map { it.first }
        val m = Offset(own.sumOf { it.x.toDouble() }.toFloat() / own.size, own.sumOf { it.y.toDouble() }.toFloat() / own.size)
        return own.minBy { (it - m).getDistance() }
    }

    private fun box(c: Offset, r: Float) = listOf(c + Offset(-r, -r), c + Offset(r, -r), c + Offset(r, r), c + Offset(-r, r))

    private fun lasso(c: Offset, r: Float) = Selection.ofPolygon(box(c, r), canvas)

    private fun fromLasso(l: Selection, c: SceneCamera = cam1) =
        SurfaceViewSelection.fromLasso("p", l, owner, hull, c, projection(c), w, h)

    private fun refreshed(s: Selection, c: SceneCamera) = SurfaceViewSelection.refresh(s, owner, projection(c), w, h)

    private fun holds(s: Selection, at: Offset) = SelectionGeometry.contains(s, at)

    private fun compose(current: Selection?, polygon: List<Offset>, op: SelectionOp, c: SceneCamera = cam1) =
        SurfaceViewSelection.composeLasso(current, polygon, canvas, op, "p", owner, hull, c, projection(c), w, h)

    /** Whether the lasso, as drawn under cam1, covers the flat point [f] of its view image (the camera-independent truth). */
    private fun coveredInImage(l: Selection, f: SurfaceMath.ViewFlat): Boolean {
        val cut = SurfaceViewSelection.forView(l, f.view, owner, hull, cam1, w, h, projection(cam1)) ?: return false
        return holds(cut, Offset(f.x, f.y))
    }

    /** The flat point stays covered or uncovered under a small nudge (so a probe on a lasso edge is not luck). */
    private fun stable(l: Selection, f: SurfaceMath.ViewFlat): Boolean? {
        val here = coveredInImage(l, f)
        val nudged = listOf(Offset(5f, 0f), Offset(-5f, 0f), Offset(0f, 5f), Offset(0f, -5f)).all {
            coveredInImage(l, SurfaceMath.ViewFlat(f.view, f.x + it.x, f.y + it.y, f.region)) == here
        }
        return here.takeIf { nudged }
    }

    @Test
    fun `a lasso on a surface is stored per view image and persists there after the camera changes`() {
        val a = shown(cam1).first { v -> shown(cam2).contains(v) }
        val spot = middleOf(cam1, a)
        val drawn = lasso(spot, 60f)
        val first = fromLasso(drawn)!!
        assertNotNull(first.surface)
        assertEquals("p", first.surface!!.surfaceId)
        assertTrue("the lasso is a region of the image it was drawn on", first.surface!!.views.any { it.view == a })
        assertTrue(holds(first, spot))
        val orbited = refreshed(first, cam2)
        assertEquals("the per-image regions are untouched by the camera", first.surface, orbited.surface)
        assertEquals(first.canvasSize, orbited.canvasSize)
        assertFalse(orbited.inverted)
        // After the orbit it is selected where the same place of the face now shows, not where it was drawn.
        var agree = 0
        var disagree = 0
        for ((at, f) in touches(cam2).filter { it.second.region == 0 && interior(cam2, it.first, it.second) }) {
            val expect = stable(drawn, f) ?: continue
            if (holds(orbited, at) == expect) agree++ else disagree++
        }
        assertEquals("the footprint is the place on the face, ($agree agree)", 0, disagree)
        assertTrue(agree > 100)
        assertTrue("and it moved on the screen", first.rings != orbited.rings)
    }

    @Test
    fun `the footprint is recomputed only when the camera changes`() {
        val a = shown(cam1)[0]
        val first = fromLasso(lasso(middleOf(cam1, a), 60f))!!
        assertSame("same camera, nothing to recompute", first, refreshed(first, cam1))
        assertNotEquals(first.rings, refreshed(first, cam2).rings)
    }

    @Test
    fun `faces turned away are dropped when the lasso is drawn`() {
        // The whole picture lassoed: only what faces the camera is kept, whichever way the lasso was drawn.
        val all = lasso(Offset(500f, 400f), 900f)
        val sel = fromLasso(all)!!
        val p = projection(cam1)
        var away = 0
        for (v in SurfaceView.entries) {
            val own = Selection(sel.surface!!.views.filter { it.view == v }.flatMap { it.rings }, canvas)
            for (t in p.triangles(v).filter { !it.facing }) {
                val u = (t.px[0] + t.px[1] + t.px[2]) / 3f
                val tt = (t.py[0] + t.py[1] + t.py[2]) / 3f
                val flat = SurfaceMath.unrolledToFlat(u, tt, owner.innerChain, w, h)!!
                assertFalse("$v turned away triangle is not in the lasso's image region", own.rings.isNotEmpty() && own.isUsable && holds(own, Offset(flat.first, flat.second)))
                away++
            }
        }
        assertTrue(away > 0)
    }

    @Test
    fun `a face that turns away contributes nothing and the lasso comes back with the camera`() {
        val a = shown(cam1).first { v -> projection(behind).facing(v).none { it.region == 0 } }
        val first = fromLasso(lasso(middleOf(cam1, a), 60f))!!
        val gone = refreshed(first, behind)
        assertTrue("nothing selected of a face turned away", gone.rings.none { it.additive })
        assertEquals("the lasso itself is kept", first.surface, gone.surface)
        assertEquals("orbiting back restores the footprint exactly", first.rings, refreshed(gone, cam1).rings)
    }

    @Test
    fun `a lasso that reaches no facing face is not converted`() {
        assertNull(fromLasso(lasso(Offset(-500f, -500f), 20f)))
    }

    @Test
    fun `a lasso added to a wand and a wand added to a lasso both follow`() {
        val vs = shown(cam1)
        val a = vs[0]
        val b = vs[1]
        val r = hull.regionRect(a, 0)!!
        val wand = SurfaceViewSelection.fromWand(
            "p", listOf(ViewSelection(a, 0, listOf(SelectionRing(listOf(Offset(r[0], r[1]), Offset(r[2], r[1]), Offset(r[2], r[3]), Offset(r[0], r[3])))))),
            owner, projection(cam1), w, h, canvas, null,
        )!!
        val spotB = middleOf(cam1, b)
        val both = compose(wand, box(spotB, 30f), SelectionOp.ADD)!!
        assertTrue(both.surface!!.views.map { it.view }.containsAll(listOf(a, b)))
        assertTrue(holds(both, spotB))
        assertEquals("camera independent", both.surface, refreshed(both, cam2).surface)
        // And the other way round: a lasso, then a wand folded onto it.
        val lassoFirst = fromLasso(lasso(spotB, 30f))!!
        val mixed = SurfaceViewSelection.compose(lassoFirst, wand, SelectionOp.ADD, "p", owner, hull, cam1, projection(cam1), w, h)!!
        assertTrue(mixed.surface!!.views.map { it.view }.containsAll(listOf(a, b)))
        assertEquals(mixed.surface, refreshed(mixed, cam2).surface)
    }

    @Test
    fun `New replaces, Add unions and Remove cuts a hole, per image, and the hole follows`() {
        val a = shown(cam1).first { v -> shown(cam2).contains(v) }
        val spot = middleOf(cam1, a)
        val base = compose(null, box(spot, 70f), SelectionOp.NEW)!!
        assertNotNull(base.surface)
        val replaced = compose(base, box(spot + Offset(0f, 0f), 20f), SelectionOp.NEW)!!
        assertFalse("New replaced the wider lasso", holds(replaced, spot + Offset(50f, 0f)))
        val added = compose(base, box(spot + Offset(200f, 0f), 30f), SelectionOp.ADD)!!
        assertTrue(holds(added, spot))
        assertTrue(added.surface!!.views.size >= base.surface!!.views.size)
        val cut = compose(base, box(spot, 25f), SelectionOp.REMOVE)!!
        assertFalse("hole at the lasso", holds(cut, spot))
        assertTrue("the rest stays", holds(cut, spot + Offset(50f, 0f)))
        assertTrue(cut.surface!!.views.flatMap { it.rings }.any { !it.additive })
        // The hole is a place on the face: after the orbit it is where that place shows.
        val flat = SurfaceMath.worldToViewFlat(spot.x, spot.y, owner, cam1, w, h, hull)!!
        val after = refreshed(cut, cam2)
        val (u, t) = SurfaceMath.flatToUnrolled(flat.x, flat.y, owner.innerChain, w, h)
        val tri = projection(cam2).facing(a).firstOrNull { it.holdsPaint(u, t) }
        if (tri != null) assertFalse("the hole followed the face", holds(after, tri.screenAt(u, t)!!))
        assertNull("Remove from nothing is nothing", compose(null, box(spot, 20f), SelectionOp.REMOVE))
    }

    @Test
    fun `invert and feather are flags on the whole and the inverted lasso is everything outside it that faces the camera`() {
        val a = shown(cam1)[0]
        val spot = middleOf(cam1, a)
        val first = fromLasso(lasso(spot, 60f))!!
        val inverted = first.copy(inverted = true, featherPx = 4f)
        assertEquals(first.surface, inverted.surface)
        assertEquals(first.surface, refreshed(inverted, cam2).surface)
        assertTrue(refreshed(inverted, cam2).inverted)
        assertEquals(4f, refreshed(inverted, cam2).featherPx, 0f)
        for (v in shown(cam1)) {
            val cut = SurfaceViewSelection.viewCut(inverted, v, owner, projection(cam1), w, h)
            assertNotNull("inverted lasso is not a miss on $v", cut)
            for ((at, f) in touches(cam1).filter { it.second.view == v && it.second.region == 0 && interior(cam1, it.first, it.second) }) {
                val under = kotlin.math.abs(at.x - spot.x) < 50f && kotlin.math.abs(at.y - spot.y) < 50f
                val near = kotlin.math.abs(at.x - spot.x) < 72f && kotlin.math.abs(at.y - spot.y) < 72f
                if (under || !near) assertEquals("$at on $v", !under, holds(cut!!, Offset(f.x, f.y)))
            }
        }
    }

    @Test
    fun `a moved lasso is rebound to the images and keeps following`() {
        val a = shown(cam1)[0]
        val first = fromLasso(lasso(middleOf(cam1, a), 50f))!!
        val moved = first.translated(Offset(30f, 0f))
        assertNull("the shifted footprint is a plain region until it is rebound", moved.surface)
        val rebound = SurfaceViewSelection.rebind(moved, "p", owner, hull, cam1, projection(cam1), w, h)
        assertEquals(moved.rings, rebound.rings)
        assertNotNull(rebound.surface)
        assertEquals(rebound.surface, refreshed(rebound, cam2).surface)
    }

    @Test
    fun `a flat selection is exactly what it was`() {
        val a = shown(cam1)[0]
        val spot = middleOf(cam1, a)
        val polygon = box(spot, 40f)
        // Not a surface selection: composing onto a plain base, or for nothing reachable, is the flat compose.
        val plain = Selection.ofPolygon(listOf(Offset(10f, 10f), Offset(200f, 10f), Offset(200f, 200f)), canvas, inverted = true, featherPx = 3f)
        for (op in listOf(SelectionOp.ADD, SelectionOp.REMOVE)) {
            assertEquals("$op onto a plain base", SelectionGeometry.compose(plain, polygon, canvas, op), compose(plain, polygon, op))
        }
        assertNotNull("New is a fresh selection: it is cut into the images", compose(plain, polygon, SelectionOp.NEW)!!.surface)
        val offSurface = box(Offset(-500f, -500f), 20f)
        assertEquals(SelectionGeometry.compose(null, offSurface, canvas, SelectionOp.NEW), compose(null, offSurface, SelectionOp.NEW))
        assertEquals(SelectionGeometry.compose(null, offSurface, canvas, SelectionOp.ADD), compose(null, offSurface, SelectionOp.ADD))
        assertNull(compose(null, offSurface, SelectionOp.REMOVE))
        assertSame("a ring too small to enclose anything leaves the selection alone", plain, compose(plain, listOf(Offset(1f, 1f), Offset(2f, 2f)), SelectionOp.ADD))
        assertNull(plain.surface)
    }

    @Test
    fun `a lasso selection is saved and loaded with its image regions`() {
        val a = shown(cam1)[0]
        val first = fromLasso(lasso(middleOf(cam1, a), 50f))!!.copy(inverted = true, featherPx = 2f)
        val json = Json.encodeToString(SavedSelection.serializer(), SavedSelection("s", first))
        val back = Json.decodeFromString(SavedSelection.serializer(), json)
        assertEquals(first, back.selection)
        assertEquals(first.surface, refreshed(back.selection, cam1).surface)
    }
}
