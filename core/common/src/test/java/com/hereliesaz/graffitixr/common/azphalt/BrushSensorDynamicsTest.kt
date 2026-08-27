package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushSensorDynamicsTest {

    @Test
    fun `predicted sample never contaminates real stroke kinematics`() {
        val builder = BrushSampleBuilder()
        val first = builder.add(0f, 0f, 100L)
        val predicted = builder.add(100f, 0f, 110L, predicted = true)
        val real = builder.add(10f, 0f, 110L)

        assertEquals(0f, first.distancePx, 0f)
        assertEquals(100f, predicted.distancePx, 0f)
        assertEquals(10f, real.distancePx, 0f)
        assertEquals(1f, real.speedPxPerMs, 1e-6f)
    }

    @Test
    fun `response curve interpolates between authored points`() {
        val curve = BrushResponseCurve(
            listOf(
                BrushCurvePoint(0f, 0f),
                BrushCurvePoint(0.5f, 0.25f),
                BrushCurvePoint(1f, 1f),
            )
        )
        assertEquals(0.125f, curve.evaluate(0.25f), 1e-6f)
        assertEquals(0.625f, curve.evaluate(0.75f), 1e-6f)
    }

    @Test
    fun `pressure can drive size through a curve`() {
        val binding = BrushSensorBinding(
            sensor = BrushSensor.PRESSURE,
            parameter = BrushParameter.SIZE,
            outputMin = 0.2f,
            outputMax = 1f,
        )
        val low = BrushSensorEngine.resolve(
            BrushSample(0f, 0f, pressure = 0f), listOf(binding), 0L, 7L, 0,
        )
        val high = BrushSensorEngine.resolve(
            BrushSample(0f, 0f, pressure = 1f), listOf(binding), 0L, 7L, 0,
        )
        assertEquals(0.2f, low.sizeMultiplier, 1e-6f)
        assertEquals(1f, high.sizeMultiplier, 1e-6f)
    }

    @Test
    fun `random sensors are deterministic and dab-local`() {
        val binding = BrushSensorBinding(
            sensor = BrushSensor.RANDOM_DAB,
            parameter = BrushParameter.SIZE,
        )
        val sample = BrushSample(0f, 0f)
        val a = BrushSensorEngine.resolve(sample, listOf(binding), 0L, 1234L, 8).sizeMultiplier
        val b = BrushSensorEngine.resolve(sample, listOf(binding), 0L, 1234L, 8).sizeMultiplier
        val c = BrushSensorEngine.resolve(sample, listOf(binding), 0L, 1234L, 9).sizeMultiplier
        assertEquals(a, b, 0f)
        assertNotEquals(a, c)
    }

    @Test
    fun `sample-aware dabs preserve legacy output when no routes exist`() {
        val brush = AzphaltBrush(
            name = "Legacy",
            spacing = 0.25f,
            opacity = 0.8f,
            sizeJitter = 0.2f,
            opacityJitter = 0.1f,
            scatter = 0.3f,
            followStroke = true,
        )
        val points = listOf(0f, 0f, 25f, 0f, 50f, 20f)
        val samples = listOf(
            BrushSample(0f, 0f, 0L),
            BrushSample(25f, 0f, 10L),
            BrushSample(50f, 20f, 20L),
        )

        assertEquals(
            BrushStamps.dabs(points, 20f, brush, 99L),
            BrushStamps.dynamicDabs(samples, 20f, brush, 99L),
        )
    }

    @Test
    fun `pressure changes concrete dab radius`() {
        val brush = AzphaltBrush(
            name = "Pressure",
            spacing = 0.5f,
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE,
                    parameter = BrushParameter.SIZE,
                    outputMin = 0.25f,
                    outputMax = 1f,
                )
            ),
        )
        val builder = BrushSampleBuilder()
        val samples = listOf(
            builder.add(0f, 0f, 0L, pressure = 0f),
            builder.add(100f, 0f, 100L, pressure = 1f),
        )
        val dabs = BrushStamps.dynamicDabs(samples, 20f, brush, 42L)

        assertTrue(dabs.size > 2)
        assertEquals(2.5f, dabs.first().radius, 1e-4f)
        assertTrue(dabs.last().radius > dabs.first().radius)
    }

    @Test
    fun `pressure can drive variable arc-length spacing`() {
        val brush = AzphaltBrush(
            name = "Spacing",
            spacing = 0.5f,
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE,
                    parameter = BrushParameter.SPACING,
                    outputMin = 0.5f,
                    outputMax = 2f,
                )
            ),
        )
        val builder = BrushSampleBuilder()
        val samples = listOf(
            builder.add(0f, 0f, 0L, pressure = 0f),
            builder.add(100f, 0f, 100L, pressure = 1f),
        )
        val dynamic = BrushStamps.dynamicDabs(samples, 20f, brush, 42L)
        val fixed = BrushStamps.dabs(
            listOf(0f, 0f, 100f, 0f), 20f, brush.copy(dynamics = emptyList()), 42L,
        )

        // Dynamic spacing starts tighter and opens up as pressure rises; it must not collapse to the
        // static brush's fixed cadence.
        assertNotEquals(fixed.size, dynamic.size)
    }

    @Test
    fun `predicted points are excluded from authoritative dabs`() {
        val brush = AzphaltBrush(name = "No prophecy", spacing = 0.5f)
        val real = listOf(
            BrushSample(0f, 0f, 0L),
            BrushSample(20f, 0f, 20L),
        )
        val withPrediction = real + BrushSample(200f, 0f, 30L, predicted = true)
        assertEquals(
            BrushStamps.dynamicDabs(real, 10f, brush, 1L),
            BrushStamps.dynamicDabs(withPrediction, 10f, brush, 1L),
        )
    }
}
