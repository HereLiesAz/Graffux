@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerNode
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.VectorShape
import com.hereliesaz.graffitixr.common.photo.DocumentCrop
import com.hereliesaz.graffitixr.common.photo.PhotoAdjustment
import com.hereliesaz.graffitixr.common.photo.PhotoTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The SVG/PDF scene renders the same pixels the PNG export (ExportManager.compositeToDocument)
 * produces: adjustment layers, colour filters, clipping masks, the document crop and the canvas
 * background included. Each case draws the vector page (PdfExport.draw, the PDF's own drawing) and
 * compares it with the PNG export of the same document.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VectorParityTest {

    private val size = 100
    private val manager = ExportManager().apply {
        // A stand-in for the GPU adjustment render: inverts what is beneath the layer.
        adjustmentRenderer = { below, _ ->
            val out = below.copy(Bitmap.Config.ARGB_8888, true)
            for (y in 0 until out.height) for (x in 0 until out.width) {
                val p = out.getPixel(x, y)
                out.setPixel(x, y, (p and 0xFF000000.toInt()) or (p.inv() and 0x00FFFFFF))
            }
            out
        }
    }

    private fun rect(id: String, argb: Long, w: Float = 100f, h: Float = 100f, offset: Offset = Offset.Zero) = Layer(
        id = id, name = id, offset = offset,
        shapes = listOf(VectorShape(ShapeKind.RECTANGLE, width = w, height = h, fillArgb = argb)),
    )

    private fun raster(layers: List<Layer>, crop: DocumentCrop?, bg: Int) = object : VectorRasterizer {
        override fun composite(nodes: List<LayerNode>, backgroundArgb: Int, container: Layer?) =
            Layer(id = "r", name = "r", bitmap = manager.compositeNodes(nodes, size, size, backgroundArgb, container))

        override fun document() =
            Layer(id = "d", name = "d", bitmap = manager.compositeToDocument(layers, size, size, size, size, backgroundColor = bg, crop = crop))
    }

    private fun assertParity(layers: List<Layer>, crop: DocumentCrop? = null, bg: Int = 0, points: List<Pair<Int, Int>>) {
        val png = manager.compositeToDocument(layers, size, size, size, size, backgroundColor = bg, crop = crop)
        val page = VectorScene.documentPage("Doc", layers, size.toFloat(), size.toFloat(), size, size, crop, bg, raster(layers, crop, bg))
        assertEquals(png.width.toFloat(), page.width)
        assertEquals(png.height.toFloat(), page.height)
        val vec = Bitmap.createBitmap(png.width, png.height, Bitmap.Config.ARGB_8888)
        PdfExport.draw(Canvas(vec), page)
        for ((x, y) in points) {
            assertEquals("pixel ($x, $y)", Integer.toHexString(png.getPixel(x, y)), Integer.toHexString(vec.getPixel(x, y)))
        }
    }

    @Test
    fun `an adjustment layer is applied to what is beneath it`() {
        val layers = listOf(rect("red", 0xFFFF0000L), Layer(id = "adj", name = "Invert", adjustment = PhotoAdjustment(PhotoTool.LIGHT)))
        assertParity(layers, points = listOf(50 to 50, 10 to 90))
    }

    @Test
    fun `layers above an adjustment stay vectors`() {
        val layers = listOf(rect("red", 0xFFFF0000L), Layer(id = "adj", name = "Invert", adjustment = PhotoAdjustment(PhotoTool.LIGHT)), rect("blue", 0xFF0000FFL, 20f, 20f))
        val page = VectorScene.documentPage("Doc", layers, 100f, 100f, 100, 100, raster = raster(layers, null, 0))
        assertEquals(2, page.nodes.size)
        assert(page.nodes[0] is VectorNode.Image)
        assert(page.nodes[1] is VectorNode.Group)
        assertParity(layers, points = listOf(50 to 50, 10 to 10))
    }

    @Test
    fun `colour filters are applied`() {
        val layers = listOf(rect("red", 0xFFFF0000L).copy(isInverted = true, opacity = 0.5f), rect("half", 0xFF00FF00L, 40f, 40f).copy(saturation = 0f))
        assertParity(layers, points = listOf(10 to 10, 50 to 50))
    }

    @Test
    fun `clip to below masks against the layer beneath`() {
        val layers = listOf(rect("left", 0xFFFF0000L, 50f, 100f, Offset(-25f, 0f)), rect("over", 0xFF0000FFL).copy(clipToLayerBelow = true))
        assertParity(layers, points = listOf(10 to 50, 90 to 50))
    }

    @Test
    fun `the canvas background is behind the document`() {
        val layers = listOf(rect("small", 0xFFFF0000L, 20f, 20f))
        assertParity(layers, bg = 0xFF00FF00.toInt(), points = listOf(5 to 5, 50 to 50))
    }

    @Test
    fun `the document crop cuts, flips and turns the page as the PNG does`() {
        val layers = listOf(rect("left", 0xFFFF0000L, 50f, 100f, Offset(-25f, 0f)), rect("right", 0xFF0000FFL, 50f, 100f, Offset(25f, 0f)))
        val crop = DocumentCrop(left = 0.5f, flipH = true, quarterTurns = 1)
        assertParity(layers, crop = crop, points = listOf(10 to 10, 40 to 90, 25 to 50))
        val page = VectorScene.documentPage("Doc", layers, 100f, 100f, 100, 100, crop)
        assertNotEquals(100f, page.width)
    }

    @Test
    fun `a perspective crop is embedded as the PNG render`() {
        val layers = listOf(rect("left", 0xFFFF0000L, 50f, 100f, Offset(-25f, 0f)))
        val crop = DocumentCrop(vertical = 40f)
        assertParity(layers, crop = crop, points = listOf(10 to 50, 90 to 50, 50 to 5))
    }
}
