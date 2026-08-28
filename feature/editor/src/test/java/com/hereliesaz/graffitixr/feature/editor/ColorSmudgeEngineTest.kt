package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
class ColorSmudgeEngineTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private fun redBlock(width: Int = 64, height: Int = 32): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            if (x < 16) Color.RED else Color.WHITE
        }

    private fun redBlockBitmap(width: Int = 64, height: Int = 32): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.setPixels(redBlock(width, height), 0, width, 0, 0, width, height)
        }

    private fun redReach(px: IntArray, width: Int, y: Int): Int {
        var reach = 15
        for (x in 16 until width) {
            val p = px[y * width + x]
            if (Color.red(p) - Color.green(p) > 20) reach = x
        }
        return reach
    }

    @Test
    fun `smear compatibility preset is pixel-identical to original Graffux smudge`() = runBlocking {
        val width = 64
        val height = 32
        val source = redBlockBitmap(width, height)
        val stroke = List(40) { Offset((8 + it).toFloat(), 16f) }
        val intensity = 0.73f
        val feathering = 0.35f

        val legacy = ImageProcessor.applyToolToBitmap(
            source,
            stroke,
            Tool.SMUDGE,
            brushSize = 12f,
            intensity = intensity,
            feathering = feathering,
        )

        val extracted = redBlock(width, height)
        ColorSmudgeEngine.apply(
            extracted,
            width,
            height,
            stroke,
            ColorSmudgeEngine.Settings(
                mode = ColorSmudgeEngine.Mode.SMEAR,
                radiusPx = 6f,
                smudgeRate = 0.35f + intensity * 0.6f,
                colorRate = 0f,
                opacity = 1f,
                feathering = feathering,
                smearAlpha = true,
            ),
        )

        val legacyPixels = IntArray(width * height)
        legacy.getPixels(legacyPixels, 0, width, 0, 0, width, height)
        assertArrayEquals(
            "extracting Smear from ImageProcessor must not change a single pixel",
            legacyPixels,
            extracted,
        )
    }

    @Test
    fun `smear carries colour in stroke direction`() {
        val width = 64
        val height = 32
        val px = redBlock(width, height)
        val stroke = List(40) { Offset((8 + it).toFloat(), 16f) }

        ColorSmudgeEngine.apply(
            px,
            width,
            height,
            stroke,
            ColorSmudgeEngine.Settings(
                mode = ColorSmudgeEngine.Mode.SMEAR,
                radiusPx = 6f,
                smudgeRate = 0.95f,
            ),
        )

        assertTrue(redReach(px, width, 16) > 24)
        assertEquals(Color.RED, px[16 * width + 1])
    }

    @Test
    fun `smear can preserve destination alpha while moving rgb`() {
        val width = 40
        val height = 20
        val px = IntArray(width * height) { i ->
            val x = i % width
            if (x < 10) Color.argb(80, 255, 0, 0) else Color.argb(180, 255, 255, 255)
        }
        val beforeAlpha = Color.alpha(px[10 * width + 18])

        ColorSmudgeEngine.apply(
            px,
            width,
            height,
            List(24) { Offset((4 + it).toFloat(), 10f) },
            ColorSmudgeEngine.Settings(
                mode = ColorSmudgeEngine.Mode.SMEAR,
                radiusPx = 5f,
                smudgeRate = 1f,
                smearAlpha = false,
            ),
        )

        assertEquals(beforeAlpha, Color.alpha(px[10 * width + 18]))
    }

    @Test
    fun `dulling mixes a hard colour boundary locally`() {
        val width = 48
        val height = 24
        val px = IntArray(width * height) { i ->
            if (i % width < width / 2) Color.RED else Color.BLUE
        }

        ColorSmudgeEngine.apply(
            px,
            width,
            height,
            listOf(Offset(18f, 12f), Offset(24f, 12f), Offset(30f, 12f)),
            ColorSmudgeEngine.Settings(
                mode = ColorSmudgeEngine.Mode.DULLING,
                radiusPx = 5f,
                smudgeRate = 1f,
                smudgeRadius = 1.5f,
            ),
        )

        val mixed = px[12 * width + 24]
        assertTrue("boundary should contain red after dulling", Color.red(mixed) > 20)
        assertTrue("boundary should contain blue after dulling", Color.blue(mixed) > 20)
    }

    @Test
    fun `color rate deposits foreground independently of smudge`() {
        val width = 40
        val height = 20
        val noPaint = IntArray(width * height) { Color.WHITE }
        val withPaint = noPaint.copyOf()
        val stroke = listOf(Offset(5f, 10f), Offset(15f, 10f), Offset(25f, 10f))

        ColorSmudgeEngine.apply(
            noPaint,
            width,
            height,
            stroke,
            ColorSmudgeEngine.Settings(
                mode = ColorSmudgeEngine.Mode.SMEAR,
                radiusPx = 5f,
                smudgeRate = 1f,
                colorRate = 0f,
                paintColor = Color.GREEN,
            ),
        )
        ColorSmudgeEngine.apply(
            withPaint,
            width,
            height,
            stroke,
            ColorSmudgeEngine.Settings(
                mode = ColorSmudgeEngine.Mode.SMEAR,
                radiusPx = 5f,
                smudgeRate = 1f,
                colorRate = 0.75f,
                paintColor = Color.GREEN,
            ),
        )

        assertEquals(Color.WHITE, noPaint[10 * width + 20])
        val painted = withPaint[10 * width + 20]
        assertTrue(Color.green(painted) > Color.red(painted))
    }

    @Test
    fun `flat colour stays flat with pure smear`() {
        val width = 40
        val height = 20
        val color = Color.argb(255, 70, 120, 190)
        val px = IntArray(width * height) { color }

        ColorSmudgeEngine.apply(
            px,
            width,
            height,
            List(30) { Offset((5 + it).toFloat(), 10f) },
            ColorSmudgeEngine.Settings(
                mode = ColorSmudgeEngine.Mode.SMEAR,
                radiusPx = 5f,
                smudgeRate = 0.8f,
            ),
        )

        assertTrue(px.all { it == color })
    }

    @Test
    fun `dulling pickup near a transparent edge does not darken the opaque colour`() {
        // A glee audit found weightedAverage() (Dulling's colour pickup) mixed RGB in straight-
        // alpha space: a fully-transparent neighbour's RGB pulled the average towards black at the
        // same weight as an opaque one, purely from spatial distance, ignoring that a transparent
        // pixel has no real colour to contribute. Fixed by weighting RGB by alpha too (premultiplied
        // averaging); alpha itself is still a plain spatial average, so it correctly still drops.
        val width = 3
        val height = 1
        val px = intArrayOf(Color.RED, Color.RED, Color.TRANSPARENT)

        ColorSmudgeEngine.apply(
            px,
            width,
            height,
            listOf(Offset(1f, 0f), Offset(1f, 0f)),
            ColorSmudgeEngine.Settings(
                mode = ColorSmudgeEngine.Mode.DULLING,
                radiusPx = 2f,
                smudgeRadius = 1f,
                smudgeRate = 1f,
                colorRate = 0f,
                opacity = 1f,
                feathering = 0f,
                smearAlpha = true,
            ),
        )

        val painted = px[1]
        assertEquals(
            "the opaque neighbour's red must stay fully saturated, not be dragged towards black " +
                "by an adjacent transparent pixel's meaningless RGB",
            255,
            Color.red(painted),
        )
        assertTrue(
            "alpha must still drop towards the transparent neighbour -- only RGB should be immune",
            Color.alpha(painted) < 255,
        )
    }
}