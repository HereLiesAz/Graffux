package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One poster page drawn onto a bitmap at 1 px per point: the tile, its marks, and the whole PDF. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PosterPdfTest {

    private val spec = PosterSpec(
        posterWidthPt = 24 * PosterLayout.POINTS_PER_INCH,
        marginPt = 36f,
        overlapPt = 18f,
    )

    /** Left half red, right half blue, 300 × 400. */
    private fun art(): Bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width) setPixel(x, y, if (x < width / 2) Color.RED else Color.BLUE)
    }

    private fun page(tile: Int): Bitmap {
        val plan = PosterLayout.plan(spec, 300, 400)
        val out = Bitmap.createBitmap(plan.pageWidthPt.toInt(), plan.pageHeightPt.toInt(), Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        PosterPdf.drawPage(Canvas(out), art(), plan, spec, plan.tiles[tile])
        return out
    }

    /** Ink of any weight: a 0.5 pt hairline at 1 px per point is anti-aliased grey. */
    private fun dark(c: Int) = c != Color.WHITE && Color.red(c) == Color.green(c) && Color.green(c) == Color.blue(c)

    @Test
    fun `the first tile is the poster's top-left, inside the margins`() {
        val p = page(0)
        // Inside the printable area: the red half (the first page is left of the poster's middle).
        assertEquals(Color.RED, p.getPixel(100, 100))
        // The margin outside the art stays paper, away from the marks.
        assertEquals(Color.WHITE, p.getPixel(10, 400))
    }

    @Test
    fun `a page on the right half carries blue`() {
        // Column 3 of 4 starts at 2 · 522 = 1044 pt of 1728: past the middle.
        assertEquals(Color.BLUE, page(2).getPixel(100, 100))
    }

    @Test
    fun `crop marks sit off the corners and registration marks mid-edge`() {
        val p = page(0)
        System.getenv("POSTER_SCREENSHOTS")?.let { dir ->
            java.io.File(dir, "poster-page-1.png").outputStream().use { p.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // Crop mark: the horizontal tick left of the top-left corner (36, 36), from x 33 to 15.
        assertTrue(dark(p.getPixel(25, 36)))
        // Clear of the art by the gap.
        assertEquals(Color.WHITE, p.getPixel(25, 30))
        // Registration cross at the top edge's middle, centred in the margin: (306, 18).
        assertTrue(dark(p.getPixel(306, 18)))
    }

    @Test
    fun `one page per tile, in order, at the paper's size`() {
        val plan = PosterLayout.plan(spec, 300, 400)
        val pages = mutableListOf<Triple<Int, Int, Int>>()
        val pixels = mutableListOf<Int>()
        PosterPdf.render(art(), plan, spec, object : PosterPdf.PageSink {
            var bitmap: Bitmap? = null
            override fun begin(width: Int, height: Int, number: Int): Canvas {
                pages += Triple(width, height, number)
                return Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it })
            }
            override fun end() {
                pixels += bitmap!!.getPixel(100, 100)
            }
        })
        assertEquals(16, plan.pageCount)
        assertEquals((1..16).map { Triple(612, 792, it) }, pages)
        // Row 1: two red pages then two blue (the poster's halves), and the same on every row.
        assertEquals(listOf(Color.RED, Color.RED, Color.BLUE, Color.BLUE), pixels.take(4))
        assertEquals(pixels.take(4), pixels.subList(12, 16))
    }
}
