@file:Suppress("MaxLineLength", "MagicNumber", "LoopWithTooManyJumpStatements")

package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

class InactiveLayerCacheTest {
    private class Res(val name: String) { var freed = false }

    private fun cache(): Pair<SplitCache<Res>, MutableList<Res>> {
        val freed = mutableListOf<Res>()
        return SplitCache<Res> { it.freed = true; freed += it } to freed
    }

    // ---- The two slots ----------------------------------------------------------------------

    @Test
    fun `changing the active layer dumps both renders`() {
        val (c, freed) = cache()
        c.select("a")
        val b = c.get(SplitCache.Slot.BEHIND, "k1") { Res("behind") }!!
        val f = c.get(SplitCache.Slot.FRONT, "k2") { Res("front") }!!
        assertEquals(2, c.liveCount)
        c.select("a")
        assertEquals(2, c.liveCount)
        c.select("b")
        assertEquals(0, c.liveCount)
        assertTrue(b.freed && f.freed)
        assertEquals(listOf(b, f), freed)
    }

    @Test
    fun `an unchanged key reuses the render and a changed one rebuilds it`() {
        val (c, _) = cache()
        c.select("a")
        val first = c.get(SplitCache.Slot.BEHIND, listOf("cam", "layers")) { Res("1") }
        val again = c.get(SplitCache.Slot.BEHIND, listOf("cam", "layers")) { error("must not rebuild") }
        assertTrue(first === again)
        val rebuilt = c.get(SplitCache.Slot.BEHIND, listOf("cam", "layers changed")) { Res("2") }
        assertTrue(first!!.freed)
        assertFalse(rebuilt!!.freed)
        assertEquals(2, c.builds)
        assertEquals(1, c.dumps)
    }

    @Test
    fun `each invalidation trigger rebuilds its render`() {
        // The keys the renderers use: the view, and per member its content, visibility, opacity,
        // blend mode, transform and the members' order. Any one of them changing must rebuild.
        val base = mapOf("content" to 1, "visible" to true, "opacity" to 1f, "blend" to "SrcOver", "transform" to 0f)
        fun key(view: Any, members: List<Map<String, Any>>) = listOf(view, members)
        val triggers = listOf<(Map<String, Any>) -> Map<String, Any>>(
            { it + ("content" to 2) },
            { it + ("visible" to false) },
            { it + ("opacity" to 0.5f) },
            { it + ("blend" to "Multiply") },
            { it + ("transform" to 10f) },
        )
        for (trigger in triggers) {
            val (c, _) = cache()
            c.select("active")
            val other = base + ("content" to 9)
            c.get(SplitCache.Slot.FRONT, key("view", listOf(base, other))) { Res("old") }
            var built = false
            c.get(SplitCache.Slot.FRONT, key("view", listOf(trigger(base), other))) { built = true; Res("new") }
            assertTrue(built)
        }
        // Order.
        val (c, _) = cache()
        val a = base
        val b = base + ("content" to 2)
        c.get(SplitCache.Slot.BEHIND, key("view", listOf(a, b))) { Res("ab") }
        var built = false
        c.get(SplitCache.Slot.BEHIND, key("view", listOf(b, a))) { built = true; Res("ba") }
        assertTrue(built)
        // The view (camera).
        built = false
        c.get(SplitCache.Slot.BEHIND, key("orbited", listOf(b, a))) { built = true; Res("view") }
        assertTrue(built)
    }

    @Test
    fun `never more than two renders exist`() {
        val (c, _) = cache()
        val rnd = Random(7)
        repeat(500) {
            when (rnd.nextInt(4)) {
                0 -> c.select("layer${rnd.nextInt(3)}")
                1 -> c.dump(SplitCache.Slot.entries.random(rnd))
                else -> c.get(SplitCache.Slot.entries.random(rnd), rnd.nextInt(5)) { Res("r") }
            }
            assertTrue(c.liveCount <= 2)
        }
        assertEquals(c.builds - c.dumps, c.liveCount)
    }

    @Test
    fun `a build that declines caches nothing and frees the stale render`() {
        val (c, _) = cache()
        val old = c.get(SplitCache.Slot.BEHIND, 1) { Res("old") }!!
        assertNull(c.get(SplitCache.Slot.BEHIND, 2) { null })
        assertTrue(old.freed)
        assertEquals(0, c.liveCount)
    }

    @Test
    fun `the view must rest one frame before a rebuild`() {
        val gate = RestGate()
        assertFalse(gate.settled(SceneCamera(yaw = 0.1f)))
        assertFalse(gate.settled(SceneCamera(yaw = 0.2f)))
        assertFalse(gate.settled(SceneCamera(yaw = 0.3f)))
        assertTrue(gate.settled(SceneCamera(yaw = 0.3f)))
        assertTrue(gate.settled(SceneCamera(yaw = 0.3f)))
    }

    // ---- The 2D split ------------------------------------------------------------------------

    @Test
    fun `the 2D split is stack order and caches only plain source-over sides`() {
        val ids = listOf("a", "b", "active", "c", "d")
        val split = StackSplit.plan(ids, "active") { it != "d" }!!
        assertEquals(listOf("a", "b"), split.behind)
        assertEquals(listOf("c", "d"), split.front)
        assertTrue(split.cacheBehind)
        assertFalse("a blend mode in front would see another backdrop", split.cacheFront)
        val bottom = StackSplit.plan(ids, "a") { true }!!
        assertFalse(bottom.cacheBehind)
        assertNull(StackSplit.plan(ids, "missing") { true })
    }

    // ---- The 3D split ------------------------------------------------------------------------

    private val w = 1000f
    private val h = 800f

    private fun plane(id: String, pose: PlanePose): DepthSplit.Member {
        val m = SceneMath.model(pose, w, h)
        return DepthSplit.Member(id, listOf(m.apply(0f, 0f, 0f), m.apply(w, 0f, 0f), m.apply(w, h, 0f), m.apply(0f, h, 0f)), planar = true)
    }

    @Test
    fun `depth order from the view splits planes behind and in front of the active one`() {
        val cam = SceneMath.frame(SceneCamera(yaw = 0.3f), w, h)
        val members = listOf(
            plane("far", PlanePose(positionZ = 400f)),
            plane("active", PlanePose()),
            plane("near", PlanePose(positionZ = -300f, scale = 0.5f)),
        )
        val split = DepthSplit.classify(cam, "active", members, listOf("far", "active", "near"))
        assertEquals(DepthSplit.Split(listOf("far"), listOf("near")), split)
    }

    @Test
    fun `turning the camera round swaps behind and in front`() {
        val cam = SceneMath.frame(SceneCamera(yaw = kotlin.math.PI.toFloat()), w, h)
        val members = listOf(plane("far", PlanePose(positionZ = 400f)), plane("active", PlanePose()))
        val split = DepthSplit.classify(cam, "active", members, listOf("active", "far"))
        assertEquals(DepthSplit.Split(emptyList(), listOf("far")), split)
    }

    @Test
    fun `a layer crossing the active plane falls back to uncached`() {
        val cam = SceneMath.frame(SceneCamera(yaw = 0.2f), w, h)
        val members = listOf(plane("active", PlanePose()), plane("crossing", PlanePose(rotationY = 60f)))
        assertTrue(DepthSplit.classify(cam, "active", members, listOf("crossing", "active")) is DepthSplit.Uncached)
    }

    @Test
    fun `a draw order that disagrees with depth order falls back to uncached`() {
        val cam = SceneMath.frame(SceneCamera(), w, h)
        val members = listOf(plane("far", PlanePose(positionZ = 400f)), plane("active", PlanePose()))
        // A translucent behind layer is drawn after the opaque active one by the uncached pass.
        assertTrue(DepthSplit.classify(cam, "active", members, listOf("active", "far")) is DepthSplit.Uncached)
    }

    @Test
    fun `active 3D ink splits by depth range`() {
        val cam = SceneMath.frame(SceneCamera(), w, h)
        val ink = DepthSplit.Member("ink", DepthSplit.boxCorners(floatArrayOf(400f, 300f, 100f, 600f, 500f, 200f), Affine3.IDENTITY), planar = false)
        val members = listOf(ink, plane("far", PlanePose(positionZ = 400f)), plane("near", PlanePose(positionZ = -200f, scale = 0.3f)), plane("mid", PlanePose(positionZ = 150f)))
        assertTrue(DepthSplit.classify(cam, "ink", members, listOf("ink", "far", "mid", "near")) is DepthSplit.Uncached)
        val split = DepthSplit.classify(cam, "ink", members.filter { it.id != "mid" }, listOf("far", "ink", "near"))
        assertEquals(DepthSplit.Split(listOf("far"), listOf("near")), split)
    }

    // ---- Pixel equality: the GLES pipeline, emulated in 8 bits -------------------------------

    /** A member's fragment at one pixel: premultiplied 8-bit colour and its depth. */
    private class Frag(val rgba: IntArray, val depth: Float)

    private class Draw(val id: String, val blended: Boolean, val frag: Frag?)

    private class Target {
        val rgba = IntArray(4)
        var depth = 1f
    }

    /** GL_ONE, GL_ONE_MINUS_SRC_ALPHA into an RGBA8 target, LEQUAL test, rounding at the write. */
    private fun blend(dst: IntArray, src: IntArray) {
        val inv = (255 - src[3]) / 255.0
        for (i in 0..3) dst[i] = (src[i] + dst[i] * inv).roundToInt().coerceIn(0, 255)
    }

    private fun draw(t: Target, draws: List<Draw>) {
        for (d in draws) {
            val f = d.frag ?: continue
            if (f.depth > t.depth) continue
            blend(t.rgba, f.rgba)
            if (!d.blended) t.depth = f.depth
        }
    }

    private fun frag(rnd: Random, lo: Float, hi: Float): Frag? {
        if (rnd.nextInt(4) == 0) return null
        val a = listOf(255, 255, rnd.nextInt(1, 256)).random(rnd)
        val rgba = IntArray(4) { if (it == 3) a else rnd.nextInt(0, a + 1) }
        return Frag(rgba, lo + rnd.nextFloat() * (hi - lo))
    }

    @Test
    fun `cached and uncached depth composites are equal, behind exactly`() {
        val rnd = Random(42)
        var worstFront = 0
        repeat(20_000) {
            val behind = List(rnd.nextInt(0, 4)) { Draw("b$it", rnd.nextBoolean(), frag(rnd, 0.6f, 0.95f)) }
                .sortedBy { it.blended } // the uncached pass: opaque, then blended
            val active = Draw("active", false, frag(rnd, 0.45f, 0.55f))
            val front = List(rnd.nextInt(0, 4)) { Draw("f$it", true, frag(rnd, 0.05f, 0.4f)) }
            val uncached = Target().also { draw(it, behind + active + front) }

            val fb = Target()
            val behindRender = Target().also { draw(it, behind) }
            blend(fb.rgba, behindRender.rgba) // the blit, over a cleared framebuffer
            draw(fb, listOf(active))
            val uncachedSoFar = Target().also { draw(it, behind + active) }
            for (i in 0..3) assertEquals("behind + active must be bit-exact", uncachedSoFar.rgba[i], fb.rgba[i])

            val frontRender = Target().also { draw(it, front) }
            blend(fb.rgba, frontRender.rgba)
            for (i in 0..3) worstFront = maxOf(worstFront, abs(uncached.rgba[i] - fb.rgba[i]))
        }
        // Source-over is associative; the only difference is where an 8-bit write rounds.
        assertTrue("front render differs by $worstFront", worstFront <= 1)
    }
}
