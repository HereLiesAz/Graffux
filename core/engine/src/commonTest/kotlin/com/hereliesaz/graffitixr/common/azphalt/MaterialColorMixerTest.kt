package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaterialColorMixerTest {

    private fun c(r: Int, g: Int, b: Int, a: Int = 255) =
        MaterialColor(r / 255f, g / 255f, b / 255f, a / 255f)

    private fun assertNear(actual: Float, expected: Float, epsilon: Float = 0.0001f) {
        assertTrue(abs(actual - expected) <= epsilon, "expected $expected, got $actual")
    }

    @Test
    fun `ratio endpoints are exact`() {
        val a = c(12, 34, 56, 78)
        val b = c(210, 180, 40, 230)
        assertEquals(a, MaterialColorMixer.mix(a, b, 0f, MaterialMixingModel.PIGMENT_RYB))
        assertEquals(b, MaterialColorMixer.mix(a, b, 1f, MaterialMixingModel.PIGMENT_RYB))
    }

    @Test
    fun `legacy rgb remains ordinary channel interpolation`() {
        val mixed = MaterialColorMixer.mix(c(255, 0, 0), c(0, 0, 255), 0.5f, MaterialMixingModel.LEGACY_RGB)
        assertTrue(abs(mixed.red - 0.5f) < 0.001f)
        assertTrue(mixed.green < 0.001f)
        assertTrue(abs(mixed.blue - 0.5f) < 0.001f)
    }

    @Test
    fun `pigment yellow and blue bend toward green`() {
        val mixed = MaterialColorMixer.mix(c(255, 255, 0), c(0, 0, 255), 0.5f, MaterialMixingModel.PIGMENT_RYB)
        assertTrue(mixed.green > mixed.red, "expected green to exceed red, got $mixed")
        assertTrue(mixed.green > mixed.blue, "expected green to exceed blue, got $mixed")
    }

    @Test
    fun `cyan and red retain chroma instead of crossing neutral rgb gray`() {
        val pigment = MaterialColorMixer.mix(c(0, 255, 255), c(255, 0, 0), 0.5f, MaterialMixingModel.PIGMENT_RYB)
        val legacy = MaterialColorMixer.mix(c(0, 255, 255), c(255, 0, 0), 0.5f, MaterialMixingModel.LEGACY_RGB)

        // Ordinary RGB interpolation lands at neutral 50% gray. The RYB latent path produces the
        // deterministic dark-violet vector (0.5, 0.25, 0.5), retaining a chromatic bias.
        assertNear(pigment.red, 0.5f)
        assertNear(pigment.green, 0.25f)
        assertNear(pigment.blue, 0.5f)
        assertNear(legacy.red, legacy.green)
        assertNear(legacy.green, legacy.blue)
        assertTrue(pigment.green < legacy.green - 0.2f)
    }

    @Test
    fun `high chroma complements do not collapse to the rgb midpoint`() {
        val pigment = MaterialColorMixer.mix(c(255, 0, 255), c(0, 255, 0), 0.5f, MaterialMixingModel.PIGMENT_RYB)
        val legacy = MaterialColorMixer.mix(c(255, 0, 255), c(0, 255, 0), 0.5f, MaterialMixingModel.LEGACY_RGB)

        // The bounded RYB approximation resolves this complement pair toward a blue-violet rather
        // than the neutral 50% gray produced by channel interpolation. This is a golden behavior
        // vector, not a claim that this approximation models named physical pigments.
        assertNear(pigment.red, 0.5f)
        assertNear(pigment.green, 0.5f)
        assertNear(pigment.blue, 1f)
        assertTrue(pigment.blue > legacy.blue + 0.45f)
    }

    @Test
    fun `black white tinting stays neutral`() {
        val tint = MaterialColorMixer.mix(c(0, 0, 0), c(255, 255, 255), 0.5f, MaterialMixingModel.PIGMENT_RYB)
        assertNear(tint.red, 0.5f)
        assertNear(tint.green, 0.5f)
        assertNear(tint.blue, 0.5f)
    }

    @Test
    fun `repeated pigment mixing follows the same latent trajectory`() {
        val yellow = c(255, 255, 0)
        val blue = c(0, 0, 255)
        val first = MaterialColorMixer.mix(yellow, blue, 0.25f, MaterialMixingModel.PIGMENT_RYB)
        val repeated = MaterialColorMixer.mix(first, blue, 0.25f, MaterialMixingModel.PIGMENT_RYB)

        // Two successive 25% moves leave 0.75^2 = 56.25% of the original latent state, equivalent
        // to one 43.75% move toward blue. This catches transforms that are not stable after the
        // intermediate RYB->RGB->RYB round-trip used by a real carried-pigment reservoir.
        val direct = MaterialColorMixer.mix(yellow, blue, 0.4375f, MaterialMixingModel.PIGMENT_RYB)
        assertNear(repeated.red, direct.red)
        assertNear(repeated.green, direct.green)
        assertNear(repeated.blue, direct.blue)
    }

    @Test
    fun `pigment mixing is order symmetric for a fixed ratio complement`() {
        val a = c(245, 205, 35)
        val b = c(30, 80, 225)
        val ab = MaterialColorMixer.mix(a, b, 0.35f, MaterialMixingModel.PIGMENT_RYB)
        val ba = MaterialColorMixer.mix(b, a, 0.65f, MaterialMixingModel.PIGMENT_RYB)
        assertTrue(abs(ab.red - ba.red) < 0.0001f)
        assertTrue(abs(ab.green - ba.green) < 0.0001f)
        assertTrue(abs(ab.blue - ba.blue) < 0.0001f)
        assertTrue(abs(ab.alpha - ba.alpha) < 0.0001f)
    }

    @Test
    fun `preserve alpha leaves destination alpha untouched`() {
        val mixed = MaterialColorMixer.mix(c(255, 0, 0, 64), c(0, 0, 255, 240), 0.75f, MaterialMixingModel.PIGMENT_RYB, includeAlpha = false)
        assertTrue(abs(mixed.alpha - 64f / 255f) < 0.0001f)
    }
}
