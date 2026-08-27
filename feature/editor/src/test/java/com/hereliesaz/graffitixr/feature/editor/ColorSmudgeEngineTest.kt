package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSmudgeEngineTest {

    private fun redBlock(width: Int = 64, height: Int = 32): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            if (x < 16) Color.RED else Color.WHITE
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
}