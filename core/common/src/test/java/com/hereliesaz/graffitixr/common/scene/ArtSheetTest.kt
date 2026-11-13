package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerFrame
import com.hereliesaz.graffitixr.common.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtSheetTest {
    /** The sheet's pad on each side, and the tolerances just above it: they constrain the pad, not hide it. */
    private val xy = ArtSheet.EDGE_PAD + 1e-3f
    private val wh = 2 * ArtSheet.EDGE_PAD + 1e-3f

    /** The derived orbit point for the group an art sheet [p] makes (identity group pose, 1000 x 1600 canvas). */
    private fun pivotOf(p: ArtSheetPlan): FloatArray {
        val profile = DefaultSurface.profile(p.rect, 1000f, 1600f, emptyList())!!
        val group = Layer(id = "g", name = "g", type = LayerType.GROUP, surface = profile)
        return OrbitPivot.resolve(listOf(group), 1000f, 1600f)
    }

    private fun sheetGroup(id: String, p: ArtSheetPlan) = Layer(
        id = id, name = id, type = LayerType.GROUP,
        surface = DefaultSurface.profile(p.rect, 1000f, 1600f, emptyList()),
    )

    private fun plan(box: FloatArray, pose: PlanePose = PlanePose(), w: Float = 1000f, h: Float = 1600f) =
        ArtSheet.plan(box, pose, w, h)

    @Test
    fun `the pivot depth is half the average of width and height`() {
        for ((w, h) in listOf(200f to 400f, 100f to 100f, 640f to 90f, 16f to 1000f)) {
            assertEquals(((w + h) / 2f) / 2f, ArtSheet.pivotDepth(w, h), 1e-4f)
        }
        assertEquals(150f, ArtSheet.pivotDepth(200f, 400f), 1e-4f)
    }

    @Test
    fun `a layer at rest gets its art box plus the edge pad and a derived pivot behind its centre`() {
        for ((w, h) in listOf(200f to 400f, 120f to 120f, 640f to 90f)) {
            val p = plan(floatArrayOf(100f, 200f, 100f + w, 200f + h))!!
            assertEquals(100f - ArtSheet.EDGE_PAD, p.rect.x, 1e-3f)
            assertEquals(200f - ArtSheet.EDGE_PAD, p.rect.y, 1e-3f)
            assertEquals(w + 2 * ArtSheet.EDGE_PAD, p.rect.width, 1e-3f)
            assertEquals(h + 2 * ArtSheet.EDGE_PAD, p.rect.height, 1e-3f)
            val pivot = pivotOf(p)
            assertEquals(100f + w / 2f, pivot[0], 1e-3f)
            assertEquals(200f + h / 2f, pivot[1], 1e-3f)
            // Positive z is away from the viewer: behind the sheet.
            assertEquals(((w + h) / 2f) / 2f, pivot[2], 1e-2f)
            assertTrue(pivot[2] > 0f)
        }
    }

    @Test
    fun `an offset and a scale move and size the box as the layer is seen`() {
        val p = plan(floatArrayOf(100f, 200f, 300f, 600f), PlanePose(offsetX = 50f, offsetY = -30f, scale = 2f))!!
        assertEquals(-250f, p.rect.x, xy)
        assertEquals(-430f, p.rect.y, xy)
        assertEquals(400f, p.rect.width, wh)
        assertEquals(800f, p.rect.height, wh)
        val pivot = pivotOf(p)
        assertEquals(-50f, pivot[0], 1e-2f)
        assertEquals(-30f, pivot[1], 1e-2f)
        assertEquals(300f, pivot[2], 1e-2f)
    }

    @Test
    fun `a quarter turn swaps the sheet's width and height about the canvas centre`() {
        val p = plan(floatArrayOf(100f, 200f, 300f, 600f), PlanePose(rotationZ = 90f))!!
        assertEquals(700f, p.rect.x, 0.1f)
        assertEquals(400f, p.rect.y, 0.1f)
        assertEquals(400f, p.rect.width, 0.1f)
        assertEquals(200f, p.rect.height, 0.1f)
    }

    @Test
    fun `a turn that is not a quarter gets the axis-aligned box that holds the art, never less`() {
        val box = floatArrayOf(100f, 200f, 300f, 600f)
        val p = plan(box, PlanePose(rotationZ = 30f))!!
        val m = SceneMath.model(PlanePose(rotationZ = 30f), 1000f, 1600f)
        for ((u, v) in listOf(box[0] to box[1], box[2] to box[1], box[0] to box[3], box[2] to box[3])) {
            val q = m.apply(u, v, 0f)
            assertTrue(q[0] >= p.rect.x - 1e-2f && q[0] <= p.rect.x + p.rect.width + 1e-2f)
            assertTrue(q[1] >= p.rect.y - 1e-2f && q[1] <= p.rect.y + p.rect.height + 1e-2f)
        }
        assertTrue(p.rect.width > 200f && p.rect.height > 400f)
    }

    @Test
    fun `tiny art gets the minimum sheet, centred on it`() {
        val p = plan(floatArrayOf(100f, 200f, 110f, 210f))!!
        assertEquals(ArtSheet.MIN_SIZE, p.rect.width, wh)
        assertEquals(ArtSheet.MIN_SIZE, p.rect.height, wh)
        assertEquals(105f, p.rect.x + p.rect.width / 2f, 1e-3f)
        assertEquals(205f, p.rect.y + p.rect.height / 2f, 1e-3f)
        assertEquals(ArtSheet.pivotDepth(16f, 16f), pivotOf(p)[2], 1e-2f)
    }

    @Test
    fun `a pose placed in 3D, a bad box or a bad canvas has no plan`() {
        val box = floatArrayOf(0f, 0f, 10f, 10f)
        assertNull(plan(box, PlanePose(positionZ = 5f)))
        assertNull(plan(box, PlanePose(rotationX = 10f)))
        assertNull(plan(floatArrayOf(0f, 0f, Float.NaN, 10f)))
        assertNull(plan(floatArrayOf(0f, 0f, 10f)))
        assertNull(ArtSheet.plan(box, PlanePose(), 0f, 100f))
        assertNull(plan(box, PlanePose(scale = 0f)))
    }

    @Test
    fun `the art box goes from bitmap pixels through the frame's fit to canvas units`() {
        // A 100 x 160 bitmap in a 1000 x 1600 canvas fits at 10x with no margin.
        val b = ArtSheet.boxOf(ArtBounds(10, 20, 30, 60), LayerFrame(100, 160), 1000f, 1600f)!!
        assertEquals(listOf(100f, 200f, 300f, 600f), b.toList())
        // A wider frame fits by its width and is centred: 200 x 100 in 1000 x 1600 is 5x, 250 px of margin above.
        val c = ArtSheet.boxOf(ArtBounds(0, 0, 200, 100), LayerFrame(200, 100), 1000f, 1600f)!!
        assertEquals(listOf(0f, 550f, 1000f, 1050f), c.toList())
        // A grown bitmap: its pixel (0, 0) sits at the frame's origin (-10, -10).
        val d = ArtSheet.boxOf(ArtBounds(0, 0, 20, 20), LayerFrame(100, 160, -10, -10), 1000f, 1600f)!!
        assertEquals(listOf(-100f, -100f, 100f, 100f), d.toList())
        // Art at the bitmap's corner and edge.
        val e = ArtSheet.boxOf(ArtBounds(99, 159, 100, 160), LayerFrame(100, 160), 1000f, 1600f)!!
        assertEquals(listOf(990f, 1590f, 1000f, 1600f), e.toList())
        assertNull(ArtSheet.boxOf(ArtBounds(5, 5, 5, 9), LayerFrame(100, 160), 1000f, 1600f))
        assertNull(ArtSheet.boxOf(ArtBounds(0, 0, 1, 1), LayerFrame(0, 160), 1000f, 1600f))
    }

    @Test
    fun `the sheet as a group surface covers the art box within the edge pad, and the sweep limit does not cut it`() {
        val p = plan(floatArrayOf(100f, 200f, 300f, 600f))!!
        val profile = DefaultSurface.profile(p.rect, 1000f, 1600f, emptyList())!!
        assertEquals(200f, profile.length, wh)
        assertEquals(400f, profile.sweepLimit!!, wh)
        assertEquals(100f, profile.point(0)[0], xy)
        assertEquals(200f, profile.point(0)[1], xy)
        assertEquals(0f, profile.point(0)[2], 0f)
    }

    @Test
    fun `the layer shifted by the sheet's corner stays exactly where it was seen`() {
        val box = floatArrayOf(100f, 200f, 300f, 600f)
        val pose = PlanePose(offsetX = 50f, offsetY = -30f, scale = 2f, rotationZ = 30f)
        val p = plan(box, pose)!!
        val profile = DefaultSurface.profile(p.rect, 1000f, 1600f, emptyList())!!
        val moved = pose.copy(offsetX = pose.offsetX - p.rect.x, offsetY = pose.offsetY - p.rect.y)
        val group = Layer(id = "g", name = "g", type = LayerType.GROUP, surface = profile)
        val leaf = Layer(
            id = "l", name = "l", parentId = "g",
            offset = androidx.compose.ui.geometry.Offset(moved.offsetX, moved.offsetY),
            scale = moved.scale, rotationZ = moved.rotationZ,
        )
        val layers = listOf(group, leaf)
        val owner = SurfaceMath.ownerOf(layers.associateBy { it.id }, "l")!!
        val onSheet = SurfaceMath.flatSurfaceModel(owner, 1000f, 1600f)!!
        val before = SceneMath.model(pose, 1000f, 1600f)
        for ((u, v) in listOf(box[0] to box[1], box[2] to box[1], box[0] to box[3], box[2] to box[3])) {
            val a = before.apply(u, v, 0f)
            val b = onSheet.apply(u, v, 0f)
            assertEquals(a[0], b[0], 5e-2f)
            assertEquals(a[1], b[1], 5e-2f)
            assertEquals(0f, b[2], 5e-2f)
            // And it lies inside the sheet's own unrolled rectangle: nothing cropped.
            val un = SurfaceMath.boxToUnrolled(owner.innerChain, 1000f, 1600f).apply(u, v, 0f)
            assertTrue(un[0] >= -5e-2f && un[0] <= profile.length + 5e-2f)
            assertTrue(un[1] >= -5e-2f && un[1] <= profile.sweepLimit!! + 5e-2f)
        }
    }

    @Test
    fun `the derived pivot is the sheet's centre, n behind it, for several widths and heights`() {
        for ((w, h) in listOf(200f to 400f, 100f to 100f, 640f to 90f, 16f to 1000f, 1000f to 1600f)) {
            val p = plan(floatArrayOf(100f, 200f, 100f + w, 200f + h))!!
            val pivot = pivotOf(p)
            assertEquals(100f + w / 2f, pivot[0], 1e-2f)
            assertEquals(200f + h / 2f, pivot[1], 1e-2f)
            assertEquals(ArtSheet.pivotDepth(w, h), pivot[2], 1e-2f)
        }
    }

    @Test
    fun `the pivot follows the layers, two sheets give the centre of the box that holds both`() {
        val a = plan(floatArrayOf(0f, 0f, 200f, 400f))!!
        val b = plan(floatArrayOf(600f, 0f, 800f, 400f))!!
        val layers = listOf(
            sheetGroup("a", a),
            sheetGroup("b", b),
        )
        val c = OrbitPivot.resolve(layers, 1000f, 1600f)
        assertEquals(400f, c[0], 0.05f)
        assertEquals(200f, c[1], 0.05f)
        // Both slabs reach 2n = 300 behind their faces: the box's centre is 150 behind.
        assertEquals(150f, c[2], 0.05f)
    }

    @Test
    fun `the art is never past the sheet's edges, with no tolerance, at any pose and canvas size`() {
        val poses = listOf(
            PlanePose(), PlanePose(offsetX = 123.456f, offsetY = -78.9f, scale = 1.37f),
            PlanePose(rotationZ = 17f, scale = 0.61f), PlanePose(rotationZ = 90f, offsetX = 5.5f),
            PlanePose(offsetX = -2048.25f, offsetY = 1999.75f, scale = 3.1f, rotationZ = 211f),
        )
        for ((cw, ch) in listOf(1000f to 1600f, 4096f to 3072f, 2160f to 3840f)) {
            for (pose in poses) {
                val box = floatArrayOf(cw * 0.113f, ch * 0.207f, cw * 0.871f, ch * 0.934f)
                val p = ArtSheet.plan(box, pose, cw, ch)!!
                val profile = DefaultSurface.profile(p.rect, cw, ch, emptyList())!!
                val moved = pose.copy(offsetX = pose.offsetX - p.rect.x, offsetY = pose.offsetY - p.rect.y)
                val m = SceneMath.model(moved, cw, ch)
                for ((u, v) in listOf(box[0] to box[1], box[2] to box[1], box[0] to box[3], box[2] to box[3])) {
                    val q = m.apply(u, v, 0f)
                    assertTrue("x ${q[0]} in 0..${profile.length}", q[0] >= 0f && q[0] <= profile.length)
                    assertTrue("y ${q[1]} in 0..${profile.sweepLimit}", q[1] >= 0f && q[1] <= profile.sweepLimit!!)
                }
            }
        }
    }
}
