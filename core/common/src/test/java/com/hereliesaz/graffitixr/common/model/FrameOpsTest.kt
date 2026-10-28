@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.model

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameOpsTest {

    private fun frame(id: String, x: Float, y: Float, w: Float, h: Float) =
        FrameOps.create(id, id, Rect(x, y, w, h))

    private fun close(expected: Float, actual: Float) = assertEquals(expected, actual, 0.01f)

    @Test
    fun `a new frame is centred on its box, filled white and clips`() {
        val f = frame("Pay", 390f, 70f, 190f, 340f)
        assertEquals(LayerType.FRAME, f.type)
        assertEquals(Offset(485f, 240f), f.offset)
        assertEquals(190f, f.layoutWidth)
        assertEquals(340f, f.layoutHeight)
        assertEquals(FrameOps.DEFAULT_FILL, f.frameFillArgb)
        assertTrue(f.clipsContent)
        assertTrue(f.isContainer)
        assertEquals(Rect(390f, 70f, 190f, 340f), FrameOps.boxOf(listOf(f), f))
    }

    @Test
    fun `names count up past the highest used`() {
        assertEquals("Frame 1", FrameOps.nextName(emptyList()))
        val layers = listOf(frame("a", 0f, 0f, 1f, 1f).copy(name = "Frame 4"), Layer(id = "x", name = "Frame 9"))
        // A plain layer called "Frame 9" is not a frame.
        assertEquals("Frame 5", FrameOps.nextName(layers))
    }

    @Test
    fun `an added frame goes right of the rightmost, tops aligned, 80 apart`() {
        val cart = frame("Cart", 150f, 70f, 190f, 340f)
        val pay = frame("Pay", 390f, 60f, 190f, 340f)
        assertEquals(Rect(660f, 60f, 100f, 50f), FrameOps.nextPlacement(listOf(cart, pay), 100f, 50f))
        assertEquals(Rect(-50f, -25f, 100f, 50f), FrameOps.nextPlacement(emptyList(), 100f, 50f))
    }

    @Test
    fun `a child's world transform composes its frame's`() {
        val f = frame("f", -100f, -100f, 200f, 200f).copy(scale = 2f, rotationZ = 90f)
        val child = Layer(id = "c", name = "c", parentId = "f", offset = Offset(10f, 0f), rotationZ = 10f)
        val t = FrameOps.worldTransform(listOf(f, child), child)
        // (10, 0) scaled by 2 and turned 90° is (0, 20), from the frame's centre at the origin.
        close(0f, t.offset.x)
        close(20f, t.offset.y)
        close(2f, t.scale)
        close(100f, t.rotation)
        val flat = FrameOps.flattenTransforms(listOf(f, child)).last()
        close(20f, flat.offset.y)
        assertEquals("f", flat.parentId)
    }

    @Test
    fun `reparenting keeps a layer where it is on screen`() {
        val f = frame("f", 0f, 0f, 200f, 200f) // centre (100, 100)
        val shape = Layer(id = "s", name = "s", offset = Offset(150f, 120f))
        val inFrame = FrameOps.reparent(listOf(f, shape), "s", "f")
        val moved = inFrame.last()
        assertEquals("f", moved.parentId)
        close(50f, moved.offset.x)
        close(20f, moved.offset.y)
        close(150f, FrameOps.worldTransform(inFrame, moved).offset.x)
        val out = FrameOps.reparent(inFrame, "s", null)
        assertNull(out.last().parentId)
        close(150f, out.last().offset.x)
        close(120f, out.last().offset.y)
    }

    @Test
    fun `a frame never moves into itself or its own child`() {
        val outer = frame("o", 0f, 0f, 400f, 400f)
        val inner = frame("i", 10f, 10f, 100f, 100f).copy(parentId = "o")
        val layers = listOf(outer, inner)
        assertEquals(layers, FrameOps.reparent(layers, "o", "i"))
        assertEquals(layers, FrameOps.reparent(layers, "o", "o"))
    }

    @Test
    fun `dropping a layer into a frame adopts it and dragging it out releases it`() {
        val f = frame("f", 0f, 0f, 200f, 200f)
        val inside = Layer(id = "s", name = "s", offset = Offset(50f, 50f))
        val adopted = FrameOps.reparentAfterMove(listOf(f, inside), "s")
        assertEquals("f", adopted.last().parentId)
        // Moved outside the frame's box.
        val dragged = adopted.map { if (it.id == "s") it.copy(offset = Offset(500f, 0f)) else it }
        val released = FrameOps.reparentAfterMove(dragged, "s")
        assertNull(released.last().parentId)
        close(600f, released.last().offset.x)
    }

    @Test
    fun `a layer still inside its frame stays put even over a nested frame`() {
        val outer = frame("o", 0f, 0f, 400f, 400f)
        val other = frame("x", 1000f, 0f, 100f, 100f)
        val child = Layer(id = "c", name = "c", parentId = "o", offset = Offset(0f, 0f))
        val layers = listOf(outer, other, child)
        assertEquals(layers, FrameOps.reparentAfterMove(layers, "c"))
    }

    @Test
    fun `adjustment layers are never adopted`() {
        val f = frame("f", 0f, 0f, 200f, 200f)
        val adj = Layer(
            id = "a", name = "Light", offset = Offset(100f, 100f),
            adjustment = com.hereliesaz.graffitixr.common.photo.PhotoAdjustment(com.hereliesaz.graffitixr.common.photo.PhotoTool.LIGHT),
        )
        assertNull(FrameOps.adoptContents(listOf(f, adj), "f").last().parentId)
        assertNull(FrameOps.reparentAfterMove(listOf(f, adj), "a").last().parentId)
    }

    @Test
    fun `drawing a frame over artwork adopts what is under it`() {
        val f = frame("f", 0f, 0f, 200f, 200f)
        val under = Layer(id = "u", name = "u", offset = Offset(120f, 80f))
        val away = Layer(id = "w", name = "w", offset = Offset(900f, 80f))
        val other = frame("g", 50f, 50f, 20f, 20f)
        val out = FrameOps.adoptContents(listOf(under, away, other, f), "f")
        assertEquals("f", out.first { it.id == "u" }.parentId)
        assertNull(out.first { it.id == "w" }.parentId)
        // Frames are not swallowed by a frame drawn over them.
        assertNull(out.first { it.id == "g" }.parentId)
    }

    @Test
    fun `hit tests use the frame's box and label`() {
        val back = frame("back", 0f, 0f, 300f, 300f)
        val front = frame("front", 100f, 100f, 100f, 100f)
        val layers = listOf(back, front)
        assertEquals("front", FrameOps.frameAt(layers, Offset(150f, 150f))?.id)
        assertEquals("back", FrameOps.frameAt(layers, Offset(20f, 20f))?.id)
        assertEquals("back", FrameOps.frameAt(layers, Offset(150f, 150f), except = "front")?.id)
        assertNull(FrameOps.frameAt(layers, Offset(-5f, 20f)))
        assertEquals("back", FrameOps.labelAt(layers, Offset(10f, -6f), labelHeight = 16f, labelWidth = 80f)?.id)
        assertNull(FrameOps.labelAt(layers, Offset(10f, -30f), labelHeight = 16f, labelWidth = 80f))
        assertFalse(FrameOps.contains(layers, front, Offset(99f, 150f)))
    }

    @Test
    fun `setting a frame's box moves it and runs its children's constraints`() {
        val f = frame("f", 0f, 0f, 200f, 100f)
        // Pinned to the right edge: 10 in from it, centred vertically.
        val pinned = Layer(
            id = "p", name = "p", parentId = "f", offset = Offset(80f, 0f),
            layoutWidth = 20f, layoutHeight = 20f,
            constraints = Constraints(horizontal = ConstraintAnchor.END, vertical = ConstraintAnchor.CENTER),
        )
        val out = FrameOps.setBox(listOf(f, pinned), "f", Rect(100f, 0f, 300f, 100f))
        val resized = out.first { it.id == "f" }
        assertEquals(Offset(250f, 50f), resized.offset)
        assertEquals(300f, resized.layoutWidth)
        // Still 10 in from the (now 150 away) right edge.
        close(130f, out.first { it.id == "p" }.offset.x)
    }

    @Test
    fun `a move inside a turned, scaled frame follows the finger`() {
        val f = frame("f", -50f, -50f, 100f, 100f).copy(scale = 2f, rotationZ = 90f)
        val child = Layer(id = "c", name = "c", parentId = "f", offset = Offset(5f, 5f))
        val before = FrameOps.worldTransform(listOf(f, child), child).offset
        val out = FrameOps.moveBy(listOf(f, child), "c", Offset(10f, 0f))
        val after = FrameOps.worldTransform(out, out.last()).offset
        close(before.x + 10f, after.x)
        close(before.y, after.y)
    }

    @Test
    fun `resizing a vector layer stretches its shapes`() {
        val shape = VectorShape(kind = ShapeKind.RECTANGLE, width = 100f, height = 50f)
        val layer = Layer(id = "v", name = "v", shapes = listOf(shape))
        val out = LayoutOps.resizeLayer(layer, 2f, 3f).shapes.single()
        close(200f, out.width)
        close(150f, out.height)
    }

    @Test
    fun `a turned frame's corners turn with it and a corner drag resizes along its own edges`() {
        // 200 x 100 about the origin, turned a quarter.
        val f = frame("f", -100f, -50f, 200f, 100f).copy(rotationZ = 90f)
        val pts = FrameOps.corners(listOf(f), f)
        // Its top-left (-100, -50) turned 90 degrees lands at (50, -100).
        close(50f, pts[0].x)
        close(-100f, pts[0].y)
        // Drag the bottom-right corner (on screen at (-50, 100)) 20 further along its own x axis,
        // which after the turn points down.
        val box = FrameOps.resizeFromCorner(listOf(f), f, 2, Offset(-50f, 120f))
        close(220f, box.width)
        close(100f, box.height)
        // The top-left corner (50, -100) stays put: the centre moved 10 down.
        close(10f, box.centerY)
        close(0f, box.centerX)
    }

    @Test
    fun `only what shows can be picked`() {
        val clip = frame("clip", 0f, 0f, 100f, 100f)
        val inClip = Layer(id = "a", name = "a", parentId = "clip", offset = Offset(0f, 0f))
        val hiddenParent = frame("hidden", 300f, 0f, 100f, 100f).copy(isVisible = false)
        val inHidden = Layer(id = "b", name = "b", parentId = "hidden")
        val layers = listOf(clip, inClip, hiddenParent, inHidden)
        assertTrue(FrameOps.showsAt(layers, "a", Offset(50f, 50f)))
        // Outside a clipping frame's box: clipped away.
        assertFalse(FrameOps.showsAt(layers, "a", Offset(150f, 50f)))
        // Not clipped once the frame shows all.
        val open = layers.map { if (it.id == "clip") it.copy(clipsContent = false) else it }
        assertTrue(FrameOps.showsAt(open, "a", Offset(150f, 50f)))
        assertFalse(FrameOps.showsAt(layers, "b", Offset(350f, 50f)))
        assertFalse(FrameOps.showsAt(layers.map { if (it.id == "a") it.copy(isVisible = false) else it }, "a", Offset(50f, 50f)))
    }
}
