package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sensor routes and Wet Mix generalizations as they reach the GPU: [ColorSmudgeEngine.resolvePlans]
 * is the only Kotlin step between a recorded smudge stroke and the GPU kernels (GpuColorSmudge), so
 * these pin what each dab is told to do. The pixels themselves are covered by the GPU instrumented
 * tests and core/wgpu-engine's smudge tests.
 */
class ColorSmudgeDynamicsTest {

    private val points = List(36) { Offset((8 + it).toFloat(), 16f) }

    private fun samples(pressure: Float) = points.mapIndexed { i, p ->
        BrushSample(p.x, p.y, uptimeMillis = i * 8L, pressure = pressure, distancePx = i.toFloat(), speedPxPerMs = 0.125f)
    }

    private fun binding(parameter: BrushParameter, min: Float) =
        BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = parameter, outputMin = min, outputMax = 1f)

    @Test
    fun `pressure drives smudge rate through the shared sensor engine`() {
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = 6f,
            smudgeRate = 1f,
            dynamics = listOf(binding(BrushParameter.SMUDGE_RATE, 0.05f)),
        )
        val low = ColorSmudgeEngine.resolvePlans(points, 64, 32, settings, samples(0.05f), 42L).single().dabs
        val high = ColorSmudgeEngine.resolvePlans(points, 64, 32, settings, samples(1f), 42L).single().dabs
        assertEquals(low.size, high.size)
        assertTrue(low.zip(high).all { (l, h) -> h.smudgeRate > l.smudgeRate })
        assertEquals(1f, high.first().smudgeRate, 1e-4f)
    }

    @Test
    fun `pressure drives color rate independently of smudge rate`() {
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = 5f,
            colorRate = 1f,
            dynamics = listOf(binding(BrushParameter.COLOR_RATE, 0f)),
        )
        val low = ColorSmudgeEngine.resolvePlans(points, 48, 24, settings, samples(0f), 7L).single().dabs
        val high = ColorSmudgeEngine.resolvePlans(points, 48, 24, settings, samples(1f), 7L).single().dabs
        assertTrue(low.zip(high).all { (l, h) -> h.colorRate > l.colorRate })
        assertTrue(low.zip(high).all { (l, h) -> h.smudgeRate == l.smudgeRate })
    }

    @Test
    fun `without dynamics every dab carries the flat settings`() {
        val settings = ColorSmudgeEngine.Settings(radiusPx = 5f, smudgeRate = 0.4f, colorRate = 0.3f, opacity = 0.7f)
        val dabs = ColorSmudgeEngine.resolvePlans(points, 48, 24, settings).single().dabs
        for (dab in dabs) {
            assertEquals(0.4f, dab.smudgeRate, 1e-5f)
            assertEquals(0.3f, dab.colorRate, 1e-5f)
            assertEquals(0.7f, dab.opacity, 1e-5f)
        }
    }

    @Test
    fun `charge decay lowers color rate monotonically along the stroke`() {
        val settings = ColorSmudgeEngine.Settings(radiusPx = 5f, colorRate = 1f, chargeDecayRate = 0.05f)
        val rates = ColorSmudgeEngine.resolvePlans(points, 64, 16, settings).single().dabs.map { it.colorRate }
        assertTrue("expected colorRate to decay, not stay flat", rates.first() > rates.last())
        for (i in 1 until rates.size) assertTrue(rates[i] <= rates[i - 1])
    }
}
