@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The layer rules for per-view paint images (spec/3d-space.md, "Paint per view"). */
class SurfaceViewImagesTest {
    private fun outline(sweep: FloatArray, place: (Float, Float) -> FloatArray) = SurfaceProfile(
        listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f).flatMap { place(it.first, it.second).toList() },
        sweep[0], sweep[1], sweep[2],
    )

    private val frontOnly = outline(floatArrayOf(0f, 0f, 1f)) { x, y -> floatArrayOf(x, y, 0f) }

    // The side box reaches both ways round the front plane, so the hull's near end is not t = 0.
    private val hullProfile = frontOnly.copy(
        side = outline(floatArrayOf(1f, 0f, 0f)) { z, y -> floatArrayOf(0f, y, z - 40f) },
    )

    private fun layer(id: String, parent: String? = null, view: SurfaceView? = null, of: String? = null) =
        Layer(id, id, parentId = parent, surfaceView = view, surfaceViewOf = of)

    @Test
    fun `duplicating a paint layer copies its view images with new ids, relinked to the copy`() {
        val group = Layer("g", "G", type = LayerType.GROUP, surface = hullProfile)
        val p = layer("p", "g").copy(name = "Wall")
        val front = layer("pf", "g", SurfaceView.FRONT, "p")
        val side = layer("ps", "g", SurfaceView.SIDE, "p")
        val other = layer("q", "g")
        val layers = listOf(group, p, front, side, other, layer("qf", "g", SurfaceView.TOP, "q"))
        val copy = p.copy(id = "p2", name = "Wall Copy")
        var n = 0
        val dups = SurfaceViewImages.duplicates(layers, p, copy) { "n${n++}" }
        assertEquals(listOf("pf", "ps"), dups.map { it.first.id })
        assertEquals(listOf("n0", "n1"), dups.map { it.second.id })
        assertTrue(dups.all { it.second.surfaceViewOf == "p2" && it.second.parentId == "g" })
        assertEquals(listOf(SurfaceView.FRONT, SurfaceView.SIDE), dups.map { it.second.surfaceView })
        assertTrue(dups.all { it.second.bitmap == null })
        // Other layers' images and a layer with none are untouched.
        assertTrue(SurfaceViewImages.duplicates(layers, other, other.copy(id = "q2")) { "x" }.size == 1)
        assertTrue(SurfaceViewImages.duplicates(listOf(group, p), p, copy) { "x" }.isEmpty())
    }

    @Test
    fun `a carried image shows each pixel where the sheet showed it`() {
        for (leafPose in listOf(Layer("p", "p", parentId = "g"), Layer("p", "p", parentId = "g", scale = 1.5f, offset = Offset(30f, -20f), rotationZ = 25f))) {
            val group = Layer("g", "G", type = LayerType.GROUP, surface = hullProfile)
            val before = Layer("g", "G", type = LayerType.GROUP, surface = frontOnly)
            val sheet = SurfaceMath.ownerOf(listOf(before, leafPose), "p")!!
            val hull = SurfaceHull.of(hullProfile)!!
            val (dx, dy) = SurfaceViewImages.carryOffset(SurfaceMath.ownerOf(listOf(group, leafPose), "p")!!, hull, 1000f, 800f)!!
            val image = leafPose.copy(id = "pf", surfaceViewOf = "p", surfaceView = SurfaceView.FRONT, offset = Offset(leafPose.offset.x + dx, leafPose.offset.y + dy))
            val owner = SurfaceMath.ownerOf(listOf(group, leafPose, image), "pf")!!
            val toOld = SurfaceMath.boxToUnrolled(sheet.innerChain, 1000f, 800f)
            val toNew = SurfaceMath.boxToUnrolled(owner.innerChain, 1000f, 800f)
            for ((bx, by) in listOf(0f to 0f, 120f to 40f, 500f to 300f)) {
                val (u, t) = toOld.apply(bx, by, 0f).let { it[0] to it[1] }
                val (nu, nt) = toNew.apply(bx, by, 0f).let { it[0] to it[1] }
                val expected = hull.paintPointAt(SurfaceView.FRONT, u, t)!!
                assertEquals(expected.first, nu, 0.05f)
                assertEquals(expected.second, nt, 0.05f)
            }
            assertTrue("the sheet's t = 0 is not the hull's near end here", kotlin.math.abs(dy) > 1f || kotlin.math.abs(dx) > 1f)
        }
    }
}
