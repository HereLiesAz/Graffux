package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.azphalt.GrainBehavior
import com.hereliesaz.graffitixr.common.azphalt.GrainBlendMode
import com.hereliesaz.graffitixr.common.azphalt.MaskedBrushConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class KritaTipTextureMaskTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    @After
    fun clearCache() {
        BrushTipMaskCache.clear()
    }

    @Test
    fun `tip cache reuses the same raster mask for the same key`() {
        val a = BrushTipMaskCache.tipMask(null, 24, 12, 0.6f)
        val b = BrushTipMaskCache.tipMask(null, 24, 12, 0.6f)
        assertSame(a, b)
    }

    @Test
    fun `ratio tip paints an ellipse rather than a circle`() {
        val bitmap = blank(64, 64)
        val brush = AzphaltBrush(name = "ellipse", tipRatio = 0.35f, hardness = 1f)
        val dabs = BrushStamps.dabs(listOf(32f, 32f), 24f, brush, 1L)
        StampBrushRenderer.paintDabs(Canvas(bitmap), dabs, brush, Color.BLACK, 1f)

        val horizontalCoverage = countPainted(bitmap, y = 32, horizontal = true)
        val verticalCoverage = countPainted(bitmap, y = 32, horizontal = false)
        assertTrue(horizontalCoverage > verticalCoverage * 2)
    }

    @Test
    fun `moving grain keeps local phase while canvas locked grain follows canvas coordinates`() {
        val grain = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).also {
            it.setPixels(intArrayOf(Color.BLACK, Color.WHITE), 0, 2, 0, 0, 2, 1)
        }
        val moving = AzphaltBrush(
            name = "moving",
            tipRatio = 0.99f, // advanced mask path even without a packaged shape
            hardness = 1f,
            grainPath = "grain.png",
            grainBehavior = GrainBehavior.MOVING,
            grainBlendMode = GrainBlendMode.MULTIPLY,
            grainStrength = 1f,
        )
        val canvasLocked = moving.copy(name = "canvas", grainBehavior = GrainBehavior.CANVAS_LOCKED)

        val movingAt20 = renderSingleDab(moving, grain, 20f)
        val movingAt21 = renderSingleDab(moving, grain, 21f)
        assertEquals(relativeAlphaSignature(movingAt20, 20), relativeAlphaSignature(movingAt21, 21))

        val canvasAt20 = renderSingleDab(canvasLocked, grain, 20f)
        val canvasAt21 = renderSingleDab(canvasLocked, grain, 21f)
        assertNotEquals(relativeAlphaSignature(canvasAt20, 20), relativeAlphaSignature(canvasAt21, 21))
        grain.recycle()
    }

    @Test
    fun `masked second tip clips the primary impression`() {
        val unmaskedBitmap = blank(64, 64)
        val maskedBitmap = blank(64, 64)
        val base = AzphaltBrush(name = "base", tipRatio = 0.99f, hardness = 1f)
        val masked = base.copy(
            name = "masked",
            maskedBrush = MaskedBrushConfig(sizeRatio = 0.45f, hardness = 1f),
        )

        StampBrushRenderer.paintDabs(
            Canvas(unmaskedBitmap), BrushStamps.dabs(listOf(32f, 32f), 24f, base, 4L),
            base, Color.BLACK, 1f,
        )
        StampBrushRenderer.paintDabs(
            Canvas(maskedBitmap), BrushStamps.dabs(listOf(32f, 32f), 24f, masked, 4L),
            masked, Color.BLACK, 1f,
        )

        assertTrue(Color.alpha(unmaskedBitmap.getPixel(41, 32)) > 0)
        assertEquals(0, Color.alpha(maskedBitmap.getPixel(41, 32)))
        assertTrue(Color.alpha(maskedBitmap.getPixel(32, 32)) > 0)
    }

    @Test
    fun `same seed and assets render identical advanced pixels`() {
        val grain = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).also {
            it.setPixels(
                intArrayOf(Color.WHITE, Color.GRAY, Color.BLACK, Color.BLACK, Color.WHITE, Color.GRAY),
                0, 3, 0, 0, 3, 2,
            )
        }
        val brush = AzphaltBrush(
            name = "deterministic",
            spacing = 0.2f,
            tipRatio = 0.55f,
            grainPath = "grain.png",
            grainBehavior = GrainBehavior.CANVAS_LOCKED,
            grainRandomOffsetPerStroke = true,
            maskedBrush = MaskedBrushConfig(
                sizeRatio = 0.8f,
                tipRatio = 0.5f,
                scatter = 0.6f,
                opacity = 0.8f,
            ),
        )
        val path = listOf(10f, 20f, 54f, 42f)
        val a = blank(64, 64)
        val b = blank(64, 64)
        val seed = 445566L
        StampBrushRenderer.paintStroke(Canvas(a), path, brush, Color.BLUE, 14f, 0.7f, seed, grain = grain)
        StampBrushRenderer.paintStroke(Canvas(b), path, brush, Color.BLUE, 14f, 0.7f, seed, grain = grain)
        assertEquals(pixelList(a), pixelList(b))
        grain.recycle()
    }

    private fun renderSingleDab(brush: AzphaltBrush, grain: Bitmap, x: Float): Bitmap {
        val bitmap = blank(48, 32)
        val dabs = BrushStamps.dabs(listOf(x, 16f), 12f, brush, 77L)
        StampBrushRenderer.paintDabs(Canvas(bitmap), dabs, brush, Color.BLACK, 1f, grain = grain, seed = 77L)
        return bitmap
    }

    private fun relativeAlphaSignature(bitmap: Bitmap, centerX: Int): List<Int> =
        (-5..5).map { dx -> Color.alpha(bitmap.getPixel(centerX + dx, 16)) }

    private fun countPainted(bitmap: Bitmap, y: Int, horizontal: Boolean): Int {
        var count = 0
        if (horizontal) {
            for (x in 0 until bitmap.width) if (Color.alpha(bitmap.getPixel(x, y)) > 0) count++
        } else {
            val x = bitmap.width / 2
            for (yy in 0 until bitmap.height) if (Color.alpha(bitmap.getPixel(x, yy)) > 0) count++
        }
        return count
    }

    private fun blank(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun pixelList(bitmap: Bitmap): List<Int> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.toList()
    }
}
