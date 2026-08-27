package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KritaBrushStagesTest {

    private val straight = listOf(0f, 0f, 100f, 0f)

    @Test
    fun `default isotropic spacing preserves diameter based placement`() {
        val brush = AzphaltBrush(name = "legacy", spacing = 0.25f)
        val dabs = BrushStamps.dabs(straight, diameterPx = 20f, brush = brush, seed = 1L)
        // 20 * .25 = 5 px → 0..100 inclusive.
        assertEquals(21, dabs.size)
        assertEquals(0f, dabs.first().x, 0f)
        assertEquals(100f, dabs.last().x, 0f)
        dabs.forEach { assertEquals(1f, it.tipRatio, 0f) }
    }

    @Test
    fun `ratio aware spacing densifies an elongated tip`() {
        val isotropic = AzphaltBrush(
            name = "iso", spacing = 0.25f, tipRatio = 0.5f, isotropicSpacing = true,
        )
        val ratioAware = isotropic.copy(name = "ratio", isotropicSpacing = false)
        val a = BrushStamps.dabs(straight, 20f, isotropic, seed = 1L)
        val b = BrushStamps.dabs(straight, 20f, ratioAware, seed = 1L)
        assertEquals(21, a.size)
        assertEquals(41, b.size) // 20 * .5 * .25 = 2.5 px.
    }

    @Test
    fun `dynamic spacing is evaluated from the current sensor sample`() {
        val samples = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L, speedPxPerMs = 0f),
            BrushSample(100f, 0f, uptimeMillis = 100L, speedPxPerMs = 2f),
        )
        val fixed = AzphaltBrush(name = "fixed", spacing = 0.25f)
        val dynamic = fixed.copy(
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.SPEED,
                    parameter = BrushParameter.SPACING,
                    inputMin = 0f,
                    inputMax = 2f,
                    outputMin = 0.5f,
                    outputMax = 2f,
                )
            )
        )
        val fixedDabs = BrushStamps.dynamicDabs(samples, 20f, fixed, seed = 9L)
        val dynamicDabs = BrushStamps.dynamicDabs(samples, 20f, dynamic, seed = 9L)
        assertTrue(dynamicDabs.size != fixedDabs.size)
        // The first sample is slow, so the first dynamic step is half the normal step.
        assertEquals(2.5f, dynamicDabs[1].x, 0.05f)
    }

    @Test
    fun `adding a masked brush does not perturb primary jitter`() {
        val base = AzphaltBrush(
            name = "base",
            spacing = 0.25f,
            sizeJitter = 0.4f,
            opacityJitter = 0.35f,
            scatter = 0.75f,
        )
        val masked = base.copy(maskedBrush = MaskedBrushConfig(scatter = 1f, sizeRatio = 0.7f))
        val plainDabs = BrushStamps.dabs(straight, 20f, base, seed = 123L)
        val maskedDabs = BrushStamps.dabs(straight, 20f, masked, seed = 123L)
        assertEquals(plainDabs.size, maskedDabs.size)
        plainDabs.zip(maskedDabs).forEach { (plain, withMask) ->
            assertEquals(plain.x, withMask.x, 0f)
            assertEquals(plain.y, withMask.y, 0f)
            assertEquals(plain.radius, withMask.radius, 0f)
            assertEquals(plain.alpha, withMask.alpha, 0f)
            assertEquals(plain.angleDeg, withMask.angleDeg, 0f)
            assertNotNull(withMask.mask)
        }
    }

    @Test
    fun `masked brush replay is deterministic`() {
        val brush = AzphaltBrush(
            name = "masked",
            spacing = 0.2f,
            maskedBrush = MaskedBrushConfig(
                sizeRatio = 0.8f,
                tipRatio = 0.4f,
                scatter = 1.25f,
                opacity = 0.7f,
                flow = 0.6f,
                angle = 17f,
            ),
        )
        val a = BrushStamps.dabs(straight, 16f, brush, seed = 999L)
        val b = BrushStamps.dabs(straight, 16f, brush, seed = 999L)
        assertEquals(a, b)
        assertTrue(a.all { it.mask != null })
    }

    @Test
    fun `masked tip can have independent pressure dynamics`() {
        val samples = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L, pressure = 0f),
            BrushSample(100f, 0f, uptimeMillis = 100L, pressure = 1f),
        )
        val brush = AzphaltBrush(
            name = "mask-pressure",
            spacing = 0.5f,
            maskedBrush = MaskedBrushConfig(
                sizeRatio = 1f,
                dynamics = listOf(
                    BrushSensorBinding(
                        sensor = BrushSensor.PRESSURE,
                        parameter = BrushParameter.SIZE,
                        outputMin = 0.25f,
                        outputMax = 1f,
                    )
                ),
            ),
        )
        val dabs = BrushStamps.dynamicDabs(samples, 20f, brush, seed = 4L)
        val first = dabs.first().mask!!
        val last = dabs.last().mask!!
        assertTrue(last.radius > first.radius)
        // Primary radius is unaffected because only the masked brush owns the route.
        dabs.forEach { assertEquals(10f, it.radius, 0.001f) }
    }
}
