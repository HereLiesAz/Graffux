@file:Suppress("MaxLineLength", "MagicNumber", "LongParameterList")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A surface made on a posed group rests where it is seen, so the pivot is the owner's rule for that sheet. */
class SurfaceRebaseTest {
    private val w = 1000f
    private val h = 1600f
    private val rect = DefaultSurface.rect(w, h)!!

    private fun group(id: String, parent: String? = null, offset: Offset = Offset.Zero, scale: Float = 1f, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f) =
        Layer(id = id, name = id, type = LayerType.GROUP, parentId = parent, offset = offset, scale = scale, rotationX = rx, rotationY = ry, rotationZ = rz)

    private fun assertPivot(x: Float, y: Float, z: Float, p: FloatArray, tol: Float = 0.05f) {
        assertEquals("x", x, p[0], tol)
        assertEquals("y", y, p[1], tol)
        assertEquals("z", z, p[2], tol)
    }

    private fun worldOf(layers: List<Layer>, id: String, u: Float, v: Float, z: Float) =
        SceneMath.chainModel(planeChain(layers, id), w, h).apply(u, v, z)

    /** The sheet made on [id] the way the editor now makes it, through [SurfaceRebase]; the layers after it. */
    private fun withSheet(layers: List<Layer>, id: String): List<Layer> {
        val rebased = SurfaceRebase.forCreation(layers, id, w, h)!!
        val profile = DefaultSurface.profile(rect, w, h, emptyList())
        return rebased.map { if (it.id == id) it.copy(surface = profile) else it }
    }

    @Test
    fun `the probe - a group at offset 300 and scale 0_5 gave a pivot 600 px sideways and 908 deep when the profile went through the pose`() {
        val layers = listOf(group("g", offset = Offset(300f, 0f), scale = 0.5f), Layer(id = "c", name = "c", parentId = "g"))
        val naive = layers.map { if (it.id == "g") it.copy(surface = DefaultSurface.profile(rect, w, h, planeChain(layers, "g"))) else it }
        assertPivot(-100f, 800f, 908.3f, OrbitPivot.of(naive, w, h), 0.5f)
        // Rebased: the sheet is centred at (500, 800) as seen, n = 454.17 behind it.
        assertPivot(500f, 800f, 454.17f, OrbitPivot.of(withSheet(layers, "g"), w, h))
    }

    @Test
    fun `a posed group's children and grandchildren stay exactly where they were seen`() {
        val layers = listOf(
            group("g", offset = Offset(300f, -50f), scale = 0.5f, rx = 20f, ry = -15f, rz = 40f),
            Layer(id = "c", name = "c", parentId = "g", offset = Offset(30f, 20f), scale = 1.4f, rotationZ = 10f, positionZ = 25f),
            Layer(id = "gc", name = "gc", parentId = "c", offset = Offset(-5f, 9f), rotationY = 12f),
        )
        val after = SurfaceRebase.forCreation(layers, "g", w, h)!!
        for (id in listOf("c", "gc")) for ((u, v) in listOf(0f to 0f, 1000f to 1600f, 400f to 300f)) {
            val a = worldOf(layers, id, u, v, 7f)
            val b = worldOf(after, id, u, v, 7f)
            for (i in 0..2) assertEquals("$id [$i]", a[i], b[i], 0.05f)
        }
        // And the group's own chain is now the identity, so a profile captured with it is stored as seen.
        val m = SceneMath.chainModel(planeChain(after, "g"), w, h)
        val p = m.apply(123f, 456f, 78f)
        assertEquals(123f, p[0], 0.05f); assertEquals(456f, p[1], 0.05f); assertEquals(78f, p[2], 0.05f)
    }

    @Test
    fun `a group inside a posed parent rests at the sheet as seen, and later edits to either pose do not move the pivot`() {
        val layers = listOf(
            group("a", offset = Offset(-80f, 40f), scale = 1.3f, rz = 25f, ry = 20f),
            group("g", parent = "a", offset = Offset(15f, 15f), scale = 0.8f, rx = 10f),
            Layer(id = "c", name = "c", parentId = "g"),
        )
        val made = withSheet(layers, "g")
        val p0 = OrbitPivot.of(made, w, h)
        assertPivot(500f, 800f, 454.17f, p0)
        val edited = made.map {
            when (it.id) {
                "a" -> it.copy(offset = Offset(400f, 400f), scale = 2f, rotationZ = 77f)
                "g" -> it.copy(offset = it.offset + Offset(-90f, 20f), scale = 0.3f, rotationX = 55f, positionZ = 200f)
                else -> it
            }
        }
        val p1 = OrbitPivot.of(edited, w, h)
        for (i in 0..2) assertEquals(p0[i], p1[i], 1e-3f)
    }

    @Test
    fun `a chain with no pose is left alone, and a zero scale cannot be rebased`() {
        val layers = listOf(group("g"), Layer(id = "c", name = "c", parentId = "g"))
        assertSame(layers, SurfaceRebase.forCreation(layers, "g", w, h))
        assertEquals(null, SurfaceRebase.forCreation(listOf(group("g", scale = 0f), Layer(id = "c", name = "c", parentId = "g")), "g", w, h))
    }
}
