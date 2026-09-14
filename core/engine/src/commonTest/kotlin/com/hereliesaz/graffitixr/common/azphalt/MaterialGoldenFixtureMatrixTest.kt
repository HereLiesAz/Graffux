package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Consolidated deterministic golden vectors for the material behavior that exists today.
 *
 * This intentionally lives in core/engine and uses no Android/renderer state. It pins the public
 * material contract across the Phase-1 pigment mixer and Phase-2 reservoir before later substrate,
 * persistent-wetness, and Impasto-v2 fixtures are added to the same matrix.
 */
class MaterialGoldenFixtureMatrixTest {

    private fun assertNear(expected: Float, actual: Float, epsilon: Float = 0.00001f) {
        assertTrue(abs(expected - actual) <= epsilon, "expected $expected, got $actual")
    }

    @Test
    fun `pigment fixture yellow plus blue bends through deterministic green`() {
        val yellow = MaterialColor(1f, 1f, 0f, 1f)
        val blue = MaterialColor(0f, 0f, 1f, 1f)

        val mixed = MaterialColorMixer.mix(
            yellow,
            blue,
            ratio = 0.5f,
            model = MaterialMixingModel.PIGMENT_RYB,
        )

        assertNear(0f, mixed.red)
        assertNear(0.5f, mixed.green)
        assertNear(0f, mixed.blue)
        assertNear(1f, mixed.alpha)
    }

    @Test
    fun `long stroke depletion fixture stays on the analytic reservoir envelope`() {
        val initial = BrushReservoirState(
            load = 1f,
            wetness = 0.25f,
            carriedColor = MaterialColor(0.2f, 0.4f, 0.8f, 1f),
        )
        val rate = 0.01f
        val distance = 300f

        val depleted = BrushReservoirModel.stateAtDistance(initial, rate, distance)
        val expectedLoad = exp(-3f)

        assertNear(expectedLoad, depleted.load)
        assertNear(0.25f, depleted.wetness)
        assertEquals(initial.carriedColor, depleted.carriedColor)
        assertNear(expectedLoad * 0.8f, BrushReservoirModel.effectiveDeposition(0.8f, depleted))
    }

    @Test
    fun `wet crossing fixture pins bounded pickup contamination and wetness transfer`() {
        val yellow = MaterialColor(1f, 1f, 0f, 1f)
        val blue = MaterialColor(0f, 0f, 1f, 1f)
        val initial = BrushReservoirState(
            load = 0.4f,
            wetness = 0.2f,
            carriedColor = yellow,
        )

        val crossing = BrushReservoirModel.transfer(
            state = initial,
            sampledColor = blue,
            sampledWetness = 0.8f,
            depositRequest = 0.1f,
            pickupRequest = 0.5f,
            mixingModel = MaterialMixingModel.PIGMENT_RYB,
        )

        assertNear(0.1f, crossing.depositedLoad)
        assertNear(0.5f, crossing.pickedUpLoad)
        assertNear(0.8f, crossing.state.load)
        assertNear(0.575f, crossing.state.wetness)
        assertNear(0f, crossing.state.carriedColor.red)
        assertNear(0.625f, crossing.state.carriedColor.green)
        assertNear(5f / 12f, crossing.state.carriedColor.blue)
        assertNear(1f, crossing.state.carriedColor.alpha)
    }

    @Test
    fun `repeated crossing fixture pins ordered reservoir history`() {
        val yellow = MaterialColor(1f, 1f, 0f, 1f)
        val blue = MaterialColor(0f, 0f, 1f, 1f)
        val red = MaterialColor(1f, 0f, 0f, 1f)
        val initial = BrushReservoirState(0.4f, 0.2f, yellow)

        val first = BrushReservoirModel.transfer(
            state = initial,
            sampledColor = blue,
            sampledWetness = 0.8f,
            depositRequest = 0.1f,
            pickupRequest = 0.5f,
            mixingModel = MaterialMixingModel.PIGMENT_RYB,
        )
        val second = BrushReservoirModel.transfer(
            state = first.state,
            sampledColor = red,
            sampledWetness = 0.1f,
            depositRequest = 0.3f,
            pickupRequest = 0.2f,
            mixingModel = MaterialMixingModel.PIGMENT_RYB,
        )

        assertNear(0.3f, second.depositedLoad)
        assertNear(0.2f, second.pickedUpLoad)
        assertNear(0.7f, second.state.load)
        assertNear(0.43928573f, second.state.wetness)
        assertNear(2f / 7f, second.state.carriedColor.red)
        assertNear(0.26785713f, second.state.carriedColor.green)
        assertNear(0.44642857f, second.state.carriedColor.blue)
        assertNear(1f, second.state.carriedColor.alpha)
    }

    @Test
    fun `legacy dry fixture remains allocation-free and behaviorally neutral`() {
        val profile = PaintMediaProfile.LEGACY_DRY
        val color = MaterialColor(0.7f, 0.2f, 0.1f, 0.9f)
        val reservoir = profile.initialReservoir(color)

        assertTrue(profile.channels.isColorOnly)
        assertTrue(!profile.usesMaterialPath)
        assertNear(1f, reservoir.load)
        assertNear(0f, reservoir.wetness)
        assertEquals(color, reservoir.carriedColor)
    }
}
