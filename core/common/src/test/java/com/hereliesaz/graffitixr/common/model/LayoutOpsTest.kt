package com.hereliesaz.graffitixr.common.model

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Constraints and auto-layout: where a child ends up when its frame changes. */
class LayoutOpsTest {

    private fun frame(id: String, layout: AutoLayout = AutoLayout()) =
        Layer(id = id, name = id, type = LayerType.GROUP, autoLayout = layout)

    /** A child sized via its shape, so declaredSize has something real to measure. */
    private fun child(
        id: String,
        parent: String,
        x: Float,
        y: Float,
        w: Float = 100f,
        h: Float = 50f,
        constraints: Constraints = Constraints(),
        visible: Boolean = true,
    ) = Layer(
        id = id,
        name = id,
        parentId = parent,
        offset = Offset(x, y),
        isVisible = visible,
        constraints = constraints,
        shapes = listOf(VectorShape(kind = ShapeKind.RECTANGLE, width = w, height = h)),
    )

    private val old = Rect(0f, 0f, 400f, 200f)
    private val wider = Rect(0f, 0f, 800f, 200f)
    private val taller = Rect(0f, 0f, 400f, 400f)

    private fun resize(layers: List<Layer>, from: Rect = old, to: Rect = wider) =
        LayoutOps.applyResize(layers, "F", from, to)

    /**
     * A constrained child described by its box's top-left corner, the way a designer reads it.
     * Constraint geometry works on a layer's CENTRE (its offset — shapes are drawn centred on it),
     * so this converts.
     */
    private fun boxed(
        id: String,
        left: Float,
        top: Float,
        size: Pair<Float, Float> = 100f to 50f,
        constraints: Constraints = Constraints(),
    ) = child(
        id, "F",
        x = left + size.first / 2f, y = top + size.second / 2f,
        w = size.first, h = size.second, constraints = constraints,
    )

    private fun List<Layer>.byId(id: String) = first { it.id == id }
    private val Layer.left get() = offset.x - shapes.first().width * scale / 2f
    private val Layer.top get() = offset.y - shapes.first().height * scale / 2f
    private val Layer.w get() = shapes.first().width * scale
    private val Layer.h get() = shapes.first().height * scale

    private fun h(anchor: ConstraintAnchor) = Constraints(horizontal = anchor)
    private fun v(anchor: ConstraintAnchor) = Constraints(vertical = anchor)

    // ── Constraints: horizontal axis (400 → 800 wide) ────────────────────────────────────────

    @Test
    fun `START keeps the child's distance from the left edge`() {
        val a = resize(
            listOf(frame("F"), boxed("a", left = 20f, top = 0f))).byId("a")
        assertEquals(20f, a.left, 0.01f)
        assertEquals(100f, a.w, 0.01f)
    }

    @Test
    fun `END keeps the child's distance from the right edge`() {
        // 400-wide frame, child at x=280 with width 100 → 20px gap on the right. After widening to
        // 800 the child must move to keep that 20px gap.
        val a = resize(
            listOf(frame("F"), boxed("a", left = 280f, top = 0f, constraints = h(ConstraintAnchor.END)))).byId("a")
        assertEquals(680f, a.left, 0.01f)
        assertEquals(100f, a.w, 0.01f)
    }

    @Test
    fun `CENTER keeps the child's offset from the frame's centre`() {
        val a = resize(
            listOf(frame("F"), boxed("a", left = 150f, top = 0f, constraints = h(ConstraintAnchor.CENTER)))).byId("a")
        assertEquals(350f, a.left, 0.01f)
        assertEquals(100f, a.w, 0.01f)
    }

    @Test
    fun `STRETCH holds both edges so the child's width grows with the frame`() {
        val a = resize(
            listOf(
            frame("F"),
            boxed("a", left = 20f, top = 0f, size = 360f to 50f,
                constraints = h(ConstraintAnchor.STRETCH)),
            ),
        ).byId("a")
        assertEquals(20f, a.left, 0.01f)
        assertEquals(760f, a.w, 0.01f)
        // The size lands on the shape, not on the layer's uniform scale.
        assertEquals(1f, a.scale, 0.0001f)
        assertEquals(50f, a.h, 0.01f)
    }

    @Test
    fun `SCALE moves and sizes the child proportionally on the horizontal axis`() {
        val a = resize(
            listOf(frame("F"), boxed("a", left = 100f, top = 0f, constraints = h(ConstraintAnchor.SCALE)))).byId("a")
        // Frame doubled, so a child at 1/4 across stays at 1/4 across, twice as wide.
        assertEquals(200f, a.left, 0.01f)
        assertEquals(200f, a.w, 0.01f)
        // Only the horizontal axis scales; the vertical is START and keeps its size.
        assertEquals(50f, a.h, 0.01f)
        assertEquals(1f, a.scale, 0.0001f)
    }

    // ── Constraints: vertical axis (200 → 400 tall) ──────────────────────────────────────────

    @Test
    fun `vertical START keeps the distance from the top`() {
        val a = resize(
            listOf(frame("F"), boxed("a", left = 0f, top = 10f)), to = taller).byId("a")
        assertEquals(10f, a.top, 0.01f)
        assertEquals(50f, a.h, 0.01f)
    }

    @Test
    fun `vertical END keeps the distance from the bottom`() {
        val a = resize(
            listOf(frame("F"), boxed("a", left = 10f, top = 130f, constraints = v(ConstraintAnchor.END))),
            to = taller,
        ).byId("a")
        assertEquals(10f, a.left, 0.01f) // unchanged: START, and width didn't change
        assertEquals(330f, a.top, 0.01f) // 20px from the bottom, preserved
        assertEquals(50f, a.h, 0.01f)
    }

    @Test
    fun `vertical CENTER keeps the offset from the frame's middle`() {
        val a = resize(
            listOf(frame("F"), boxed("a", left = 0f, top = 75f, constraints = v(ConstraintAnchor.CENTER))),
            to = taller,
        ).byId("a")
        assertEquals(175f, a.top, 0.01f)
        assertEquals(50f, a.h, 0.01f)
    }

    @Test
    fun `vertical STRETCH grows the child's height`() {
        val a = resize(
            listOf(
            frame("F"),
            boxed("a", left = 0f, top = 10f, size = 100f to 180f,
                constraints = v(ConstraintAnchor.STRETCH)),
            ),
            to = taller,
        ).byId("a")
        assertEquals(10f, a.top, 0.01f)
        assertEquals(380f, a.h, 0.01f)
        assertEquals(100f, a.w, 0.01f)
    }

    @Test
    fun `vertical SCALE is applied, independently of the horizontal axis`() {
        val a = resize(
            listOf(frame("F"), boxed("a", left = 0f, top = 50f, constraints = v(ConstraintAnchor.SCALE))),
            to = taller,
        ).byId("a")
        assertEquals(100f, a.top, 0.01f)
        assertEquals(100f, a.h, 0.01f)
        assertEquals(100f, a.w, 0.01f)
    }

    @Test
    fun `both axes resize independently in one pass`() {
        val bigger = Rect(0f, 0f, 800f, 400f)
        val a = resize(
            listOf(
                frame("F"),
                boxed(
                    "a", left = 20f, top = 50f, size = 360f to 50f,
                    constraints = Constraints(horizontal = ConstraintAnchor.STRETCH, vertical = ConstraintAnchor.SCALE),
                ),
            ),
            to = bigger,
        ).byId("a")
        assertEquals(20f, a.left, 0.01f)
        assertEquals(760f, a.w, 0.01f)
        assertEquals(100f, a.top, 0.01f)
        assertEquals(100f, a.h, 0.01f)
    }

    @Test
    fun `a scaled path scales its points and handles, not just its box`() {
        val path = VectorShape(
            kind = ShapeKind.PATH, width = 100f, height = 50f,
            points = listOf(-50f, -25f, 50f, 25f), handlesOut = listOf(10f, 10f, 0f, 0f),
        )
        val shape = resize(
            listOf(
                frame("F"),
                boxed("a", left = 0f, top = 0f, constraints = h(ConstraintAnchor.SCALE)).copy(shapes = listOf(path)),
            ),
        ).byId("a").shapes.first()
        assertEquals(listOf(-100f, -25f, 100f, 25f), shape.points)
        assertEquals(listOf(20f, 10f, 0f, 0f), shape.handlesOut)
    }

    // ── Constraints: layers without a shape ──────────────────────────────────────────────────

    @Test
    fun `a raster layer SCALEs through its uniform scale`() {
        // No shape and no declared size: the only size field a raster layer has is `scale`.
        val raster = Layer(
            id = "r", name = "r", parentId = "F", offset = Offset(100f, 100f),
            constraints = h(ConstraintAnchor.SCALE),
        )
        val r = resize(listOf(frame("F"), raster)).byId("r")
        assertEquals(2f, r.scale, 0.001f)
        assertEquals(200f, r.offset.x, 0.01f)
    }

    @Test
    fun `a raster layer with no measurable size is only moved by STRETCH`() {
        val raster = Layer(
            id = "r", name = "r", parentId = "F", offset = Offset(100f, 100f),
            constraints = h(ConstraintAnchor.STRETCH),
        )
        val r = resize(listOf(frame("F"), raster)).byId("r")
        assertEquals(1f, r.scale, 0.001f)
        assertEquals(100f, r.offset.x, 0.01f)
    }

    @Test
    fun `a raster layer scaled on both axes keeps its aspect and takes the smaller factor`() {
        val raster = Layer(
            id = "r", name = "r", parentId = "F", offset = Offset(200f, 100f),
            constraints = Constraints(ConstraintAnchor.SCALE, ConstraintAnchor.SCALE),
        )
        val r = resize(listOf(frame("F"), raster), to = Rect(0f, 0f, 800f, 300f)).byId("r")
        // x doubles, y grows 1.5x — a single scale can only honour one, so it takes the smaller.
        assertEquals(1.5f, r.scale, 0.001f)
    }

    @Test
    fun `a child with a declared layout size stretches that size`() {
        val sized = Layer(
            id = "s", name = "s", parentId = "F", offset = Offset(200f, 100f),
            layoutWidth = 360f, layoutHeight = 50f, constraints = h(ConstraintAnchor.STRETCH),
        )
        val s = resize(listOf(frame("F"), sized)).byId("s")
        assertEquals(760f, s.layoutWidth, 0.01f)
        assertEquals(50f, s.layoutHeight, 0.01f)
        assertEquals(1f, s.scale, 0.0001f)
    }

    // ── Constraints: nested frames ───────────────────────────────────────────────────────────

    @Test
    fun `a stretched child frame resizes its own layout box and passes the resize down`() {
        // F (400x200) holds G, a 200x100 frame centred at x=200 that stretches horizontally. G holds
        // g1, pinned to G's right edge. Widening F to 800 widens G to 600, and g1 must follow G's
        // right edge in G's own (centred) space.
        val g = Layer(
            id = "G", name = "G", type = LayerType.GROUP, parentId = "F",
            offset = Offset(200f, 100f), layoutWidth = 200f, layoutHeight = 100f,
            constraints = h(ConstraintAnchor.STRETCH),
        )
        // G's local box is -100..100; g1 is 40 wide with a 10px gap on the right → centre 70.
        val g1 = child("g1", "G", x = 70f, y = 0f, w = 40f, h = 20f, constraints = h(ConstraintAnchor.END))
        val out = resize(listOf(frame("F"), g, g1))
        val newG = out.byId("G")
        assertEquals(600f, newG.layoutWidth, 0.01f)
        assertEquals(100f, newG.layoutHeight, 0.01f)
        assertEquals(400f, newG.offset.x, 0.01f)
        // G's local box is now -300..300; 10px gap + 20 half-width → centre 270.
        assertEquals(270f, out.byId("g1").offset.x, 0.01f)
    }

    @Test
    fun `a nested frame whose box did not change leaves its children alone`() {
        val g = Layer(
            id = "G", name = "G", type = LayerType.GROUP, parentId = "F",
            offset = Offset(100f, 100f), layoutWidth = 200f, layoutHeight = 100f,
        )
        val g1 = child("g1", "G", x = 70f, y = 0f, constraints = h(ConstraintAnchor.END))
        val out = resize(listOf(frame("F"), g, g1))
        assertEquals(70f, out.byId("g1").offset.x, 0.01f)
    }

    @Test
    fun `a nested auto-layout frame is re-laid out rather than constrained`() {
        val row = AutoLayout(direction = LayoutDirection.HORIZONTAL, gap = 10f)
        val g = Layer(
            id = "G", name = "G", type = LayerType.GROUP, parentId = "F", autoLayout = row,
            offset = Offset(200f, 100f), layoutWidth = 200f, layoutHeight = 100f,
            constraints = h(ConstraintAnchor.STRETCH),
        )
        val g1 = child("g1", "G", x = 999f, y = 999f, constraints = h(ConstraintAnchor.END))
        val out = resize(listOf(frame("F"), g, g1))
        val newG = out.byId("G")
        // Laid out from G's (moved) origin, not constrained from g1's previous position.
        assertEquals(newG.offset.x, out.byId("g1").offset.x, 0.01f)
    }

    @Test
    fun `local frame rect is centred on the frame origin`() {
        assertEquals(Rect(-200f, -100f, 400f, 200f), LayoutOps.localFrameRect(400f, 200f))
    }

    @Test
    fun `layers outside the frame are untouched`() {
        val stranger = Layer(id = "z", name = "z", offset = Offset(5f, 5f))
        val out = resize(listOf(frame("F"), child("a", "F", 20f, 0f), stranger))
        assertEquals(Offset(5f, 5f), out.first { it.id == "z" }.offset)
    }

    @Test
    fun `a degenerate old rect is a no-op rather than a divide by zero`() {
        val layers = listOf(frame("F"), child("a", "F", 20f, 0f))
        assertEquals(layers, LayoutOps.applyResize(layers, "F", Rect(0f, 0f, 0f, 0f), wider))
    }

    @Test
    fun `resizing an unknown frame is a no-op`() {
        val layers = listOf(frame("F"), child("a", "F", 20f, 0f))
        assertEquals(layers, LayoutOps.applyResize(layers, "nope", old, wider))
    }

    // ── Auto-layout ──────────────────────────────────────────────────────────────────────────

    private val row = AutoLayout(direction = LayoutDirection.HORIZONTAL, gap = 10f)

    @Test
    fun `a horizontal layout stacks children left to right with the gap between them`() {
        val out = LayoutOps.applyAutoLayout(
            listOf(frame("F", row), child("a", "F", 0f, 0f, w = 100f), child("b", "F", 0f, 0f, w = 60f)),
            "F",
            Rect(0f, 0f, 400f, 200f),
        )
        assertEquals(0f, out.first { it.id == "a" }.offset.x, 0.01f)
        assertEquals(110f, out.first { it.id == "b" }.offset.x, 0.01f)
    }

    @Test
    fun `a vertical layout stacks top to bottom`() {
        val col = AutoLayout(direction = LayoutDirection.VERTICAL, gap = 8f)
        val out = LayoutOps.applyAutoLayout(
            listOf(frame("F", col), child("a", "F", 0f, 0f, h = 50f), child("b", "F", 0f, 0f, h = 30f)),
            "F",
            Rect(0f, 0f, 400f, 200f),
        )
        assertEquals(0f, out.first { it.id == "a" }.offset.y, 0.01f)
        assertEquals(58f, out.first { it.id == "b" }.offset.y, 0.01f)
    }

    @Test
    fun `padding offsets the whole stack`() {
        val padded = row.copy(paddingStart = 25f, paddingTop = 15f)
        val out = LayoutOps.applyAutoLayout(
            listOf(frame("F", padded), child("a", "F", 0f, 0f)),
            "F",
            Rect(0f, 0f, 400f, 200f),
        )
        assertEquals(25f, out.first { it.id == "a" }.offset.x, 0.01f)
        assertEquals(15f, out.first { it.id == "a" }.offset.y, 0.01f)
    }

    @Test
    fun `cross-axis alignment centres and end-aligns`() {
        val centred = row.copy(align = LayoutAlign.CENTER)
        val out = LayoutOps.applyAutoLayout(
            listOf(frame("F", centred), child("a", "F", 0f, 0f, h = 50f)),
            "F",
            Rect(0f, 0f, 400f, 200f),
        )
        // (200 - 50) / 2
        assertEquals(75f, out.first { it.id == "a" }.offset.y, 0.01f)

        val ended = row.copy(align = LayoutAlign.END)
        val out2 = LayoutOps.applyAutoLayout(
            listOf(frame("F", ended), child("a", "F", 0f, 0f, h = 50f)),
            "F",
            Rect(0f, 0f, 400f, 200f),
        )
        assertEquals(150f, out2.first { it.id == "a" }.offset.y, 0.01f)
    }

    @Test
    fun `a hidden child takes no space so the stack collapses`() {
        // The behaviour that makes auto-layout worth having: hiding an item closes the gap rather
        // than leaving a hole where it was.
        val out = LayoutOps.applyAutoLayout(
            listOf(
                frame("F", row),
                child("a", "F", 0f, 0f, w = 100f),
                child("hidden", "F", 0f, 0f, w = 100f, visible = false),
                child("b", "F", 0f, 0f, w = 60f),
            ),
            "F",
            Rect(0f, 0f, 400f, 200f),
        )
        assertEquals(110f, out.first { it.id == "b" }.offset.x, 0.01f)
    }

    @Test
    fun `auto-layout takes precedence over constraints on resize`() {
        // Both would answer "where does this child go"; letting each apply would give two answers.
        val out = LayoutOps.applyResize(
            listOf(
                frame("F", row),
                child("a", "F", x = 999f, y = 999f, constraints = Constraints(horizontal = ConstraintAnchor.END)),
            ),
            "F", old, wider,
        )
        // Laid out from the frame origin, not constrained from its previous position.
        assertEquals(0f, out.first { it.id == "a" }.offset.x, 0.01f)
    }

    @Test
    fun `a frame with no auto-layout leaves its children where they are`() {
        val layers = listOf(frame("F"), child("a", "F", 33f, 44f))
        assertEquals(layers, LayoutOps.applyAutoLayout(layers, "F", Rect(0f, 0f, 400f, 200f)))
    }

    @Test
    fun `an empty frame lays out to nothing rather than crashing`() {
        val layers = listOf(frame("F", row))
        assertEquals(layers, LayoutOps.applyAutoLayout(layers, "F", Rect(0f, 0f, 400f, 200f)))
    }

    // ── Hug contents ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `hug contents measures the stack plus gaps and padding`() {
        val padded = row.copy(paddingStart = 10f, paddingEnd = 10f, paddingTop = 5f, paddingBottom = 5f)
        val (w, h) = LayoutOps.hugContentsSize(
            listOf(frame("F", padded), child("a", "F", 0f, 0f, w = 100f, h = 50f), child("b", "F", 0f, 0f, w = 60f, h = 30f)),
            "F",
        )!!
        // 100 + 10 gap + 60 + 20 padding
        assertEquals(190f, w, 0.01f)
        // tallest child + vertical padding
        assertEquals(60f, h, 0.01f)
    }

    @Test
    fun `hug contents is null without auto-layout or without children`() {
        assertNull(LayoutOps.hugContentsSize(listOf(frame("F"), child("a", "F", 0f, 0f)), "F"))
        assertNull(LayoutOps.hugContentsSize(listOf(frame("F", row)), "F"))
        assertNull(LayoutOps.hugContentsSize(emptyList(), "F"))
    }
}
