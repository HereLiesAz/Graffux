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

    private fun resize(layers: List<Layer>, from: Rect = old, to: Rect = wider) =
        LayoutOps.applyResize(layers, "F", from, to)

    // ── Constraints ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `START keeps the child's distance from the left edge`() {
        val out = resize(listOf(frame("F"), child("a", "F", x = 20f, y = 0f)))
        assertEquals(20f, out.first { it.id == "a" }.offset.x, 0.01f)
    }

    @Test
    fun `END keeps the child's distance from the right edge`() {
        // 400-wide frame, child at x=280 with width 100 → 20px gap on the right. After widening to
        // 800 the child must move to keep that 20px gap.
        val out = resize(
            listOf(
                frame("F"),
                child("a", "F", x = 280f, y = 0f, constraints = Constraints(horizontal = ConstraintAnchor.END)),
            ),
        )
        assertEquals(680f, out.first { it.id == "a" }.offset.x, 0.01f)
    }

    @Test
    fun `CENTER keeps the child's offset from the frame's centre`() {
        // Child centred in the 400-wide frame stays centred in the 800-wide one.
        val out = resize(
            listOf(
                frame("F"),
                child("a", "F", x = 150f, y = 0f, constraints = Constraints(horizontal = ConstraintAnchor.CENTER)),
            ),
        )
        assertEquals(350f, out.first { it.id == "a" }.offset.x, 0.01f)
    }

    @Test
    fun `STRETCH holds both edges so the child grows with the frame`() {
        val out = resize(
            listOf(
                frame("F"),
                child("a", "F", x = 20f, y = 0f, w = 360f, constraints = Constraints(horizontal = ConstraintAnchor.STRETCH)),
            ),
        )
        // Position keeps the left gap; the growth itself is the width change, which the caller
        // applies to the shape — position must not drift.
        assertEquals(20f, out.first { it.id == "a" }.offset.x, 0.01f)
    }

    @Test
    fun `SCALE moves the child proportionally and scales it`() {
        val out = resize(
            listOf(
                frame("F"),
                child("a", "F", x = 100f, y = 0f, constraints = Constraints(horizontal = ConstraintAnchor.SCALE)),
            ),
        ).first { it.id == "a" }
        // Frame doubled, so a child at 1/4 across stays at 1/4 across.
        assertEquals(200f, out.offset.x, 0.01f)
        assertEquals(2f, out.scale, 0.01f)
    }

    @Test
    fun `the vertical axis is constrained independently of the horizontal`() {
        val taller = Rect(0f, 0f, 400f, 400f)
        val out = LayoutOps.applyResize(
            listOf(
                frame("F"),
                child(
                    "a", "F", x = 10f, y = 130f, w = 100f, h = 50f,
                    constraints = Constraints(horizontal = ConstraintAnchor.START, vertical = ConstraintAnchor.END),
                ),
            ),
            "F", old, taller,
        ).first { it.id == "a" }
        assertEquals(10f, out.offset.x, 0.01f)   // unchanged: START, and width didn't change
        assertEquals(330f, out.offset.y, 0.01f)  // 20px from the bottom, preserved
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
