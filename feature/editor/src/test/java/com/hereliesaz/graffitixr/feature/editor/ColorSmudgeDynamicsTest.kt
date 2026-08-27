package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSmudgeDynamicsTest {
    private fun redBlock(width: Int, height: Int) = IntArray(width * height) { i ->
        if (i % width < width / 4) Color.RED else Color.WHITE
    }

    // Plain JVM Android tests use the mockable android.jar, whose Color.red()/green() methods are
    // stubs. Read packed ARGB directly so these assertions measure the engine output rather than a
    // framework stub. Color.RED/GREEN/WHITE themselves are compile-time constants and are safe.
    private fun red(argb: Int): Int = argb ushr 16 and 0xFF
    private fun green(argb: Int): Int = argb ushr 8 and 0xFF

    private fun reach(px: IntArray, width: Int, y: Int): Int {
        var out = width / 4 - 1
        for (x in width / 4 until width) {
            val p = px[y * width + x]
            if (red(p) - green(p) > 20) out = x
        }
        return out
    }

    @Test
    fun `pressure can drive smudge rate through shared sensor engine`() {
        val w = 64; val h = 32
        val low = redBlock(w, h); val high = redBlock(w, h)
        val points = List(36) { Offset((8 + it).toFloat(), 16f) }
        fun samples(pressure: Float) = points.mapIndexed { i, p ->
            BrushSample(p.x, p.y, uptimeMillis = i * 8L, pressure = pressure, distancePx = i.toFloat(), speedPxPerMs = 0.125f)
        }
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = 6f,
            smudgeRate = 1f,
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE,
                    parameter = BrushParameter.SMUDGE_RATE,
                    outputMin = 0.05f,
                    outputMax = 1f,
                )
            ),
        )
        ColorSmudgeEngine.apply(low, w, h, points, settings, samples(0.05f), 42L)
        ColorSmudgeEngine.apply(high, w, h, points, settings, samples(1f), 42L)
        assertTrue("high pressure should carry red further", reach(high, w, 16) > reach(low, w, 16))
    }

    @Test
    fun `pressure can independently drive color rate`() {
        val w = 48; val h = 24
        val low = IntArray(w * h) { Color.WHITE }
        val high = low.copyOf()
        val points = List(24) { Offset((6 + it).toFloat(), 12f) }
        fun samples(pressure: Float) = points.mapIndexed { i, p ->
            BrushSample(p.x, p.y, uptimeMillis = i * 8L, pressure = pressure)
        }
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = 5f,
            colorRate = 1f,
            paintColor = Color.GREEN,
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE,
                    parameter = BrushParameter.COLOR_RATE,
                    outputMin = 0f,
                    outputMax = 1f,
                )
            ),
        )
        ColorSmudgeEngine.apply(low, w, h, points, settings, samples(0f), 7L)
        ColorSmudgeEngine.apply(high, w, h, points, settings, samples(1f), 7L)
        val lowPixel = low[12 * w + 20]
        val highPixel = high[12 * w + 20]
        val lowGreen = green(lowPixel) - red(lowPixel)
        val highGreen = green(highPixel) - red(highPixel)
        assertTrue("pressure-driven Color Rate should deposit more green", highGreen > lowGreen)
    }
}
