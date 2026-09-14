package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSmudgeReservoirPlanTest {

    @Test
    fun resolvedPlanCarriesDistanceAndUndepletedSensorMultiplierSeparately() {
        val settings = ColorSmudgeEngine.Settings(
            colorRate = 0.8f,
            chargeDecayRate = 0.1f,
            pickupRate = 0.75f,
            radiusPx = 8f,
        )
        val plan = ColorSmudgeEngine.resolvePlans(
            stroke = listOf(Offset(0f, 0f), Offset(12f, 0f)),
            width = 64,
            height = 64,
            settings = settings,
        ).single()

        assertEquals(4, plan.dabs.size)
        assertEquals(0f, plan.dabs.first().distanceDeltaPx, 0f)
        plan.dabs.drop(1).forEach { dab ->
            assertEquals(4f, dab.distanceDeltaPx, 0f)
            assertEquals(1f, dab.colorRateMultiplier, 0f)
        }

        plan.dabs.forEachIndexed { index, dab ->
            val expected = (0.8f * exp(-0.1f * index * 4f)).coerceIn(0f, 1f)
            assertEquals(expected, dab.colorRate, 0.000001f)
        }
        assertTrue(ColorSmudgeEngine.usesStatefulReservoir(settings))
    }
}
