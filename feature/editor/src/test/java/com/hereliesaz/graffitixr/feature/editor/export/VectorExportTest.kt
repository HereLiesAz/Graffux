@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.model.FrameOps
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Rect
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VectorExportTest {

    private val cw = 400f
    private val ch = 800f

    // A 200 x 100 frame with its top-left at world (0, 0), and a red 50 x 20 rectangle centred in it.
    private val frame = FrameOps.create("f", "Pay", Rect(0f, 0f, 200f, 100f), fillArgb = 0xFF1A1A1DL)
    private val card = Layer(
        id = "card", name = "Card", parentId = "f",
        shapes = listOf(VectorShape(ShapeKind.RECTANGLE, width = 50f, height = 20f, cornerRadius = 4f, fillArgb = 0xFFFF0000L)),
    )
    private val outside = Layer(id = "o", name = "Other", offset = Offset(900f, 0f), shapes = listOf(VectorShape(ShapeKind.ELLIPSE)))

    private fun map(page: VectorPage, path: List<VectorNode>, x: Float, y: Float): Pair<Float, Float> {
        var t = Affine.IDENTITY
        path.asReversed().forEach { t = t.then(it.transform) }
        return t.then(page.root).map(x, y)
    }

    @Test
    fun `a frame page is exactly the frame's box with only what is in it`() {
        val page = VectorScene.framePage(listOf(frame, card, outside), "f", cw, ch)!!
        assertEquals(200f, page.width)
        assertEquals(100f, page.height)
        val group = page.nodes.single() as VectorNode.Group
        assertTrue(group.clips)
        val cardGroup = group.children.single() as VectorNode.Group
        // The rectangle's centre (canvas centre in its own space) lands at the page's centre.
        val (x, y) = map(page, listOf(group, cardGroup), cw / 2f, ch / 2f)
        assertEquals(100f, x, 0.01f)
        assertEquals(50f, y, 0.01f)
        // The frame box's top-left is the page's origin.
        val box = group.box!!
        val (bx, by) = map(page, listOf(group), box[0], box[1])
        assertEquals(0f, bx, 0.01f)
        assertEquals(0f, by, 0.01f)
    }

    @Test
    fun `the SVG has the frame's clip and fill, the shape as a path, and keeps blend modes`() {
        val page = VectorScene.framePage(listOf(frame, card.copy(blendMode = BlendMode.Multiply, opacity = 0.5f)), "f", cw, ch)!!
        val svg = SvgWriter.write(page) { null }
        assertTrue(svg.contains("""width="200" height="100" viewBox="0 0 200 100""""))
        assertTrue(svg.contains("<clipPath id=\"clip0\">"))
        assertTrue(svg.contains("fill=\"#1a1a1d\""))
        assertTrue(svg.contains("<path"))
        assertTrue(svg.contains("fill=\"#ff0000\""))
        assertTrue(svg.contains("mix-blend-mode:multiply"))
        assertTrue(svg.contains("opacity=\"0.5\""))
        assertFalse(svg.contains("Other"))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
    }

    @Test
    fun `pixel layers are embedded as PNG images and skipped when they cannot be encoded`() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val raster = Layer(id = "r", name = "Photo", parentId = "f", bitmap = bmp)
        val page = VectorScene.framePage(listOf(frame, raster), "f", cw, ch)!!
        assertTrue(SvgWriter.write(page) { byteArrayOf(1, 2, 3) }.contains("data:image/png;base64,AQID"))
        assertFalse(SvgWriter.write(page) { null }.contains("<image"))
    }

    @Test
    fun `rounded rectangles and ellipses become closed cubic outlines`() {
        val rr = VectorScene.roundRect(0f, 0f, 100f, 50f, 10f)
        assertEquals(PathCmd.Move(10f, 0f), rr.first())
        assertEquals(PathCmd.Close, rr.last())
        assertEquals(4, rr.count { it is PathCmd.Cubic })
        // A radius past half the short side is clamped.
        assertEquals(PathCmd.Move(25f, 0f), VectorScene.roundRect(0f, 0f, 100f, 50f, 99f).first())
        val ellipse = VectorScene.outline(VectorShape(ShapeKind.ELLIPSE, width = 20f, height = 10f), 0f, 0f)
        assertEquals(PathCmd.Move(10f, 0f), ellipse.first())
    }

    @Test
    fun `every top-level frame is a page, and the document page is the artboard`() {
        val second = FrameOps.create("g", "Done", Rect(300f, 0f, 100f, 100f))
        val pages = VectorScene.allFramePages(listOf(frame, card, second), cw, ch)
        assertEquals(listOf("Pay", "Done"), pages.map { it.name })
        val doc = VectorScene.documentPage("Wall", listOf(card.copy(parentId = null)), cw, ch, 1000, 1000)
        assertEquals(1000f, doc.width)
        // The square artboard fitted to the 400 x 800 canvas is 400 wide: its scale is 2.5.
        assertEquals(2.5f, doc.root.a, 0.001f)
    }

    @Test
    fun `the PDF drawing fills the frame and clips what spills out`() {
        val spill = card.copy(offset = Offset(90f, 0f)) // 165..215: past the frame's right edge at 200
        val page = VectorScene.framePage(listOf(frame, spill), "f", cw, ch)!!
        val bmp = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        PdfExport.draw(Canvas(bmp), page)
        assertEquals(0xFF1A1A1D.toInt(), bmp.getPixel(10, 10))
        assertEquals(0xFFFF0000.toInt(), bmp.getPixel(195, 50))
        // What spills past the box is clipped: the page ends at 200, so draw a wider bitmap to see.
        val wide = Bitmap.createBitmap(260, 100, Bitmap.Config.ARGB_8888)
        PdfExport.draw(Canvas(wide), page)
        assertEquals(0, wide.getPixel(210, 50))
        assertNotNull(wide)
    }
}
