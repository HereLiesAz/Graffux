@file:Suppress("MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.Selection
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A camera inside a closed hull sees the inner side of its walls; selection must still reach them. */
class SurfaceProjectionInsideTest {
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
    private val outside = SceneCamera(yaw = 0.6f, pitch = 0.3f)

    /** Zoomed in until the eye is inside the solid. */
    private val inside = SceneCamera(yaw = 0.3f, pitch = 0.1f, distanceScale = 0.05f, panZ = 200f)

    private fun eyeInside(c: SceneCamera): Boolean {
        val eye = SceneMath.frame(c, w, h).eye
        val e = SceneMath.chainModel(owner.ownerChain, w, h).inverse()!!.apply(eye[0], eye[1], eye[2])
        return hull.contains(e[0], e[1], e[2])
    }

    @Test
    fun `an eye inside the hull sees the inner side of its walls`() {
        assertTrue("eye is inside the solid", eyeInside(inside))
        val projection = SurfaceProjection.of(hull, owner, inside, w, h)
        assertTrue("some wall faces the viewer from inside", projection.facingViews.isNotEmpty())
    }

    @Test
    fun `a lasso drawn from inside the hull reaches the walls and leaves a usable surface selection`() {
        assertTrue(eyeInside(inside))
        val projection = SurfaceProjection.of(hull, owner, inside, w, h)
        val lasso = Selection.ofPolygon(
            listOf(Offset(300f, 200f), Offset(700f, 200f), Offset(700f, 600f), Offset(300f, 600f)),
            IntSize(w.toInt(), h.toInt()),
        )
        val sel = SurfaceViewSelection.fromLasso("p", lasso, owner, hull, inside, projection, w, h)
        assertNotNull("the lasso binds to the walls it covers", sel)
        assertTrue(sel!!.surface!!.isUsable)
    }

    @Test
    fun `from outside the near side still faces the viewer and the far side does not`() {
        assertTrue(!eyeInside(outside))
        val projection = SurfaceProjection.of(hull, owner, outside, w, h)
        val all = SurfaceView.entries.flatMap { projection.triangles(it) }
        assertTrue(all.any { it.facing })
        assertTrue(all.any { !it.facing })
    }
}
