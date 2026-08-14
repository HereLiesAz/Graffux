package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.Dab
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [StampBrushRenderer] against real `android.graphics` — no test at all existed for this file before,
 * in a codebase that otherwise specifically invested in this render tier
 * ([SelectionMaskRenderTest], [ImageWarpRenderTest]) to catch exactly this class of bug: a swapped
 * argument order or a sign error that a pure-logic test, or no test, would never see.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StampBrushRendererTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val brush = AzphaltBrush(name = "test", hardness = 1f)

    private fun isOpaque(b: Bitmap, x: Int, y: Int) = Color.alpha(b.getPixel(x, y)) > 200

    @Test
    fun `a generated round tip paints an opaque disc at the dab centre and stays transparent past its radius`() {
        val canvas = RenderTestBase.filled(40, 40, Color.TRANSPARENT)
        val red = 0xFFFF0000.toInt()
        StampBrushRenderer.paintDabs(
            Canvas(canvas),
            dabs = listOf(Dab(x = 20f, y = 20f, radius = 8f, alpha = 1f, angleDeg = 0f)),
            brush = brush,
            colorArgb = red,
            flow = 1f,
        )
        assertTrue("dab centre should be opaque", isOpaque(canvas, 20, 20))
        // Well outside the radius on every side.
        assertTrue("corner should stay transparent", !isOpaque(canvas, 2, 2))
        assertTrue("corner should stay transparent", !isOpaque(canvas, 38, 38))
        val center = canvas.getPixel(20, 20)
        assertTrue(
            "centre should be tinted red, was ${Integer.toHexString(center)}",
            Color.red(center) > 200 && Color.green(center) < 60 && Color.blue(center) < 60,
        )
    }

    @Test
    fun `a shape stamp is positioned and rotated correctly, not mirrored or offset`() {
        // A stamp that is opaque only on its right half (x >= half-width), transparent on its left.
        // If the translate-scale-rotate-translate chain in paintDabs is composed in the wrong order,
        // or a sign is wrong, this asymmetric shape lands mirrored, offset, or unrotated — a mistake
        // a symmetric round-tip test could never catch.
        val stampSize = 20
        val stamp = Bitmap.createBitmap(stampSize, stampSize, Bitmap.Config.ARGB_8888)
        for (y in 0 until stampSize) {
            for (x in 0 until stampSize) {
                stamp.setPixel(x, y, if (x >= stampSize / 2) Color.WHITE else Color.TRANSPARENT)
            }
        }
        val blue = 0xFF0000FF.toInt()

        fun paint(angleDeg: Float): Bitmap {
            val canvasBmp = RenderTestBase.filled(40, 40, Color.TRANSPARENT)
            StampBrushRenderer.paintDabs(
                Canvas(canvasBmp),
                dabs = listOf(Dab(x = 20f, y = 20f, radius = 8f, alpha = 1f, angleDeg = angleDeg)),
                brush = brush,
                colorArgb = blue,
                flow = 1f,
                stamp = stamp,
            )
            return canvasBmp
        }

        // Unrotated: the opaque half of the stamp is on the right, so the right side of the dab
        // (relative to its centre at x=20) should be tinted and the left side transparent.
        val unrotated = paint(0f)
        assertTrue("right of centre should be opaque at angle 0", isOpaque(unrotated, 26, 20))
        assertTrue("left of centre should stay transparent at angle 0", !isOpaque(unrotated, 14, 20))

        // Rotated 180 degrees: the same opaque half now faces left instead of right.
        val rotated = paint(180f)
        assertTrue("left of centre should be opaque at angle 180", isOpaque(rotated, 14, 20))
        assertTrue("right of centre should stay transparent at angle 180", !isOpaque(rotated, 26, 20))
    }

    @Test
    fun `flow scales dab opacity`() {
        val faint = RenderTestBase.filled(40, 40, Color.TRANSPARENT)
        StampBrushRenderer.paintDabs(
            Canvas(faint),
            dabs = listOf(Dab(x = 20f, y = 20f, radius = 8f, alpha = 1f, angleDeg = 0f)),
            brush = brush,
            colorArgb = Color.BLACK or (0xFF shl 24),
            flow = 0.2f,
        )
        val full = RenderTestBase.filled(40, 40, Color.TRANSPARENT)
        StampBrushRenderer.paintDabs(
            Canvas(full),
            dabs = listOf(Dab(x = 20f, y = 20f, radius = 8f, alpha = 1f, angleDeg = 0f)),
            brush = brush,
            colorArgb = Color.BLACK or (0xFF shl 24),
            flow = 1f,
        )
        val faintAlpha = Color.alpha(faint.getPixel(20, 20))
        val fullAlpha = Color.alpha(full.getPixel(20, 20))
        assertTrue(
            "low flow ($faintAlpha) should be visibly less opaque than full flow ($fullAlpha)",
            faintAlpha < fullAlpha,
        )
    }
}
