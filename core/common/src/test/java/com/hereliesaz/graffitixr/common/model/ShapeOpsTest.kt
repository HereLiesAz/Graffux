package com.hereliesaz.graffitixr.common.model

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeOpsTest {

    private val red = 0xFFFF0000L

    private val spec = ShapeOps.Spec("id", "name", red)

    private fun make(mode: ShapeToolMode, a: Offset, b: Offset) = ShapeOps.create(mode, spec, a, b)

    @Test
    fun `a rectangle covers the dragged box whichever way the drag went`() {
        for ((a, b) in listOf(
            Offset(10f, 20f) to Offset(110f, 80f),
            Offset(110f, 80f) to Offset(10f, 20f),
            Offset(110f, 20f) to Offset(10f, 80f),
            Offset(10f, 80f) to Offset(110f, 20f),
        )) {
            val layer = make(ShapeToolMode.RECTANGLE, a, b)!!
            val shape = layer.shapes.single()
            assertEquals(LayerType.VECTOR, layer.type)
            assertEquals(ShapeKind.RECTANGLE, shape.kind)
            assertEquals(100f, shape.width, 0.001f)
            assertEquals(60f, shape.height, 0.001f)
            assertEquals(Offset(60f, 50f), layer.offset)
            assertEquals(0f, layer.rotationZ, 0.001f)
        }
    }

    @Test
    fun `a closed shape is filled with the colour in hand and has no stroke`() {
        for (mode in listOf(ShapeToolMode.RECTANGLE, ShapeToolMode.ROUNDED_RECTANGLE, ShapeToolMode.ELLIPSE)) {
            val shape = make(mode, Offset(0f, 0f), Offset(50f, 50f))!!.shapes.single()
            assertEquals(red, shape.fillArgb)
            assertEquals(0f, shape.strokeWidth, 0f)
            assertTrue(shape.hasFill)
            assertFalse(shape.hasStroke)
        }
    }

    @Test
    fun `an ellipse is an ellipse kind and a rectangle has no radius`() {
        val end = Offset(80f, 40f)
        assertEquals(ShapeKind.ELLIPSE, make(ShapeToolMode.ELLIPSE, Offset.Zero, end)!!.shapes.single().kind)
        assertEquals(0f, make(ShapeToolMode.RECTANGLE, Offset.Zero, end)!!.shapes.single().cornerRadius, 0f)
    }

    @Test
    fun `a rounded rectangle is a rectangle whose radius is a fifth of its shorter side`() {
        val shape = make(ShapeToolMode.ROUNDED_RECTANGLE, Offset.Zero, Offset(200f, 100f))!!.shapes.single()
        assertEquals(ShapeKind.RECTANGLE, shape.kind)
        assertEquals(20f, shape.cornerRadius, 0.001f)
    }

    @Test
    fun `a line runs from touch to lift, stroked with the colour in hand`() {
        val a = Offset(10f, 10f)
        val b = Offset(70f, 90f)
        val layer = make(ShapeToolMode.LINE, a, b)!!
        val shape = layer.shapes.single()
        assertEquals(ShapeKind.LINE, shape.kind)
        assertEquals(100f, shape.width, 0.01f)
        assertEquals(red, shape.strokeArgb)
        assertEquals(ShapeOps.LINE_STROKE_WIDTH, shape.strokeWidth, 0f)
        assertTrue(shape.hasStroke)
        assertFalse(shape.hasFill)
        assertEquals(Offset(40f, 50f), layer.offset)
        // The layer's own transform puts the run's two ends back on the touch and the lift.
        val t = FrameOps.worldTransform(listOf(layer), layer)
        val end1 = t.apply(Offset(shape.width / 2f, 0f))
        val end2 = t.apply(Offset(-shape.width / 2f, 0f))
        assertEquals(b.x, end1.x, 0.01f)
        assertEquals(b.y, end1.y, 0.01f)
        assertEquals(a.x, end2.x, 0.01f)
        assertEquals(a.y, end2.y, 0.01f)
    }

    @Test
    fun `a horizontal line is not turned`() {
        assertEquals(0f, make(ShapeToolMode.LINE, Offset(0f, 5f), Offset(30f, 5f))!!.rotationZ, 0.001f)
    }

    @Test
    fun `a drag too small to be a shape adds nothing`() {
        assertNull(make(ShapeToolMode.RECTANGLE, Offset.Zero, Offset(3f, 100f)))
        assertNull(make(ShapeToolMode.ELLIPSE, Offset.Zero, Offset(100f, 3f)))
        assertNull(make(ShapeToolMode.LINE, Offset.Zero, Offset(2f, 2f)))
    }

    @Test
    fun `the frame and the modes not yet offered draw no shape`() {
        for (mode in listOf(ShapeToolMode.FRAME, ShapeToolMode.POLYGON, ShapeToolMode.STAR)) {
            assertNull(make(mode, Offset.Zero, Offset(100f, 100f)))
            assertNull(ShapeOps.createCentred(mode, spec, Offset.Zero))
        }
    }

    @Test
    fun `the Add button's shape is the default size, centred`() {
        val rect = ShapeOps.createCentred(ShapeToolMode.RECTANGLE, spec, Offset(30f, -40f))!!
        assertEquals(Offset(30f, -40f), rect.offset)
        assertEquals(ShapeOps.ADD_SIZE, rect.shapes.single().width, 0f)
        assertEquals(ShapeOps.ADD_SIZE, rect.shapes.single().height, 0f)
        val line = ShapeOps.createCentred(ShapeToolMode.LINE, spec, Offset(30f, -40f))!!
        assertEquals(Offset(30f, -40f), line.offset)
        assertEquals(ShapeOps.ADD_SIZE, line.shapes.single().width, 0.01f)
        assertEquals(0f, line.rotationZ, 0.001f)
    }

    @Test
    fun `a new shape's number is one past the highest already used for its kind`() {
        assertEquals(1, ShapeOps.nextNumber(emptyList(), "Rectangle"))
        val names = listOf("Rectangle 3", "Rectangle 1", "Ellipse 8", "Rectangle", "Rectangle x", "Rectangles 40")
            .plus("photo")
        val layers = names.map { Layer(id = it, name = it) }
        assertEquals(4, ShapeOps.nextNumber(layers, "Rectangle"))
        assertEquals(9, ShapeOps.nextNumber(layers, "Ellipse"))
        assertEquals(1, ShapeOps.nextNumber(layers, "Line"))
    }

    @Test
    fun `a line's height is a thin strip, not the 400 default, and the renderers ignore it`() {
        val shape = make(ShapeToolMode.LINE, Offset.Zero, Offset(100f, 0f))!!.shapes.single()
        assertEquals(ShapeOps.LINE_BOX_HEIGHT, shape.height, 0f)
        assertTrue(shape.height <= ShapeOps.LINE_STROKE_WIDTH)
        val added = ShapeOps.createCentred(ShapeToolMode.LINE, spec, Offset.Zero)!!.shapes.single()
        assertEquals(ShapeOps.LINE_BOX_HEIGHT, added.height, 0f)
    }

    @Test
    fun `the Frame tool's mode is the default and only the offered modes are read back`() {
        assertEquals(ShapeToolMode.FRAME, ShapeToolMode.DEFAULT)
        assertEquals(ShapeToolMode.FRAME, ShapeToolMode.fromName(null))
        assertEquals(ShapeToolMode.FRAME, ShapeToolMode.fromName("nonsense"))
        assertEquals(ShapeToolMode.FRAME, ShapeToolMode.fromName("STAR"))
        for (mode in ShapeToolMode.OFFERED) assertEquals(mode, ShapeToolMode.fromName(mode.name))
        assertFalse(ShapeToolMode.FRAME.drawsShape)
        assertTrue(ShapeToolMode.LINE.drawsShape)
        assertFalse(ShapeToolMode.STAR.drawsShape)
    }

    @Test
    fun `Tool FRAME keeps its place as the last tool, so a stroke's saved tool ordinal still resolves`() {
        assertEquals(Tool.FRAME, Tool.entries.last())
        assertEquals(Tool.entries.size - 1, Tool.FRAME.ordinal)
    }

    @Test
    fun `a shape layer saves with no key a frame-era layer lacks`() {
        // No new field: a project written with shapes opens in the build before this one.
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val shape = make(ShapeToolMode.RECTANGLE, Offset.Zero, Offset(50f, 50f))!!
        fun keysOf(layer: Layer) =
            (json.encodeToJsonElement(Layer.serializer(), layer) as kotlinx.serialization.json.JsonObject).keys
        val keys = keysOf(shape)
        val plain = keysOf(Layer(id = "p", name = "p"))
        assertEquals(plain, keys)
    }
}
