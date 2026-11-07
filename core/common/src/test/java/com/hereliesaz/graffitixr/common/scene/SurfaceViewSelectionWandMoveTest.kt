@file:Suppress("MaxLineLength", "MagicNumber", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "NestedBlockDepth")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wand regions and selection moves on a multi-view surface (spec/3d-space.md, "Selection on a multi-view surface"). */
class SurfaceViewSelectionWandMoveTest {
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
    private val camera = SceneCamera(yaw = 0.6f, pitch = 0.3f)
    private val w = 1000f
    private val h = 800f
    private val projection = SurfaceProjection.of(hull, owner, camera, w, h)

    /** A grid of canvas points on the surface, each with the face the viewer sees there. */
    private fun touches() = buildList {
        var y = 0f
        while (y < h) {
            var x = 0f
            while (x < w) {
                SurfaceMath.worldToViewFlat(x, y, owner, camera, w, h, hull)?.let { add(Offset(x, y) to it) }
                x += 10f
            }
            y += 10f
        }
    }

    /** The whole image of [view]'s sheet, as a ring in flat space (the innerChain is the identity here). */
    private fun sheetRing(view: SurfaceView, region: Int = 0): SelectionRing {
        val r = hull.regionRect(view, region)!!
        return SelectionRing(listOf(Offset(r[0], r[1]), Offset(r[2], r[1]), Offset(r[2], r[3]), Offset(r[0], r[3])))
    }

    private fun world(view: SurfaceView, region: Int = 0, tapped: Offset? = null) =
        SurfaceViewSelection.fromView(listOf(sheetRing(view, region)), view, owner, projection, w, h, tapped)

    private fun selected(rings: List<SelectionRing>, at: Offset) =
        SelectionGeometry.contains(Selection(rings, IntSize(1000, 800)), at)

    /** Not within 15px of a place where the face under the viewer changes. */
    private fun interior(at: Offset, f: SurfaceMath.ViewFlat) = listOf(Offset(15f, 0f), Offset(-15f, 0f), Offset(0f, 15f), Offset(0f, -15f)).all {
        val o = SurfaceMath.worldToViewFlat(at.x + it.x, at.y + it.y, owner, camera, w, h, hull)
        o != null && o.view == f.view && o.region == f.region
    }

    @Test
    fun `a wand region on one face and the next, across a crease, is selected on screen where each is`() {
        val views = touches().filter { it.second.region == 0 }.map { it.second.view }.distinct()
        assertTrue("the camera shows two faces at least", views.size >= 2)
        val a = views[0]
        val b = views[1]
        val rings = world(a) + world(b)
        var inside = 0
        for ((at, f) in touches().filter { interior(it.first, it.second) }) {
            val expect = f.region == 0 && (f.view == a || f.view == b)
            assertEquals("$at on ${f.view}/${f.region}", expect, selected(rings, at))
            if (expect) inside++
        }
        assertTrue(inside > 50)
    }

    @Test
    fun `a wand region never selects the screen area of the faces in front of the turned away ones`() {
        var checked = 0
        for (view in SurfaceView.entries) {
            val rings = world(view)
            // The view's image holds faces turned away as well as facing ones: only the facing ones may come through.
            for ((at, f) in touches().filter { interior(it.first, it.second) }) {
                val expect = f.region == 0 && f.view == view
                assertEquals("$view at $at on ${f.view}/${f.region}", expect, selected(rings, at))
                checked++
            }
        }
        assertTrue(checked > 100)
        assertTrue("some view of the cube has a face turned away", SurfaceView.entries.any { v -> projection.triangles(v).any { !it.facing } })
    }

    @Test
    fun `the tapped triangle counts even when it faces away, and only it`() {
        val away = SurfaceView.entries.firstOrNull { v -> projection.triangles(v).any { !it.facing } } ?: error("none")
        val tri = projection.triangles(away).first { !it.facing }
        val at = Offset((tri.px[0] + tri.px[1] + tri.px[2]) / 3f, (tri.py[0] + tri.py[1] + tri.py[2]) / 3f)
        val plain = world(away)
        val tapped = world(away, tapped = at)
        assertEquals(plain.size + 1, tapped.size)
    }

    @Test
    fun `a world region cut back to an image gives the region it came from`() {
        val (at, f) = touches().first { interior(it.first, it.second) && it.second.region == 0 }
        val sel = Selection(world(f.view), IntSize(1000, 800))
        val back = SurfaceViewSelection.forView(sel, f.view, owner, hull, camera, w, h)!!
        assertTrue(SelectionGeometry.contains(back, Offset(f.x, f.y)))
        assertTrue(at.x.isFinite())
    }

    /** An image whose pixel is its paint point, coloured by where it is, so a moved pixel says where it came from. */
    private inner class Img(val view: SurfaceView) : SurfaceViewMove.ViewPixels {
        override val width = hull.paintSize(view)!!.first.toInt() + 1
        override val height = hull.paintSize(view)!!.second.toInt() + 1
        override fun pixelOf(x: Float, y: Float) = x to y
        override fun paintOf(px: Float, py: Float) = px to py
        override fun argb(px: Int, py: Int): Int = (0xFF shl 24) or ((px and 0xFFF) shl 12) or (py and 0xFFF)
    }

    private fun code(c: Int) = ((c shr 12) and 0xFFF) to (c and 0xFFF)

    @Test
    fun `moving across a crease puts the pixels into the neighbouring image where they show`() {
        val views = touches().filter { it.second.region == 0 }.map { it.second.view }.distinct()
        val images = views.associateWith { Img(it) }
        val onA = touches().filter { it.second.view == views[0] && it.second.region == 0 && interior(it.first, it.second) }
        // Move the whole picture's left-hand face by a drag that carries it onto the neighbour.
        val source = Rect(0f, 0f, w, h)
        val delta = Offset(if (views.size > 1) 120f else 0f, 0f)
        val patches = SurfaceViewMove.plan(projection, images, delta, source)
        assertTrue("pixels land in more than one image", patches.size >= 2)
        var verified = 0
        for ((view, p) in patches) {
            for (i in 0 until p.width * p.height step 37) {
                val c = p.pixels[i]
                if (c == 0) continue
                val px = p.left + i % p.width
                val py = p.top + i / p.width
                // Where this pixel shows, and what the viewer sees `delta` behind it: that is the pixel it was taken from.
                val box = hull.pointAt(view, px + 0.5f, py + 0.5f) ?: continue
                val model = SceneMath.chainModel(owner.ownerChain, w, h)
                val scr = SceneMath.frame(camera, w, h).project(model.apply(box[0], box[1], box[2])) ?: continue
                val mine = projection.surfaceAt(Offset(scr.first - delta.x, scr.second - delta.y))
                val src = SurfaceMath.worldToViewFlat(scr.first - delta.x, scr.second - delta.y, owner, camera, w, h, hull) ?: continue
                val from = Offset(scr.first - delta.x, scr.second - delta.y)
                // Not at a silhouette or a crease, where two answers are both right.
                if (mine?.tri?.view != src.view || !interior(from, src) || !interior(Offset(scr.first, scr.second), SurfaceMath.ViewFlat(view, 0f, 0f, 0))) continue
                val (cx, cy) = code(c)
                assertTrue("$view ($px,$py) came ${src.view} (${src.x},${src.y}) not ($cx,$cy)", kotlin.math.abs(cx - src.x) <= 2f && kotlin.math.abs(cy - src.y) <= 2f)
                verified++
            }
        }
        assertTrue("checked $verified", verified > 20)
        assertTrue(onA.isNotEmpty())
    }

    @Test
    fun `a move by nothing leaves every pixel where it was`() {
        val view = touches().first { it.second.region == 0 }.second.view
        val patches = SurfaceViewMove.plan(projection, mapOf(view to Img(view)), Offset.Zero, Rect(0f, 0f, w, h))
        val p = patches.getValue(view)
        var same = 0
        for (i in 0 until p.width * p.height step 11) {
            if (p.pixels[i] == 0) continue
            val (cx, cy) = code(p.pixels[i])
            assertTrue(kotlin.math.abs(cx - (p.left + i % p.width)) <= 1 && kotlin.math.abs(cy - (p.top + i / p.width)) <= 1)
            same++
        }
        assertTrue(same > 10)
    }

    @Test
    fun `pixels carried off the surface are lost`() {
        val view = touches().first { it.second.region == 0 }.second.view
        val patches = SurfaceViewMove.plan(projection, mapOf(view to Img(view)), Offset(5000f, 0f), Rect(0f, 0f, w, h))
        assertTrue(patches.isEmpty())
        assertFalse(selected(emptyList(), Offset(1f, 1f)))
    }
}
