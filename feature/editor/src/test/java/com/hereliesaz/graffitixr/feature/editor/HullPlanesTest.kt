package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.DepthComposite
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SurfaceHull
import com.hereliesaz.graffitixr.common.scene.SurfaceMath
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.common.scene.SurfaceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A multi-view surface draws each view's paint image on that view's patch (spec/3d-space.md, "Rendering the hull"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("MaxLineLength")
class HullPlanesTest {
    private fun outline(sweep: FloatArray, place: (Float, Float) -> FloatArray) = SurfaceProfile(
        listOf(300f to 200f, 700f to 200f, 700f to 600f, 300f to 600f).flatMap { place(it.first, it.second).toList() },
        sweep[0], sweep[1], sweep[2],
    )

    private val cube = outline(floatArrayOf(0f, 0f, 1f)) { x, y -> floatArrayOf(x, y, 0f) }.copy(
        side = outline(floatArrayOf(1f, 0f, 0f)) { z, y -> floatArrayOf(0f, y, z - 300f) },
        top = outline(floatArrayOf(0f, 1f, 0f)) { x, z -> floatArrayOf(x, 0f, z - 300f) },
    )

    private fun image(id: String, of: String, view: SurfaceView, bmp: Bitmap?) = Layer(
        id = id, name = id, parentId = "g", bitmap = bmp, isVisible = false, surfaceViewOf = of, surfaceView = view,
    )

    private val group = Layer("g", "G", type = LayerType.GROUP, surface = cube)
    private val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    private val paint = Layer("p", "P", parentId = "g", opacity = 0.5f)
    private val layers = listOf(
        group, paint,
        image("front", "p", SurfaceView.FRONT, bmp),
        image("side", "p", SurfaceView.SIDE, bmp),
        image("top", "p", SurfaceView.TOP, null),
    )

    @Test
    fun `one plane per painted view image, on the hull's triangles, in the paint layer's place`() {
        val owner = SurfaceMath.ownerOf(layers, "p")!!
        val hull = SurfaceHull.of(owner.profile)!!
        val planes = DepthScene.hullPlanes(
            layers, paint, owner, hull, 1000f, 800f, { it.bitmap?.let { b -> b to 1L } }, 0.5f, null, 3,
        )
        assertEquals(listOf("front", "side"), planes.map { it.id })
        for (p in planes) {
            assertTrue(p.surfaceTriangles && !p.surfaceFlat)
            assertEquals(0, p.surface!!.size % 15)
            assertEquals(3, p.surfaceIndex)
            assertEquals(0.5f, p.alpha, 0f)
            assertEquals("g", p.surfaceGroup)
            for (i in p.surface!!.indices step 5) {
                // The image is smaller than the patch here, so uvs may leave 0..1; they must be real numbers.
                assertTrue(p.surface!![i + 3].isFinite() && p.surface!![i + 4].isFinite())
            }
        }
    }

    @Test
    fun `a paint layer with no pixels of its own still takes part when a view image has some`() {
        val plan = DepthComposite.plan(layers.map { if (it.id == "front") it.copy(isVisible = false) else it }, SceneCamera(yaw = 0.7f))
        assertTrue("p" in plan.memberIds)
        assertFalse("front" in plan.memberIds)
        val none = DepthComposite.plan(layers.filter { !it.id.startsWith("front") && it.id != "side" }, SceneCamera(yaw = 0.7f))
        assertFalse("p" in none.memberIds)
    }
}
