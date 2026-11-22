@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.ShapeToolMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The pure rules of the guided surface flow (spec/guided-surface.md, sections 2, 3 and 4). */
class SurfaceGuideTest {
    private fun plane(id: String, parent: String? = null) = Layer(id, id, parentId = parent)
    private fun group(id: String, parent: String? = null, surface: SurfaceProfile? = null) =
        Layer(id, id, type = LayerType.GROUP, parentId = parent, surface = surface)

    private val answers = mapOf("content" to LeafContent.CONTENT, "unknown" to LeafContent.UNKNOWN)
    private fun leaf(layer: Layer) = answers[layer.id] ?: LeafContent.EMPTY

    @Test
    fun `an empty plane, an empty group and nested empty groups are an empty project`() {
        assertEquals(LeafContent.EMPTY, SurfaceGuide.projectContent(listOf(plane("a")), ::leaf))
        assertEquals(LeafContent.EMPTY, SurfaceGuide.projectContent(listOf(group("g")), ::leaf))
        val nested = listOf(group("g"), group("h", "g"), plane("p", "h"), plane("q", "h"))
        assertEquals(LeafContent.EMPTY, SurfaceGuide.projectContent(nested, ::leaf))
        assertEquals(LeafContent.EMPTY, SurfaceGuide.projectContent(emptyList(), ::leaf))
    }

    @Test
    fun `a group holding a surface but no art is empty, and a group is content only through what is under it`() {
        val surface = SurfaceProfile(listOf(0f, 0f, 0f, 100f, 0f, 0f))
        val layers = listOf(group("g", surface = surface), plane("view", "g"), plane("view2", "g"))
        assertEquals(LeafContent.EMPTY, SurfaceGuide.projectContent(layers, ::leaf))
        val withArt = listOf(group("g", surface = surface), plane("content", "g"))
        assertEquals(LeafContent.CONTENT, SurfaceGuide.projectContent(withArt, ::leaf))
    }

    @Test
    fun `any content wins over cannot-tell, and cannot-tell is never empty`() {
        assertEquals(LeafContent.CONTENT, SurfaceGuide.projectContent(listOf(plane("unknown"), plane("content")), ::leaf))
        assertEquals(LeafContent.UNKNOWN, SurfaceGuide.projectContent(listOf(plane("a"), plane("unknown")), ::leaf))
    }

    @Test
    fun `hidden and locked layers count exactly like visible ones`() {
        val hidden = plane("content").copy(isVisible = false, isImageLocked = true)
        assertEquals(LeafContent.CONTENT, SurfaceGuide.projectContent(listOf(plane("a"), hidden), ::leaf))
        val posed = plane("a").copy(positionZ = 40f, rotationY = 0.3f, scale = 2f)
        assertEquals(LeafContent.EMPTY, SurfaceGuide.projectContent(listOf(posed), ::leaf))
    }

    @Test
    fun `the reasons come in the spec's order`() {
        val one = listOf(plane("a"))
        assertNull(SurfaceGuide.unavailable(one, true, false, LeafContent.EMPTY))
        assertEquals(SurfaceGuideReason.UNAVAILABLE, SurfaceGuide.unavailable(one, false, true, LeafContent.CONTENT))
        assertEquals(SurfaceGuideReason.NO_LAYER, SurfaceGuide.unavailable(emptyList(), true, true, LeafContent.CONTENT))
        assertEquals(SurfaceGuideReason.LOCKED, SurfaceGuide.unavailable(one, true, true, LeafContent.CONTENT))
        assertEquals(SurfaceGuideReason.CONTENT, SurfaceGuide.unavailable(one, true, false, LeafContent.CONTENT))
        assertEquals(SurfaceGuideReason.UNREADABLE, SurfaceGuide.unavailable(one, true, false, LeafContent.UNKNOWN))
    }

    @Test
    fun `3D availability is a usable canvas`() {
        assertTrue(SurfaceGuide.available(1000f, 1600f))
        assertFalse(SurfaceGuide.available(0f, 1600f))
        assertFalse(SurfaceGuide.available(1000f, -1f))
        assertFalse(SurfaceGuide.available(Float.NaN, 1600f))
        assertFalse(SurfaceGuide.available(1000f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `the target is the active group, the group of the active layer, or the layer to wrap`() {
        val layers = listOf(group("g"), plane("in", "g"), plane("top"), group("deep", "g"), plane("nested", "deep"))
        assertEquals(GuideTarget.Group("g"), SurfaceGuide.target(layers, "g"))
        assertEquals(GuideTarget.Group("g"), SurfaceGuide.target(layers, "in"))
        assertEquals(GuideTarget.Wrap("top"), SurfaceGuide.target(layers, "top"))
        assertEquals(GuideTarget.Group("deep"), SurfaceGuide.target(layers, "nested"))
        assertNull(SurfaceGuide.target(layers, "missing"))
        assertNull(SurfaceGuide.target(layers, null))
    }

    @Test
    fun `the first perspective is the axis the camera looks most nearly along, ties to the front`() {
        assertEquals(SurfaceView.FRONT, SurfaceGuide.firstPerspective(SceneCamera()))
        assertEquals(SurfaceView.SIDE, SurfaceGuide.firstPerspective(SceneCamera(yaw = (PI / 2).toFloat())))
        assertEquals(SurfaceView.SIDE, SurfaceGuide.firstPerspective(SceneCamera(yaw = (-PI / 2).toFloat())))
        assertEquals(SurfaceView.TOP, SurfaceGuide.firstPerspective(SceneCamera(pitch = SceneCamera.MAX_PITCH)))
        // 45 degrees of yaw: x and z tie, and the front wins.
        assertEquals(SurfaceView.FRONT, SurfaceGuide.firstPerspective(SceneCamera(yaw = (PI / 4).toFloat())))
        // Each step's camera shows the perspective it was set for.
        for (v in SurfaceView.entries) assertEquals(v, SurfaceGuide.firstPerspective(v.camera(SceneCamera())))
    }

    @Test
    fun `the second and third perspectives follow in the cyclic order`() {
        assertEquals(listOf(SurfaceView.FRONT, SurfaceView.SIDE, SurfaceView.TOP), SurfaceGuide.order(SurfaceView.FRONT))
        assertEquals(listOf(SurfaceView.SIDE, SurfaceView.TOP, SurfaceView.FRONT), SurfaceGuide.order(SurfaceView.SIDE))
        assertEquals(listOf(SurfaceView.TOP, SurfaceView.FRONT, SurfaceView.SIDE), SurfaceGuide.order(SurfaceView.TOP))
    }

    @Test
    fun `the default tool is the Shape tool's vector mode, else the ellipse, never freehand`() {
        assertEquals(GuideTool.RECTANGLE, SurfaceGuide.defaultTool(ShapeToolMode.RECTANGLE))
        assertEquals(GuideTool.ROUNDED_RECTANGLE, SurfaceGuide.defaultTool(ShapeToolMode.ROUNDED_RECTANGLE))
        assertEquals(GuideTool.ELLIPSE, SurfaceGuide.defaultTool(ShapeToolMode.ELLIPSE))
        assertEquals(GuideTool.LINE, SurfaceGuide.defaultTool(ShapeToolMode.LINE))
        for (m in listOf(ShapeToolMode.FRAME, ShapeToolMode.POLYGON, ShapeToolMode.STAR)) {
            assertEquals(GuideTool.ELLIPSE, SurfaceGuide.defaultTool(m))
        }
        assertTrue(ShapeToolMode.entries.none { SurfaceGuide.defaultTool(it) == GuideTool.FREEHAND })
        assertTrue(GuideTool.PEN.isStroke && GuideTool.FREEHAND.isStroke && !GuideTool.ELLIPSE.isStroke)
    }

    // ── The mirror ──

    /** A closed outline in the plane perpendicular to [view]'s axis, with the sweep along that axis. */
    private fun outline(view: SurfaceView, a: List<Pair<Float, Float>>, depth: Float = 0f): SurfaceProfile {
        val (u, v) = when (view) {
            SurfaceView.FRONT -> 0 to 1
            SurfaceView.SIDE -> 2 to 1
            SurfaceView.TOP -> 0 to 2
        }
        val pts = a.flatMap { (p, q) ->
            FloatArray(3).also { it[u] = p; it[v] = q; it[view.axis] = depth }.toList()
        }
        val sweep = FloatArray(3).also { it[view.axis] = 1f }
        return SurfaceProfile(pts, sweep[0], sweep[1], sweep[2])
    }

    private val rect = listOf(0f to 0f, 60f to 0f, 60f to 20f, 0f to 20f, 0f to 0f)
    private val circle = (0 until 32).map { 50f + 40f * cos(2 * PI.toFloat() * it / 32) to 50f + 40f * sin(2 * PI.toFloat() * it / 32) }

    @Test
    fun `a circle in the Z-view with a rectangle in the X-view mirrors to the same rectangle in the Y-view`() {
        val first = outline(SurfaceView.FRONT, circle)
        // The X-view shows z across and y down: 60 deep by 20 tall, 5 units out from the plane.
        val second = outline(SurfaceView.SIDE, rect, depth = 5f)
        val third = SurfaceGuide.mirrored(second, first, SurfaceView.FRONT, SurfaceView.SIDE)
        // It looks along y now, so its sweep is y and every point shares one y; it shows z (60 wide) and x (20 tall).
        assertEquals(listOf(0f, 1f, 0f), listOf(third.sweepX, third.sweepY, third.sweepZ))
        val ys = (0 until third.count).map { third.point(it)[1] }.toSet()
        assertEquals(1, ys.size)
        val xs = (0 until third.count).map { third.point(it)[0] }
        val zs = (0 until third.count).map { third.point(it)[2] }
        assertEquals(20f, xs.max() - xs.min(), 1e-3f)
        assertEquals(60f, zs.max() - zs.min(), 1e-3f)
        assertTrue(SurfaceGuide.isClosed(third))
        // The first outline's centre is (50, 50, 0): the second sat at x = 5, so it lands at y = 50 + (5 - 50).
        assertEquals(5f, ys.single(), 1e-3f)
    }

    @Test
    fun `the mirror is an involution for every first perspective, open and closed`() {
        val open = listOf(0f to 0f, 30f to 10f, 55f to 40f, 80f to 0f)
        for (first in SurfaceView.entries) {
            val order = SurfaceGuide.order(first)
            val (secondView, thirdView) = order[1] to order[2]
            for (shape in listOf(rect, open)) {
                val firstProfile = outline(first, circle, depth = 3f)
                val second = outline(secondView, shape, depth = 7f)
                val third = SurfaceGuide.mirrored(second, firstProfile, first, secondView)
                val back = SurfaceGuide.mirrored(third, firstProfile, first, thirdView)
                assertEquals("$first/$shape", second.points.size, back.points.size)
                second.points.zip(back.points).forEach { (p, q) -> assertEquals(p, q, 1e-3f) }
                assertEquals(second.sweep.toList(), back.sweep.toList())
                assertEquals(SurfaceGuide.isClosed(second), SurfaceGuide.isClosed(third))
                // The mirrored surface looks along the third perspective's axis.
                assertEquals(1f, third.sweep[thirdView.axis], 1e-6f)
            }
        }
    }

    @Test
    fun `a mirrored surface carries no base, slots or solid flag`() {
        val first = outline(SurfaceView.SIDE, circle)
        val second = outline(SurfaceView.TOP, rect).copy(solid = true, base = SurfaceView.TOP)
        val third = SurfaceGuide.mirrored(second, first, SurfaceView.SIDE, SurfaceView.TOP)
        assertNull(third.base)
        assertFalse(third.solid)
        assertNull(third.front)
    }
}
