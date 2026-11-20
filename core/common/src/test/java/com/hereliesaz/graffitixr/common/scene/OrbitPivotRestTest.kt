@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.isPlacedIn3D
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pivot is measured on REST geometry (spec/3d-space.md, "The orbit point"): no pose enters it, so no edit the user
 * makes to a pose (slider, Move-tool twist, translate, scale, handle) can move it, and only a structural change does.
 */
class OrbitPivotRestTest {
    private val w = 1000f
    private val h = 1600f
    private val eps = 1e-3f

    private fun sheet(id: String, r: SceneRect, parent: String? = null) = Layer(
        id = id, name = id, type = LayerType.GROUP, parentId = parent,
        surface = DefaultSurface.profile(r, w, h, emptyList()),
    )

    private val defaultRect = DefaultSurface.rect(w, h)!!
    private val rectB = SceneRect(600f, 100f, 300f, 500f)
    private val n = ArtSheet.pivotDepth(750f, 1600f * 2f / 3f)

    private fun assertPivot(x: Float, y: Float, z: Float, p: FloatArray, tol: Float = 0.01f) {
        assertEquals("x", x, p[0], tol)
        assertEquals("y", y, p[1], tol)
        assertEquals("z", z, p[2], tol)
    }

    private fun assertSame(a: FloatArray, b: FloatArray, what: String) {
        for (i in 0..2) assertEquals("$what [$i]", a[i], b[i], eps)
    }

    /** Mixed content: a plane beside the surfaces, two sheets with paint, a drawn floor, and a sheet nested in a plain group. */
    private fun mixed(): List<Layer> = listOf(
        Layer(id = "base", name = "base"),
        sheet("a", defaultRect),
        Layer(id = "pa", name = "pa", parentId = "a"),
        sheet("b", rectB),
        Layer(id = "pb", name = "pb", parentId = "b"),
        Layer(id = "o", name = "o", type = LayerType.GROUP),
        sheet("c", SceneRect(300f, 300f, 200f, 200f), parent = "o"),
        Layer(id = "pc", name = "pc", parentId = "c"),
        Layer(id = "side", name = "side", parentId = "o"),
        Layer(
            id = "floor", name = "floor", type = LayerType.GROUP,
            surface = SurfaceProfile(listOf(200f, 1000f, 0f, 800f, 1000f, 0f), 0f, 0f, 1f),
        ),
        Layer(id = "pf", name = "pf", parentId = "floor"),
    )

    /** One user edit to a random layer's pose, through the same maths the editor uses; [held] is the pivot as the gesture began. */
    private fun edited(layers: List<Layer>, rng: Random, held: FloatArray): List<Layer> {
        val target = layers[rng.nextInt(layers.size)]
        val turn = { axis: RotationAxis ->
            val delta = (rng.nextFloat() - 0.5f) * 140f
            if (axis == RotationAxis.Z && !target.isPlacedIn3D) {
                target.copy(rotationZ = target.rotationZ + delta)
            } else {
                val parent = SceneMath.chainModel(planeChain(layers, target.id).dropLast(1), w, h).inverse()!!
                val pivot = parent.apply(held[0], held[1], held[2])
                val pose = SceneMath.rotatePoseAbout(target.planePose, axis, delta, pivot, w, h)
                target.copy(
                    offset = Offset(pose.offsetX, pose.offsetY), positionZ = pose.positionZ,
                    rotationX = pose.rotationX, rotationY = pose.rotationY, rotationZ = pose.rotationZ,
                )
            }
        }
        val next = when (rng.nextInt(5)) {
            0 -> turn(RotationAxis.entries[rng.nextInt(3)]) // the slider
            1 -> turn(RotationAxis.Z) // the Move tool's twist
            2 -> target.copy(offset = target.offset + Offset(rng.nextFloat() * 400f - 200f, rng.nextFloat() * 400f - 200f))
            3 -> target.copy(scale = (target.scale * (0.5f + rng.nextFloat() * 1.5f)).coerceIn(0.2f, 5f))
            else -> target.copy(positionZ = target.positionZ + rng.nextFloat() * 300f - 150f) // a pinch in depth
        }
        return layers.map { if (it.id == target.id) next else it }
    }

    @Test
    fun `random slider turns, twists, translations and scales never move the pivot, on many surfaces and mixed content`() {
        repeat(20) { seed ->
            val rng = Random(seed)
            var layers = mixed()
            val p0 = OrbitPivot.resolve(layers, w, h)
            repeat(60) { step ->
                layers = edited(layers, rng, p0)
                assertSame(p0, OrbitPivot.resolve(layers, w, h), "seed $seed step $step")
                assertSame(p0, OrbitPivot.of(layers, w, h), "of, seed $seed step $step")
                assertSame(p0, OrbitPivot.ofSurfaces(layers, w, h)!!, "ofSurfaces, seed $seed step $step")
            }
            assertTrue("the layers really were posed", layers.any { it.offset != Offset.Zero || it.scale != 1f || it.positionZ != 0f })
        }
    }

    @Test
    fun `the same holds on one surface alone and on one group among several`() {
        for (layers0 in listOf(listOf(sheet("a", defaultRect)), listOf(sheet("a", defaultRect), Layer(id = "p", name = "p", parentId = "a")), mixed().take(3))) {
            val rng = Random(7)
            var layers = layers0
            val p0 = OrbitPivot.resolve(layers, w, h)
            repeat(80) {
                layers = edited(layers, rng, p0)
                assertSame(p0, OrbitPivot.resolve(layers, w, h), "step $it")
            }
        }
    }

    @Test
    fun `with no surface the pivot is the canvas's assumed box whatever the layers' poses`() {
        val d = OrbitPivot.assumedDepth(w, h)
        val rng = Random(3)
        var layers = listOf(Layer(id = "a", name = "a"), Layer(id = "b", name = "b"))
        assertPivot(500f, 800f, d / 2f, OrbitPivot.of(layers, w, h))
        repeat(40) { layers = edited(layers, rng, floatArrayOf(500f, 800f, d / 2f)) }
        assertPivot(500f, 800f, d / 2f, OrbitPivot.of(layers, w, h))
        assertPivot(500f, 800f, d / 2f, OrbitPivot.of(emptyList(), w, h))
        // A layer stood edge-on at depth no longer pulls it (before, the union of the planes did).
        assertPivot(500f, 800f, d / 2f, OrbitPivot.of(listOf(Layer(id = "a", name = "a"), Layer(id = "b", name = "b", positionZ = 300f, rotationY = 90f)), w, h))
    }

    @Test
    fun `a group saved with a pose is measured at rest, the same as the unposed group`() {
        val posed = sheet("a", defaultRect).copy(offset = Offset(300f, -120f), scale = 1.7f, rotationX = 35f, rotationY = -20f, rotationZ = 50f, positionZ = 90f)
        assertSame(OrbitPivot.of(listOf(sheet("a", defaultRect)), w, h), OrbitPivot.of(listOf(posed), w, h), "posed")
        val nested = listOf(
            Layer(id = "o", name = "o", type = LayerType.GROUP, offset = Offset(-40f, 80f), rotationZ = 33f, scale = 0.6f),
            sheet("a", defaultRect, parent = "o").copy(rotationY = 70f, positionZ = -50f),
        )
        assertSame(OrbitPivot.of(listOf(sheet("a", defaultRect)), w, h), OrbitPivot.of(nested, w, h), "nested")
    }

    @Test
    fun `the default sheet gives 500, 800, 454_2 as before, with or without a base layer and paint`() {
        assertPivot(500f, 800f, 454.17f, OrbitPivot.of(listOf(sheet("a", defaultRect)), w, h))
        assertPivot(500f, 800f, 454.17f, OrbitPivot.of(listOf(Layer(id = "base", name = "base"), sheet("a", defaultRect), Layer(id = "p", name = "p", parentId = "a")), w, h))
        assertEquals(454.1667f, n, 0.001f)
    }

    // ── What does move it: structural changes, between gestures ──

    @Test
    fun `adding or removing a surface re-derives the pivot`() {
        val a = sheet("a", defaultRect)
        val two = OrbitPivot.of(listOf(a, sheet("b", rectB)), w, h)
        assertPivot(512.5f, 716.6667f, 454.17f, two)
        assertPivot(500f, 800f, 454.17f, OrbitPivot.of(listOf(a), w, h))
        val three = OrbitPivot.of(listOf(a, sheet("b", rectB), sheet("c", SceneRect(10f, 10f, 100f, 100f))), w, h)
        assertTrue(three[0] != two[0] || three[1] != two[1])
    }

    @Test
    fun `redrawing or resizing a surface re-derives the pivot`() {
        // A 200 x 200 sheet at (100, 100): centre (200, 200), n = 100 behind it.
        assertPivot(200f, 200f, 100f, OrbitPivot.of(listOf(sheet("a", SceneRect(100f, 100f, 200f, 200f))), w, h))
        // The same group given a 400 x 500 sheet at (100, 100): centre (300, 350), n = 225 behind it.
        assertPivot(300f, 350f, 225f, OrbitPivot.of(listOf(sheet("a", SceneRect(100f, 100f, 400f, 500f))), w, h))
    }

    @Test
    fun `the first content layer beside the surfaces joins the box and the last leaving takes it out, more of them change nothing`() {
        val a = sheet("a", SceneRect(100f, 100f, 200f, 200f))
        assertPivot(200f, 200f, 100f, OrbitPivot.of(listOf(a), w, h))
        // Paint on the surface is not beside it.
        assertPivot(200f, 200f, 100f, OrbitPivot.of(listOf(a, Layer(id = "pa", name = "pa", parentId = "a")), w, h))
        // The canvas plane joins: x 0..1000, y 0..1600, z 0..200.
        val withBase = OrbitPivot.of(listOf(Layer(id = "base", name = "base"), a), w, h)
        assertPivot(500f, 800f, 100f, withBase)
        assertSame(withBase, OrbitPivot.of(listOf(Layer(id = "base", name = "base"), Layer(id = "two", name = "two", scale = 3f, offset = Offset(80f, 90f)), a), w, h), "second layer")
        assertPivot(200f, 200f, 100f, OrbitPivot.of(listOf(a), w, h))
    }

    @Test
    fun `painting never moves the pivot, because a sheet is measured when it is made`() {
        // Strokes change a layer's pixels, not its surface or its place in the stack: nothing the box is made from.
        val a = sheet("a", defaultRect)
        val before = OrbitPivot.of(listOf(a, Layer(id = "p", name = "p", parentId = "a")), w, h)
        val painted = listOf(a, Layer(id = "p", name = "p", parentId = "a", isSketch = true), Layer(id = "q", name = "q", parentId = "a"))
        assertSame(before, OrbitPivot.of(painted, w, h), "painted")
    }

    @Test
    fun `a turn about the pivot leaves the pivot, and the pivot of the turned layers is the pivot that turned them`() {
        // The closing check on the owner's complaint: turn a sheet about the pivot, then turn it again - the second turn
        // revolves about exactly the point the first did.
        var layers = listOf(Layer(id = "base", name = "base"), sheet("a", defaultRect), sheet("b", rectB))
        val p0 = OrbitPivot.resolve(layers, w, h)
        repeat(5) {
            val a = layers.first { it.id == "a" }
            val pose = SceneMath.rotatePoseAbout(a.planePose, RotationAxis.X, 37f, OrbitPivot.resolve(layers, w, h), w, h)
            layers = layers.map {
                if (it.id == "a") it.copy(offset = Offset(pose.offsetX, pose.offsetY), positionZ = pose.positionZ, rotationX = pose.rotationX, rotationY = pose.rotationY, rotationZ = pose.rotationZ) else it
            }
            assertSame(p0, OrbitPivot.resolve(layers, w, h), "turn $it")
        }
    }
}
