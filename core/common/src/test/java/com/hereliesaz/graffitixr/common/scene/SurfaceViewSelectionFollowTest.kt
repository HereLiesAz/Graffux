@file:Suppress("MaxLineLength", "MagicNumber", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "NestedBlockDepth", "LongMethod")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.model.SurfaceSelection
import com.hereliesaz.graffitixr.common.model.ViewSelection
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wand selection on a multi-view surface follows the surface (spec/3d-space.md, "Selection on a
 * multi-view surface"): it is stored per view image, and its on-screen footprint is derived from that for
 * whatever camera is current.
 */
class SurfaceViewSelectionFollowTest {
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

    private fun touches(c: SceneCamera) = buildList {
        var y = 0f
        while (y < h) {
            var x = 0f
            while (x < w) {
                SurfaceMath.worldToViewFlat(x, y, owner, c, w, h, hull)?.let { add(Offset(x, y) to it) }
                x += 10f
            }
            y += 10f
        }
    }

    private fun interior(c: SceneCamera, at: Offset, f: SurfaceMath.ViewFlat) = listOf(Offset(15f, 0f), Offset(-15f, 0f), Offset(0f, 15f), Offset(0f, -15f)).all {
        val o = SurfaceMath.worldToViewFlat(at.x + it.x, at.y + it.y, owner, c, w, h, hull)
        o != null && o.view == f.view && o.region == f.region
    }

    private fun sheetRing(view: SurfaceView, region: Int = 0): SelectionRing {
        val r = hull.regionRect(view, region)!!
        return SelectionRing(listOf(Offset(r[0], r[1]), Offset(r[2], r[1]), Offset(r[2], r[3]), Offset(r[0], r[3])))
    }

    private fun wand(view: SurfaceView, c: SceneCamera = cam1): Selection =
        SurfaceViewSelection.fromWand("p", listOf(ViewSelection(view, 0, listOf(sheetRing(view)))), owner, projection(c), w, h, canvas, null)!!

    private fun refreshed(s: Selection, c: SceneCamera) = SurfaceViewSelection.refresh(s, owner, projection(c), w, h)

    /** The sheet views the camera shows, in a stable order. */
    private fun shown(c: SceneCamera) = touches(c).filter { it.second.region == 0 }.map { it.second.view }.distinct()

    /** A view the camera shows now whose sheet is hidden from behind. */
    private fun hiddenFromBehind() = shown(cam1).first { v -> projection(behind).facing(v).none { it.region == 0 } }

    private fun holds(s: Selection, at: Offset) = SelectionGeometry.contains(s, at)

    @Test
    fun `a wand selection persists in image space after the camera changes`() {
        val a = shown(cam1)[0]
        val first = wand(a)
        assertNotNull(first.surface)
        assertEquals("p", first.surface!!.surfaceId)
        assertEquals(listOf(ViewSelection(a, 0, listOf(sheetRing(a)))), first.surface!!.views)
        val orbited = refreshed(first, cam2)
        assertEquals("the per-image regions are what was selected, untouched by the camera", first.surface, orbited.surface)
        assertEquals(first.canvasSize, orbited.canvasSize)
        assertEquals(first.inverted, orbited.inverted)
    }

    @Test
    fun `the footprint is recomputed for the new camera and follows the face`() {
        val a = shown(cam1).first { v -> shown(cam2).contains(v) }
        val first = wand(a)
        val orbited = refreshed(first, cam2)
        assertNotEquals("the footprint moved with the face", first.rings, orbited.rings)
        var onFace = 0
        var other = 0
        for ((at, f) in touches(cam2).filter { interior(cam2, it.first, it.second) }) {
            val expect = f.region == 0 && f.view == a
            assertEquals("$at on ${f.view}/${f.region} after the orbit", expect, holds(orbited, at))
            if (expect) onFace++ else other++
        }
        assertTrue(onFace > 30 && other > 30)
        // And the old footprint is where it was: it no longer matches the new picture.
        val stale = touches(cam2).filter { interior(cam2, it.first, it.second) && it.second.view == a && it.second.region == 0 }
        assertTrue("the stale footprint misses the face", stale.count { !holds(first, it.first) } > stale.size / 10)
    }

    @Test
    fun `a face now turned away is not selected for the new camera and comes back when the camera does`() {
        val a = hiddenFromBehind()
        val first = wand(a)
        val away = projection(behind)
        // Looking from behind, the camera sees the opposite faces of the solid.
        assertTrue("the view's sheet is turned away from behind", away.facing(a).none { it.region == 0 })
        val gone = refreshed(first, behind)
        assertTrue("nothing of a turned away face is selected", gone.rings.none { it.additive })
        assertEquals("the selection itself is kept", first.surface, gone.surface)
        val back = refreshed(gone, cam1)
        assertEquals("orbiting back brings the footprint back exactly", first.rings, back.rings)
    }

    @Test
    fun `a face turned away when the wand was tapped contributes nothing`() {
        val away = SurfaceView.entries.first { v -> projection(cam1).facing(v).none { it.region == 0 } && projection(cam1).triangles(v).any { it.region == 0 } }
        val none = SurfaceViewSelection.fromWand("p", listOf(ViewSelection(away, 0, listOf(sheetRing(away)))), owner, projection(cam1), w, h, canvas, null)
        assertNull(none)
    }

    @Test
    fun `a flat selection is left exactly as it was`() {
        val lasso = Selection.ofPolygon(listOf(Offset(10f, 10f), Offset(200f, 10f), Offset(200f, 200f)), canvas, inverted = true, featherPx = 3f)
        assertSame(lasso, SurfaceViewSelection.refresh(lasso, owner, projection(cam2), w, h))
        assertNull(lasso.surface)
        assertEquals(lasso.rings.map { r -> r.copy(path = r.path.map { it + Offset(5f, 0f) }) }, lasso.translated(Offset(5f, 0f)).rings)
        // The flat ring operations are the ones they were.
        val added = SelectionGeometry.compose(lasso, listOf(Offset(0f, 0f), Offset(50f, 0f), Offset(50f, 50f)), canvas, SelectionOp.ADD)!!
        assertEquals(lasso.rings.size + 1, added.rings.size)
        assertNull(added.surface)
        assertTrue(added.inverted)
        assertEquals(3f, added.featherPx, 0f)
    }

    @Test
    fun `a move bakes the footprint and drops the stale image regions`() {
        val moved = wand(shown(cam1)[0]).translated(Offset(40f, 0f))
        assertNull(moved.surface)
    }

    private fun lassoOver(a: Offset, b: Offset) = Selection.ofPolygon(listOf(a, Offset(b.x, a.y), b, Offset(a.x, b.y)), canvas)

    @Test
    fun `a lasso added to a wand is cut into the images and both follow the camera`() {
        val shownViews = shown(cam1)
        val a = shownViews[0]
        val b = shownViews[1]
        val first = wand(a)
        val spotB = touches(cam1).first { it.second.view == b && it.second.region == 0 && interior(cam1, it.first, it.second) }.first
        val lasso = lassoOver(spotB - Offset(15f, 15f), spotB + Offset(15f, 15f))
        val both = SurfaceViewSelection.compose(first, lasso, SelectionOp.ADD, "p", owner, hull, cam1, projection(cam1), w, h)!!
        assertNotNull(both.surface)
        assertTrue("the lasso became a region of b's image", both.surface!!.views.any { it.view == b })
        assertTrue(holds(both, spotB))
        assertTrue(holds(both, touches(cam1).first { it.second.view == a && it.second.region == 0 && interior(cam1, it.first, it.second) }.first))
        // After the orbit, b's patch of the old lasso moved with b, and a still has its whole face.
        val orbited = refreshed(both, cam2)
        val (after, fb) = touches(cam2).first { it.second.view == b && it.second.region == 0 && interior(cam2, it.first, it.second) && kotlin.math.abs(it.second.x - touchFlat(cam1, spotB).x) < 6f && kotlin.math.abs(it.second.y - touchFlat(cam1, spotB).y) < 6f }
        assertTrue("$after on $b follows", holds(orbited, after) && fb.view == b)
    }

    private fun touchFlat(c: SceneCamera, at: Offset) = SurfaceMath.worldToViewFlat(at.x, at.y, owner, c, w, h, hull)!!

    @Test
    fun `a lasso removed from a wand leaves a hole in the image that stays after the camera moves`() {
        val a = shown(cam1).first { v -> shown(cam2).contains(v) }
        val first = wand(a)
        val seen2 = projection(cam2).facing(a).filter { it.region == 0 }
        val spot = touches(cam1).first { c ->
            val (pu, pt) = SurfaceMath.flatToUnrolled(c.second.x, c.second.y, owner.innerChain, w, h)
            c.second.view == a && c.second.region == 0 && interior(cam1, c.first, c.second) && seen2.any { it.holdsPaint(pu, pt, 0.05f) && it.screenAt(pu, pt) != null }
        }
        val flat = spot.second
        val lasso = lassoOver(spot.first - Offset(12f, 12f), spot.first + Offset(12f, 12f))
        val cut = SurfaceViewSelection.compose(first, lasso, SelectionOp.REMOVE, "p", owner, hull, cam1, projection(cam1), w, h)!!
        assertFalse("hole at the lasso", holds(cut, spot.first))
        assertTrue(cut.surface!!.views.flatMap { it.rings }.any { !it.additive })
        // The hole is a place on the face: after the orbit it is where that place now shows.
        val orbited = refreshed(cut, cam2)
        val tri = projection(cam2).facing(a).first { it.region == 0 && it.holdsPaint(SurfaceMath.flatToUnrolled(flat.x, flat.y, owner.innerChain, w, h).first, SurfaceMath.flatToUnrolled(flat.x, flat.y, owner.innerChain, w, h).second) }
        val (u, t) = SurfaceMath.flatToUnrolled(flat.x, flat.y, owner.innerChain, w, h)
        val where = tri.screenAt(u, t)!!
        assertFalse("the hole follows the face to $where", holds(orbited, where))
        assertTrue(holds(orbited, tri.screenAt(tri.px[0] * 0.9f + u * 0.1f, tri.py[0] * 0.9f + t * 0.1f)!!))
    }

    @Test
    fun `a wand added to a lasso converts the lasso to the images`() {
        val shownViews = shown(cam1)
        val a = shownViews[0]
        val b = shownViews[1]
        val spotB = touches(cam1).first { it.second.view == b && it.second.region == 0 && interior(cam1, it.first, it.second) }.first
        val lasso = lassoOver(spotB - Offset(20f, 20f), spotB + Offset(20f, 20f))
        val both = SurfaceViewSelection.compose(lasso, wand(a), SelectionOp.ADD, "p", owner, hull, cam1, projection(cam1), w, h)!!
        assertTrue(both.surface!!.views.map { it.view }.containsAll(listOf(a, b)))
        assertTrue(holds(both, spotB))
        // The converted lasso now follows the surface too, so the whole is camera independent.
        assertEquals(both.surface, refreshed(both, cam2).surface)
    }

    @Test
    fun `a wand removed from nothing is nothing, and under an inverted selection the sense is flipped`() {
        val a = shown(cam1)[0]
        assertNull(SurfaceViewSelection.compose(null, wand(a), SelectionOp.REMOVE, "p", owner, hull, cam1, projection(cam1), w, h))
        val base = wand(shown(cam1)[1]).copy(inverted = true, featherPx = 2f)
        val next = SurfaceViewSelection.compose(base, wand(a), SelectionOp.ADD, "p", owner, hull, cam1, projection(cam1), w, h)!!
        assertTrue(next.inverted)
        assertEquals(2f, next.featherPx, 0f)
        assertTrue("an Add onto an inverted selection is a subtractive ring", next.surface!!.views.last().rings.all { !it.additive })
    }

    @Test
    fun `a moved surface selection is rebound to the images at its new place`() {
        val a = shown(cam1)[0]
        val first = wand(a)
        val moved = first.translated(Offset(30f, 0f))
        val rebound = SurfaceViewSelection.rebind(moved, "p", owner, hull, cam1, projection(cam1), w, h)
        assertEquals("the marquee is what the move made it", moved.rings, rebound.rings)
        assertNotNull(rebound.surface)
        assertEquals("p", rebound.surface!!.surfaceId)
        // Following the camera now keeps the regions in the images.
        assertEquals(rebound.surface, refreshed(rebound, cam2).surface)
    }

    @Test
    fun `a view's cut for painting is the stored region restricted to what faces the camera`() {
        val a = hiddenFromBehind()
        val first = wand(a)
        val cut = SurfaceViewSelection.viewCut(first, a, owner, projection(cam1), w, h)
        assertNotNull(cut)
        val none = SurfaceViewSelection.viewCut(first, a, owner, projection(behind), w, h)
        assertNull("turned away, nothing of it can be painted", none)
        val other = shown(cam1).first { it != a }.also { assertTrue(it != a) }
        assertNull("a view the selection never reached is a miss", SurfaceViewSelection.viewCut(first, other, owner, projection(cam1), w, h))
        assertTrue(SurfaceSelection("p", emptyList()).views.isEmpty())
    }
}
