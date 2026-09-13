package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushReservoirModelTest {

    private fun color(r: Float, g: Float, b: Float, a: Float = 1f) = MaterialColor(r, g, b, a)

    @Test
    fun `zero depletion preserves sanitized initial state`() {
        val initial = BrushReservoirState(
            load = 1.2f,
            wetness = -1f,
            carriedColor = color(2f, -1f, 0.5f, 3f),
        )

        val result = BrushReservoirModel.stateAtDistance(initial, depletionRatePerPx = 0f, distancePx = 500f)

        assertEquals(initial.sanitized(), result)
    }

    @Test
    fun `distance depletion follows the existing exponential charge envelope`() {
        val initial = BrushReservoirState(
            load = 0.8f,
            wetness = 0.4f,
            carriedColor = color(1f, 0f, 0f),
        )
        val rate = 0.05f
        val distance = 24f

        val result = BrushReservoirModel.stateAtDistance(initial, rate, distance)
        val expected = 0.8f * exp(-rate * distance)

        assertTrue(abs(result.load - expected) < 0.000001f, "expected $expected, got ${result.load}")
        assertEquals(initial.wetness, result.wetness)
        assertEquals(initial.carriedColor, result.carriedColor)
    }

    @Test
    fun `negative rate and distance cannot create paint`() {
        val initial = BrushReservoirState(
            load = 0.35f,
            wetness = 0.2f,
            carriedColor = color(0.1f, 0.2f, 0.3f),
        )

        assertEquals(initial, BrushReservoirModel.stateAtDistance(initial, -4f, 100f))
        assertEquals(initial, BrushReservoirModel.stateAtDistance(initial, 4f, -100f))
    }

    @Test
    fun `effective deposition is bounded by current load`() {
        val state = BrushReservoirState(
            load = 0.25f,
            wetness = 0f,
            carriedColor = color(1f, 1f, 1f),
        )

        assertEquals(0.25f, BrushReservoirModel.effectiveDeposition(1f, state))
        assertEquals(0.125f, BrushReservoirModel.effectiveDeposition(0.5f, state))
        assertEquals(0f, BrushReservoirModel.effectiveDeposition(-5f, state))
    }

    @Test
    fun `transfer never overdraws or overfills reservoir`() {
        val initial = BrushReservoirState(
            load = 0.3f,
            wetness = 0.4f,
            carriedColor = color(1f, 0f, 0f),
        )

        val result = BrushReservoirModel.transfer(
            state = initial,
            sampledColor = color(0f, 0f, 1f),
            sampledWetness = 0.8f,
            depositRequest = 5f,
            pickupRequest = 5f,
        )

        assertEquals(0.3f, result.depositedLoad)
        assertEquals(1f, result.pickedUpLoad)
        assertEquals(1f, result.state.load)
        assertEquals(color(0f, 0f, 1f), result.state.carriedColor)
        assertEquals(0.8f, result.state.wetness)
    }

    @Test
    fun `pickup contamination is load weighted`() {
        val initial = BrushReservoirState(
            load = 0.75f,
            wetness = 0.2f,
            carriedColor = color(1f, 0f, 0f),
        )

        val result = BrushReservoirModel.transfer(
            state = initial,
            sampledColor = color(0f, 0f, 1f),
            sampledWetness = 1f,
            depositRequest = 0.25f,
            pickupRequest = 0.25f,
            mixingModel = MaterialMixingModel.LEGACY_RGB,
        )

        // 0.50 retained + 0.25 pickup => pickup contributes one third of carried mass.
        assertEquals(0.75f, result.state.load)
        assertTrue(abs(result.state.carriedColor.red - (2f / 3f)) < 0.000001f)
        assertTrue(abs(result.state.carriedColor.blue - (1f / 3f)) < 0.000001f)
        assertTrue(abs(result.state.wetness - (0.2f * (2f / 3f) + 1f / 3f)) < 0.000001f)
    }

    @Test
    fun `pigment pickup uses the selected material mixer`() {
        val yellow = color(1f, 1f, 0f)
        val blue = color(0f, 0f, 1f)
        val initial = BrushReservoirState(load = 0.5f, wetness = 0f, carriedColor = yellow)

        val result = BrushReservoirModel.transfer(
            state = initial,
            sampledColor = blue,
            depositRequest = 0f,
            pickupRequest = 0.5f,
            mixingModel = MaterialMixingModel.PIGMENT_RYB,
        )

        val mixed = result.state.carriedColor
        assertTrue(mixed.green > mixed.red, "expected green dominance, got $mixed")
        assertTrue(mixed.green > mixed.blue, "expected green dominance, got $mixed")
    }

    @Test
    fun `same transition sequence is deterministic`() {
        fun runSequence(): BrushReservoirState {
            var state = BrushReservoirState(
                load = 1f,
                wetness = 0.25f,
                carriedColor = color(0.9f, 0.2f, 0.1f),
            )
            repeat(8) { index ->
                state = BrushReservoirModel.transfer(
                    state = state,
                    sampledColor = color(0.1f, 0.2f + index * 0.03f, 0.8f),
                    sampledWetness = 0.7f,
                    depositRequest = 0.07f,
                    pickupRequest = 0.03f,
                    mixingModel = MaterialMixingModel.PIGMENT_RYB,
                ).state
            }
            return state
        }

        assertEquals(runSequence(), runSequence())
    }
}
