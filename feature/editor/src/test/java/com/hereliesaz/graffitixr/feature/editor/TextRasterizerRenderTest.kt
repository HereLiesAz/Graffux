package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import com.hereliesaz.graffitixr.common.model.TextLayerParams
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * [TextRasterizer] against real `android.graphics` and `android.text`. Before this, the only place
 * it was exercised at all was `EditorViewModelTest`, which mocks the whole object — `rasterize`'s
 * real body never ran in any test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TextRasterizerRenderTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private fun opaquePixels(b: Bitmap): List<Int> {
        val out = ArrayList<Int>()
        for (y in 0 until b.height) {
            for (x in 0 until b.width) {
                val p = b.getPixel(x, y)
                if (Color.alpha(p) > 0) out.add(p)
            }
        }
        return out
    }

    private fun colorDistance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    @Test
    fun `rasterize actually draws the glyph, not a blank bitmap`() {
        val params = TextLayerParams(text = "A", fontSizeDp = 80f)
        val bmp = TextRasterizer.rasterize(params, widthPx = 200, heightPx = 200, density = 1f)
        assertTrue("expected at least some opaque pixels for rendered text", opaquePixels(bmp).isNotEmpty())
    }

    @Test
    fun `fill colour is applied to the glyph`() {
        val red = 0xFFFF0000.toInt()
        val params = TextLayerParams(text = "I", fontSizeDp = 100f, colorArgb = red, isBold = true)
        val bmp = TextRasterizer.rasterize(params, widthPx = 200, heightPx = 200, density = 1f)
        val pixels = opaquePixels(bmp)
        assertTrue("expected some opaque pixels", pixels.isNotEmpty())
        // Anti-aliased edges blend towards other colours, but the solid glyph body should contain
        // pixels at (or extremely close to) the fill colour.
        assertTrue(
            "expected some pixel close to the fill colour, closest was ${pixels.minOf { colorDistance(it, red) }}",
            pixels.any { colorDistance(it, red) < 10 },
        )
    }

    @Test
    fun `outline is drawn in the outline colour underneath the fill, not skipped or overwritten`() {
        val fillColor = 0xFFFFFFFF.toInt()
        val outlineColor = 0xFF0000FF.toInt()
        val params = TextLayerParams(
            text = "I", fontSizeDp = 120f, colorArgb = fillColor,
            hasOutline = true, outlineColorArgb = outlineColor, outlineWidthDp = 14f,
        )
        val bmp = TextRasterizer.rasterize(params, widthPx = 200, heightPx = 200, density = 1f)
        val pixels = opaquePixels(bmp)
        assertTrue("expected some opaque pixels", pixels.isNotEmpty())
        assertTrue(
            "expected some pixel close to the outline colour (proves the outline pass actually " +
                "drew, isn't a no-op, and isn't fully painted over by the fill)",
            pixels.any { colorDistance(it, outlineColor) < 10 },
        )
        assertTrue(
            "expected some pixel close to the fill colour",
            pixels.any { colorDistance(it, fillColor) < 10 },
        )
    }

    @Test
    fun `an empty string falls back to placeholder text instead of rendering nothing`() {
        val params = TextLayerParams(text = "", fontSizeDp = 80f)
        val bmp = TextRasterizer.rasterize(params, widthPx = 200, heightPx = 200, density = 1f)
        assertTrue("expected the 'Text' placeholder to still render something", opaquePixels(bmp).isNotEmpty())
    }
}
