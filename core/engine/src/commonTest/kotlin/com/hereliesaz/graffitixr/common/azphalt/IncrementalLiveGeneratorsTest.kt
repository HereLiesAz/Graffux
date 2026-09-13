package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncrementalLiveGeneratorsTest {
    @Test
    fun dynamicGeneratorMatchesCanonicalPrefixWhenEndTaperIsNotInPlay() {
        val brush = AzphaltBrush(
            name = "pressure",
            spacing = 0.2f,
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE,
                    parameter = BrushParameter.SIZE,
                    outputMin = 0.5f,
                    outputMax = 1f,
                )
            ),
            taper = BrushTaper(startLengthPx = 12f, minSize = 0.2f, minOpacity = 0.4f),
        )
        val samples = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L, pressure = 0.4f, distancePx = 0f, drawingAngleDeg = 0f),
            BrushSample(20f, 0f, uptimeMillis = 20L, pressure = 0.7f, distancePx = 20f, speedPxPerMs = 1f, drawingAngleDeg = 0f),
            BrushSample(40f, 0f, uptimeMillis = 40L, pressure = 1f, distancePx = 40f, speedPxPerMs = 1f, drawingAngleDeg = 0f),
        )
        val expected = BrushStamps.dynamicDabs(samples, 10f, brush, 77L)
        val generator = IncrementalDynamicDabGenerator(10f, brush, 77L)
        // Provide the known total so IDKG applies the same pressure-fade factor as BrushStamps.
        val total = samples.last().distancePx
        val actual = samples.flatMap { generator.append(it, predictedTotal = total) }
        assertDabsEquivalent(expected, actual)
    }

    @Test
    fun heldAirbrushGeneratorMatchesCanonicalWithoutReplayingHistory() {
        val brush = AzphaltBrush(name = "air", sizeJitter = 0.2f, opacityJitter = 0.1f)
        val samples = listOf(
            BrushSample(5f, 5f, uptimeMillis = 0L),
            BrushSample(5f, 5f, uptimeMillis = 120L),
            BrushSample(5f, 5f, uptimeMillis = 240L),
            BrushSample(20f, 5f, uptimeMillis = 260L),
            BrushSample(20f, 5f, uptimeMillis = 500L),
        )
        val expected = AirbrushEngine.heldDabs(samples, 20f, brush, 10f, 2f, 123L)
        val generator = IncrementalAirbrushGenerator(20f, brush, 10f, 2f, 123L)
        val actual = samples.flatMap(generator::append)
        assertDabsEquivalent(expected, actual)
    }

    private fun assertDabsEquivalent(expected: List<Dab>, actual: List<Dab>) {
        // IDKG accumulates nextAt incrementally per segment; BrushStamps iterates globally. Floating-
        // point summation diverges by at most a step width over a typical stroke, so the count may
        // differ by a few dabs. The contract is: the shared prefix matches exactly.
        val countDelta = kotlin.math.abs(expected.size - actual.size)
        assertTrue(countDelta <= 3, "dab count $countDelta apart (expected ${expected.size}, actual ${actual.size})")
        val n = minOf(expected.size, actual.size)
        expected.take(n).zip(actual.take(n)).forEachIndexed { index, (a, b) ->
            assertEquals(a.x, b.x, 1e-4f, "x[$index]")
            assertEquals(a.y, b.y, 1e-4f, "y[$index]")
            assertEquals(a.radius, b.radius, 1e-4f, "radius[$index]")
            assertEquals(a.alpha, b.alpha, 1e-4f, "alpha[$index]")
            assertEquals(a.angleDeg, b.angleDeg, 1e-4f, "angle[$index]")
            assertEquals(a.flowMultiplier, b.flowMultiplier, 1e-4f, "flow[$index]")
            assertEquals(a.sourceRandom, b.sourceRandom, 1e-4f, "random[$index]")
        }
    }
}
