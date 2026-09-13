package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSmudgeReservoirChargeTest {

    @Test
    fun `resolved charge remains identical to historical exponential envelope`() {
        val radius = 6f
        val step = radius / 2f
        val colorRate = 0.83f
        val decayRate = 0.037f
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = radius,
            smudgeRate = 0f,
            colorRate = colorRate,
            chargeDecayRate = decayRate,
            paintColor = 0xFFFF8000.toInt(),
        )
        val stroke = listOf(Offset(4f, 8f), Offset(34f, 8f))

        val plan = ColorSmudgeEngine.resolvePlans(
            stroke = stroke,
            width = 64,
            height = 32,
            settings = settings,
        ).single()

        plan.dabs.forEachIndexed { index, dab ->
            val distance = index * step
            val historical = (colorRate * exp(-decayRate * distance)).coerceIn(0f, 1f)
            assertTrue(
                "dab=$index distance=$distance old=$historical new=${dab.colorRate}",
                abs(historical - dab.colorRate) < 0.000001f,
            )
        }
    }

    @Test
    fun `zero decay still resolves flat color rate`() {
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = 8f,
            colorRate = 0.61f,
            chargeDecayRate = 0f,
            paintColor = 0xFF4080FF.toInt(),
        )
        val stroke = listOf(Offset(4f, 8f), Offset(44f, 8f))

        val plan = ColorSmudgeEngine.resolvePlans(
            stroke = stroke,
            width = 64,
            height = 32,
            settings = settings,
        ).single()

        plan.dabs.forEachIndexed { index, dab ->
            assertTrue(
                "dab=$index expected flat 0.61, got ${dab.colorRate}",
                abs(dab.colorRate - 0.61f) < 0.000001f,
            )
        }
    }
}
