package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaterialColorMixerTest {

    private fun c(r: Int, g: Int, b: Int, a: Int = 255) =
        MaterialColor(r / 255f, g / 255f, b / 255f, a / 255f)

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
