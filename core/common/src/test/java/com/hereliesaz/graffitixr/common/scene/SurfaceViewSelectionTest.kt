@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A lasso over a multi-view surface splits across the view images (spec/3d-space.md, "Paint per view"). */
class SurfaceViewSelectionTest {
    private fun outline(sweep: FloatArray, place: (Float, Float) -> FloatArray) = SurfaceProfile(
        listOf(300f to 200f, 700f to 200f, 700f to 600f, 300f to 600f).flatMap { place(it.first, it.second).toList() },
        sweep[0], sweep[1], sweep[2],
    )

    // A cube seen from a turned camera so the front and the side are both on screen, meeting at a crease.
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

    private fun ring(vararg pts: Pair<Float, Float>) = Selection.ofPolygon(pts.map { Offset(it.first, it.second) }, IntSize(1000, 800))

    /** A grid of screen points that land on the surface, each with the view and flat point it paints. */
    private fun touches() = buildList {
        var y = 0f
        while (y < h) {
            var x = 0f
            while (x < w) {
                SurfaceMath.worldToViewFlat(x, y, owner, camera, w, h, hull)?.let { add(Offset(x, y) to it) }
                x += 20f
            }
            y += 20f
        }
    }

    @Test
    fun `a lasso over the whole picture selects the touches on every view, a small one only its own`() {
        val all = ring(-100f to -100f, 1100f to -100f, 1100f to 900f, -100f to 900f)
        val views = touches().map { it.second.view }.toSet()
        assertTrue("the camera shows more than one face", views.size >= 2)
        for (v in views) {
            val sel = SurfaceViewSelection.forView(all, v, owner, hull, camera, w, h)
            assertNotNull(sel)
            for ((_, f) in touches().filter { it.second.view == v && it.second.region == 0 }) {
                assertTrue(SelectionGeometry.contains(sel!!, Offset(f.x, f.y)))
            }
        }
    }

    @Test
    fun `a lasso crossing a crease selects exactly the touches under it in each image`() {
        val ts = touches()
        val left = ts.minOf { it.first.x }
        val right = ts.maxOf { it.first.x }
        val mid = (left + right) / 2f
        // The right half of the picture only: a vertical edge that cuts across the surface.
        val lasso = ring(mid to -100f, 1100f to -100f, 1100f to 900f, mid to 900f)
        var inside = 0
        var outside = 0
        for (v in ts.map { it.second.view }.toSet()) {
            val sel = SurfaceViewSelection.forView(lasso, v, owner, hull, camera, w, h)
            for ((at, f) in ts.filter { it.second.view == v && it.second.region == 0 }) {
                // Away from the lasso's edge and the crease, so the check is not about pixel luck.
                if (kotlin.math.abs(at.x - mid) < 25f) continue
                val selected = sel != null && SelectionGeometry.contains(sel, Offset(f.x, f.y))
                if (at.x > mid) { assertTrue("under the lasso at $at in $v", selected); inside++ } else { assertFalse("outside the lasso at $at in $v", selected); outside++ }
            }
        }
        assertTrue(inside > 20 && outside > 20)
    }

    @Test
    fun `a lasso that misses the surface selects nothing in any image`() {
        val off = ring(-300f to -300f, -200f to -300f, -200f to -200f)
        for (v in SurfaceView.entries) assertNull(SurfaceViewSelection.forView(off, v, owner, hull, camera, w, h))
    }

    @Test
    fun `the face region bounds a fill to the face it was tapped on`() {
        val (at, f) = touches().first { it.second.region == 0 }
        val sel = SurfaceViewSelection.faceRegion(f, owner, hull, IntSize(1000, 800), w, h)!!
        assertTrue(SelectionGeometry.contains(sel, Offset(f.x, f.y)))
        val (pw, _) = hull.paintSize(f.view)!!
        assertFalse(SelectionGeometry.contains(sel, Offset(pw + 5f, 5f)))
        assertNotNull(at)
    }
}
