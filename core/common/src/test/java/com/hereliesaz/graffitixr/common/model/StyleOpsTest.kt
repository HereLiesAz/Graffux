package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared styles: what a token owns, and what happens when one is edited or deleted. */
class StyleOpsTest {

    private val red = ColorStyle(id = "S1", name = "Brand Red", argb = 0xFFFF0000L)
    private val heading = TextStyle(id = "T1", name = "Heading", fontSizeDp = 96f, isBold = true)

    private fun shapeLayer(id: String, fillStyle: String? = null, strokeStyle: String? = null) = Layer(
        id = id,
        name = id,
        shapes = listOf(
            VectorShape(
                kind = ShapeKind.RECTANGLE,
                fillArgb = 0xFF000000L,
                strokeArgb = 0xFF000000L,
                fillStyleId = fillStyle,
                strokeStyleId = strokeStyle,
            ),
        ),
    )

    private fun textLayer(id: String, styleId: String? = null) = Layer(
        id = id,
        name = id,
        textParams = TextLayerParams(text = "hello", fontSizeDp = 10f, styleId = styleId),
    )

    // ── Resolution ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a linked fill takes the token's colour`() {
        val out = StyleOps.resolve(listOf(shapeLayer("a", fillStyle = "S1")), listOf(red), emptyList())
        assertEquals(0xFFFF0000L, out.single().shapes.single().fillArgb)
    }

    @Test
    fun `fill and stroke link independently`() {
        val blue = ColorStyle(id = "S2", name = "Blue", argb = 0xFF0000FFL)
        val out = StyleOps.resolve(
            listOf(shapeLayer("a", fillStyle = "S1", strokeStyle = "S2")),
            listOf(red, blue),
            emptyList(),
        )
        assertEquals(0xFFFF0000L, out.single().shapes.single().fillArgb)
        assertEquals(0xFF0000FFL, out.single().shapes.single().strokeArgb)
    }

    @Test
    fun `an unlinked shape keeps its own colour`() {
        val out = StyleOps.resolve(listOf(shapeLayer("a")), listOf(red), emptyList())
        assertEquals(0xFF000000L, out.single().shapes.single().fillArgb)
    }

    @Test
    fun `editing a token updates every shape using it`() {
        val layers = listOf(shapeLayer("a", fillStyle = "S1"), shapeLayer("b", fillStyle = "S1"), shapeLayer("c"))
        val recoloured = red.copy(argb = 0xFF00FF00L)
        val out = StyleOps.resolve(layers, listOf(recoloured), emptyList())
        assertEquals(0xFF00FF00L, out[0].shapes.single().fillArgb)
        assertEquals(0xFF00FF00L, out[1].shapes.single().fillArgb)
        // ...and leaves the unlinked one alone.
        assertEquals(0xFF000000L, out[2].shapes.single().fillArgb)
    }

    @Test
    fun `a text style carries typography but never the text itself`() {
        val out = StyleOps.resolve(listOf(textLayer("t", styleId = "T1")), emptyList(), listOf(heading))
        val params = out.single().textParams!!
        assertEquals(96f, params.fontSizeDp, 0f)
        assertEquals(true, params.isBold)
        // Content is per-layer; a style must never overwrite what the layer says.
        assertEquals("hello", params.text)
    }

    @Test
    fun `resolving is idempotent`() {
        val once = StyleOps.resolve(listOf(shapeLayer("a", fillStyle = "S1")), listOf(red), emptyList())
        assertEquals(once, StyleOps.resolve(once, listOf(red), emptyList()))
    }

    @Test
    fun `a document with no styles is returned unchanged`() {
        val layers = listOf(shapeLayer("a"))
        assertTrue(layers === StyleOps.resolve(layers, emptyList(), emptyList()))
    }

    @Test
    fun `a reference to a missing token keeps the last resolved value`() {
        // Blanking here would turn artwork black on a dangling id — far worse than leaving it be.
        val layers = listOf(shapeLayer("a", fillStyle = "GONE"))
        val out = StyleOps.resolve(layers, listOf(red), emptyList())
        assertEquals(0xFF000000L, out.single().shapes.single().fillArgb)
    }

    // ── Linking ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `linking and unlinking a fill`() {
        val linked = StyleOps.setShapeFillStyle(listOf(shapeLayer("a")), "a", "S1")
        assertEquals("S1", linked.single().shapes.single().fillStyleId)
        val unlinked = StyleOps.setShapeFillStyle(linked, "a", null)
        assertNull(unlinked.single().shapes.single().fillStyleId)
    }

    @Test
    fun `linking text on a layer with no text params is a no-op`() {
        val layers = listOf(shapeLayer("a"))
        assertEquals(layers, StyleOps.setTextStyle(layers, "a", "T1"))
    }

    @Test
    fun `linking only touches the addressed layer`() {
        val out = StyleOps.setShapeFillStyle(listOf(shapeLayer("a"), shapeLayer("b")), "b", "S1")
        assertNull(out[0].shapes.single().fillStyleId)
        assertEquals("S1", out[1].shapes.single().fillStyleId)
    }

    // ── Deleting ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `deleting a colour token unlinks users but does not change how they look`() {
        val resolved = StyleOps.resolve(listOf(shapeLayer("a", fillStyle = "S1")), listOf(red), emptyList())
        val (layers, styles) = StyleOps.deleteColorStyle(resolved, listOf(red), "S1")
        assertTrue(styles.isEmpty())
        assertNull(layers.single().shapes.single().fillStyleId)
        // The colour it last resolved to is retained — deletion stops propagation, it doesn't repaint.
        assertEquals(0xFFFF0000L, layers.single().shapes.single().fillArgb)
    }

    @Test
    fun `deleting a token leaves other tokens' links intact`() {
        val blue = ColorStyle(id = "S2", name = "Blue", argb = 0xFF0000FFL)
        val layers = listOf(shapeLayer("a", fillStyle = "S1"), shapeLayer("b", fillStyle = "S2"))
        val (out, styles) = StyleOps.deleteColorStyle(layers, listOf(red, blue), "S1")
        assertNull(out[0].shapes.single().fillStyleId)
        assertEquals("S2", out[1].shapes.single().fillStyleId)
        assertEquals(listOf("S2"), styles.map { it.id })
    }

    @Test
    fun `deleting a text token unlinks its users`() {
        val resolved = StyleOps.resolve(listOf(textLayer("t", styleId = "T1")), emptyList(), listOf(heading))
        val (layers, styles) = StyleOps.deleteTextStyle(resolved, listOf(heading), "T1")
        assertTrue(styles.isEmpty())
        assertNull(layers.single().textParams!!.styleId)
        assertEquals(96f, layers.single().textParams!!.fontSizeDp, 0f)
    }

    // ── Creating from a selection ────────────────────────────────────────────────────────────

    @Test
    fun `a token can be lifted from an existing shape or text layer`() {
        val shape = VectorShape(kind = ShapeKind.ELLIPSE, fillArgb = 0xFF123456L)
        assertEquals(0xFF123456L, StyleOps.colorStyleFromShape(shape, "Lifted").argb)

        val params = TextLayerParams(text = "x", fontName = "Inter", fontSizeDp = 42f, isItalic = true)
        val style = StyleOps.textStyleFromParams(params, "Lifted")
        assertEquals("Inter", style.fontName)
        assertEquals(42f, style.fontSizeDp, 0f)
        assertEquals(true, style.isItalic)
    }
}
