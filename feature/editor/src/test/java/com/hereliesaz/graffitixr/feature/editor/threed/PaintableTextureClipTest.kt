package com.hereliesaz.graffitixr.feature.editor.threed

import android.graphics.Bitmap
import android.graphics.Color
import com.hereliesaz.graffitixr.common.mesh.TexturePaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The clip a dab is limited to: the island, a filter gutter around it, never another island. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PaintableTextureClipTest {

    /** A rectangle over u [u0]..[u1], v 0..1, as two UV triangles. */
    private fun rect(u0: Float, u1: Float) =
        floatArrayOf(u0, 0f, u1, 0f, u1, 1f, u0, 0f, u1, 1f, u0, 1f)

    private fun texture() = PaintableTexture(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))

    private fun PaintableTexture.dab() =
        stamp(listOf(TexturePaint.Stamp(30f, 50f)), Color.RED, radiusPx = 60f, hardness = 1f)

    private fun PaintableTexture.alpha(x: Int) = Color.alpha(bitmap.getPixel(x, 50))

    @Test
    fun `no clip paints the whole dab`() {
        val t = texture().apply { clipTo(null); dab() }
        assertEquals(255, t.alpha(80))
    }

    @Test
    fun `the clip is the island plus a gutter of padding`() {
        // The island ends at x = 50.
        val t = texture().apply { clipTo(rect(0f, 0.5f)); dab() }
        assertEquals("inside", 255, t.alpha(49))
        val gutter = PaintableTexture.GUTTER_TEXELS.toInt()
        for (x in 50 until 50 + gutter) assertEquals("gutter texel $x", 255, t.alpha(x))
        assertEquals("past the gutter", 0, t.alpha(50 + gutter + 1))
        assertEquals("far", 0, t.alpha(80))
    }

    @Test
    fun `the gutter never enters a neighbouring island`() {
        // This island ends at 50, the next starts at 51: one texel of padding between them.
        val t = texture().apply { clipTo(rect(0f, 0.5f), rect(0.51f, 1f)); dab() }
        assertEquals("inside", 255, t.alpha(49))
        assertEquals("the padding between them", 255, t.alpha(50))
        assertEquals("the neighbour's first texel", 0, t.alpha(51))
        assertEquals("the neighbour", 0, t.alpha(70))
    }

    @Test
    fun `the islands own texels survive a neighbour that overlaps them in uv`() {
        // A neighbour drawn over the whole island (mirrored UVs overlap in real atlases) must not cut it.
        val t = texture().apply { clipTo(rect(0f, 0.5f), rect(0f, 1f)); dab() }
        assertEquals(255, t.alpha(30))
        assertEquals(255, t.alpha(49))
    }

    @Test
    fun `two islands painted from both sides leave no half-coverage texel at the seam`() {
        val left = texture().apply { clipTo(rect(0f, 0.5f), rect(0.51f, 1f)); dab() }
        // Paint the right island on the same bitmap.
        left.clipTo(rect(0.51f, 1f), rect(0f, 0.5f))
        left.stamp(listOf(TexturePaint.Stamp(70f, 50f)), Color.RED, radiusPx = 60f, hardness = 1f)
        for (x in 40..60) assertTrue("texel $x alpha ${left.alpha(x)}", left.alpha(x) == 255)
    }
}
