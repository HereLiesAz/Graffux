@file:Suppress("MaxLineLength", "MagicNumber", "LongParameterList")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A surface made on a posed group rests where it is seen, so the pivot is the owner's rule for that sheet, and the paint
 * on it is placed exactly as it was before (measured through [SurfaceMath.flatSurfaceModel], the real placement).
 */
class SurfaceRebaseTest {
    private val w = 1000f
    private val h = 1600f
    private val rect = DefaultSurface.rect(w, h)!!

    private fun group(
        id: String, parent: String? = null, offset: Offset = Offset.Zero, scale: Float = 1f,
        rx: Float = 0f, ry: Float = 0f, rz: Float = 0f, z: Float = 0f,
    ) = Layer(
        id = id, name = id, type = LayerType.GROUP, parentId = parent, offset = offset, scale = scale,
        rotationX = rx, rotationY = ry, rotationZ = rz, positionZ = z,
    )

    private val childPose = Layer(
        id = "c", name = "c", parentId = "g", offset = Offset(30f, 20f), scale = 1.4f, rotationZ = 10f,
        rotationX = 25f, positionZ = 40f,
    )

    private fun assertPivot(x: Float, y: Float, z: Float, p: FloatArray, tol: Float = 0.05f) {
        assertEquals("x", x, p[0], tol)
        assertEquals("y", y, p[1], tol)
        assertEquals("z", z, p[2], tol)
    }

    /** What a user got before the rebase: the profile stored through the inverse of the group's chain. */
    private fun naive(layers: List<Layer>, id: String) = layers.map {
        if (it.id == id) it.copy(surface = DefaultSurface.profile(rect, w, h, planeChain(layers, id))) else it
    }

    private fun rebased(layers: List<Layer>, id: String): List<Layer> {
        val after = SurfaceRebase.forCreation(layers, id, w, h)!!
        return after.map { if (it.id == id) it.copy(surface = DefaultSurface.profile(rect, w, h, emptyList())) else it }
    }

    private fun paint(layers: List<Layer>, id: String, u: Float, v: Float): FloatArray =
        SurfaceMath.flatSurfaceModel(SurfaceMath.ownerOf(layers, id)!!, w, h)!!.apply(u, v, 0f)

    /** Where paint on the sheet lands through the real placement, before (the old path) and after the rebase. */
    private fun assertPaintUnchanged(layers: List<Layer>, what: String) {
        val before = naive(layers, "g")
        val after = rebased(layers, "g")
        val leaves = after.filter { it.id != "g" && it.type != LayerType.GROUP && SurfaceMath.ownerOf(after, it.id)?.group?.id == "g" }
        assertTrue("$what has paint to measure", leaves.isNotEmpty())
        for (leaf in leaves) for ((u, v) in listOf(0f to 0f, 100f to 200f, 700f to -50f)) {
            val a = paint(before, leaf.id, u, v)
            val b = paint(after, leaf.id, u, v)
            for (i in 0..2) assertEquals("$what ${leaf.id} ($u, $v) [$i]", a[i], b[i], 0.05f)
        }
        val pivot = OrbitPivot.of(after, w, h)
        val n = ArtSheet.pivotDepth(rect.width, rect.height)
        assertPivot(rect.x + rect.width / 2f, rect.y + rect.height / 2f, n, pivot)
    }

    /** [g] with a child that has a grandchild, and a nested plain group holding a leaf that holds another leaf. */
    private fun deeper(g: Layer, parent: String? = null): List<Layer> = listOfNotNull(
        parent?.let { group(it, offset = Offset(-80f, 40f), scale = 1.3f, rz = 25f, ry = 20f) },
        g, childPose.copy(parentId = "g"),
        Layer(id = "gc", name = "gc", parentId = "c", offset = Offset(-5f, 9f), scale = 0.9f, rotationY = 12f, rotationZ = 8f),
        group("sub", parent = "g", offset = Offset(40f, -30f), scale = 1.5f, rz = 20f, rx = 15f, z = 60f),
        Layer(id = "leaf", name = "leaf", parentId = "sub", offset = Offset(10f, 10f), scale = 0.8f, rotationZ = -15f),
        Layer(id = "leaf2", name = "leaf2", parentId = "leaf", offset = Offset(7f, -3f), rotationX = 20f),
    )

    private fun cases(): Map<String, List<Layer>> {
        val c = childPose
        val plain = Layer(id = "p", name = "p", parentId = "g")
        return mapOf(
            "offset and scale" to listOf(group("g", offset = Offset(300f, 0f), scale = 0.5f), c, plain),
            "rotationZ" to listOf(group("g", rz = 33f, offset = Offset(-40f, 90f)), c, plain),
            "rotationX" to listOf(group("g", offset = Offset(300f, 0f), scale = 0.5f, rx = 30f), c, plain),
            "rotationY" to listOf(group("g", offset = Offset(300f, 0f), scale = 0.5f, ry = -35f), c, plain),
            "positionZ" to listOf(group("g", scale = 0.7f, z = 150f), c, plain),
            "all of them" to listOf(group("g", offset = Offset(60f, -20f), scale = 1.3f, rx = 20f, ry = 15f, rz = 40f, z = -80f), c, plain),
            "grandchildren and a nested group with paint below it" to deeper(group("g", offset = Offset(300f, 0f), scale = 0.5f, rx = 30f, rz = 20f)),
            "grandchildren under a posed parent" to deeper(group("g", parent = "a", offset = Offset(15f, 15f), scale = 0.8f, ry = 25f), "a"),
            "inside a posed parent" to listOf(
                group("a", offset = Offset(-80f, 40f), scale = 1.3f, rz = 25f, ry = 20f),
                group("g", parent = "a", offset = Offset(15f, 15f), scale = 0.8f, rx = 10f), c.copy(parentId = "g"), plain,
            ),
            "inside two posed parents" to listOf(
                group("a", offset = Offset(100f, 0f), scale = 0.6f, rx = 30f),
                group("b", parent = "a", offset = Offset(-20f, 50f), rz = 70f, z = 90f),
                group("g", parent = "b", scale = 1.2f, ry = 40f), c.copy(parentId = "g"), plain,
            ),
        )
    }

    @Test
    fun `the probe - a group at offset 300 and scale 0_5 gave a pivot 600 px sideways and 908 deep when the profile went through the pose`() {
        val layers = listOf(group("g", offset = Offset(300f, 0f), scale = 0.5f), Layer(id = "c", name = "c", parentId = "g"))
        assertPivot(-100f, 800f, 908.3f, OrbitPivot.of(naive(layers, "g"), w, h), 0.5f)
        assertPivot(500f, 800f, 454.17f, OrbitPivot.of(rebased(layers, "g"), w, h))
    }

    @Test
    fun `paint lands exactly where it did, and the pivot is the owner's rule, for every kind of pose`() {
        for ((name, layers) in cases()) assertPaintUnchanged(layers, name)
    }

    @Test
    fun `only the group's pose and its direct children's flat scale and offset change`() {
        for ((name, layers) in cases()) {
            val after = SurfaceRebase.forCreation(layers, "g", w, h)!!
            val k = SceneMath.chainModel(planeChain(layers, "g"), w, h).column(0).let { Math.sqrt((it[0] * it[0] + it[1] * it[1] + it[2] * it[2]).toDouble()).toFloat() }
            for (l in layers.filter { it.id != "g" }) {
                val now = after.first { it.id == l.id }
                if (l.parentId != "g") { assertSame("$name ${l.id}", l, now); continue }
                assertEquals("$name ${l.id} scale", l.scale * k, now.scale, 1e-4f)
                assertEquals("$name ${l.id} offset x", l.offset.x * k + (k - 1f) * 500f, now.offset.x, 1e-2f)
                assertEquals("$name ${l.id} offset y", l.offset.y * k + (k - 1f) * 800f, now.offset.y, 1e-2f)
                assertEquals(l.rotationX, now.rotationX, 0f); assertEquals(l.rotationY, now.rotationY, 0f)
                assertEquals(l.rotationZ, now.rotationZ, 0f); assertEquals(l.positionZ, now.positionZ, 0f)
            }
            val chain = SceneMath.chainModel(planeChain(after, "g"), w, h)
            for ((u, v, z) in listOf(Triple(0f, 0f, 0f), Triple(1000f, 1600f, 0f), Triple(123f, 456f, 78f))) {
                val p = chain.apply(u, v, z)
                assertEquals("$name x", u, p[0], SurfaceRebase.REBUILD_TOLERANCE)
                assertEquals("$name y", v, p[1], SurfaceRebase.REBUILD_TOLERANCE)
                assertEquals("$name z", z, p[2], SurfaceRebase.REBUILD_TOLERANCE)
            }
        }
    }

    @Test
    fun `later edits to the group or its parents do not move the pivot`() {
        val layers = cases().getValue("inside two posed parents")
        val made = rebased(layers, "g")
        val p0 = OrbitPivot.of(made, w, h)
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
    fun `300 fixed random trees, gimbal included, all rebase to an identity chain within the tolerance`() {
        var worst = 0f
        for (seed in 0 until 300) {
            val rng = Random(seed)
            fun pick() = if (rng.nextInt(5) == 0) 90f * (if (rng.nextBoolean()) 1 else -1) else rng.nextFloat() * 360f - 180f
            val depth = 1 + rng.nextInt(3)
            val layers = ArrayList<Layer>()
            var parent: String? = null
            for (i in 0 until depth) {
                val id = if (i == depth - 1) "g" else "p$i"
                layers += group(
                    id, parent, Offset(rng.nextFloat() * 800f - 400f, rng.nextFloat() * 800f - 400f),
                    0.4f + rng.nextFloat() * 1.6f, pick(), pick(), pick(), rng.nextFloat() * 400f - 200f,
                )
                parent = id
            }
            val after = SurfaceRebase.forCreation(layers, "g", w, h)
            assertNotNull("seed $seed", after)
            val chain = SceneMath.chainModel(planeChain(after!!, "g"), w, h)
            for ((u, v, z) in listOf(Triple(0f, 0f, 0f), Triple(1000f, 1600f, 0f), Triple(0f, 1600f, 500f))) {
                val p = chain.apply(u, v, z)
                worst = maxOf(worst, abs(p[0] - u), abs(p[1] - v), abs(p[2] - z))
            }
        }
        assertTrue("worst world error $worst px", worst <= SurfaceRebase.REBUILD_TOLERANCE)
    }

    @Test
    fun `an invertible chain that does not rebuild is declined - mirrored parents`() {
        // A negative scale is invertible but improper (a mirror): the pose that would undo it has a positive scale, so
        // no PlanePose rebuilds the identity, and the rebuild check is what says so.
        for ((i, a) in listOf(
            group("a", scale = -1f, offset = Offset(30f, 0f)),
            group("a", scale = -0.5f, rx = 30f, rz = 40f, offset = Offset(-60f, 20f)),
            group("a", scale = -2f, ry = 70f),
        ).withIndex()) {
            val layers = listOf(a, group("g", parent = "a"))
            assertNotNull("case $i is invertible", SceneMath.chainModel(planeChain(layers, "g").dropLast(1), w, h).inverse())
            assertNull("case $i", SurfaceRebase.forCreation(layers, "g", w, h))
        }
    }

    @Test
    fun `parents within float rounding of gimbal rebuild inside the tolerance, none is returned off by more`() {
        for (i in 0 until 400) {
            val ry = 90f - 0.0005f * (i + 1) / 4f
            val layers = listOf(group("a", rx = 30f, ry = ry, rz = 50f, offset = Offset(60f, 10f)), group("g", parent = "a"))
            val after = SurfaceRebase.forCreation(layers, "g", w, h) ?: continue
            val chain = SceneMath.chainModel(planeChain(after, "g"), w, h)
            for ((u, v, z) in listOf(Triple(0f, 0f, 0f), Triple(1000f, 1600f, 0f), Triple(0f, 1600f, 500f))) {
                val p = chain.apply(u, v, z)
                assertTrue("ry $ry", maxOf(abs(p[0] - u), abs(p[1] - v), abs(p[2] - z)) <= SurfaceRebase.REBUILD_TOLERANCE)
            }
        }
    }

    @Test
    fun `a chain with no pose is left alone, and the cases that are declined are`() {
        val plain = listOf(group("g"), Layer(id = "c", name = "c", parentId = "g"))
        assertSame(plain, SurfaceRebase.forCreation(plain, "g", w, h))
        // A zero scale or a non-finite pose above the group cannot be inverted.
        val zero = listOf(group("a", scale = 0f), group("g", parent = "a"))
        assertNull(SurfaceRebase.forCreation(zero, "g", w, h))
        val nan = listOf(group("a", offset = Offset(Float.NaN, 0f)), group("g", parent = "a"))
        assertNull(SurfaceRebase.forCreation(nan, "g", w, h))
        // A group that holds another surface: that one's whole chain runs through this group's pose.
        val nested = listOf(
            group("g", offset = Offset(10f, 0f)),
            group("inner", parent = "g").copy(surface = DefaultSurface.profile(rect, w, h, emptyList())),
        )
        assertNull(SurfaceRebase.forCreation(nested, "g", w, h))
        // A zero scale on the group itself: its chain is singular (it draws nothing), so the old path's profile is null too.
        assertNull(SurfaceRebase.forCreation(listOf(group("g", scale = 0f, offset = Offset(5f, 5f))), "g", w, h))
    }
}
