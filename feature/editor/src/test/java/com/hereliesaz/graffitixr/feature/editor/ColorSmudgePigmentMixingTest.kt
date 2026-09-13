package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.MaterialMixingModel
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSmudgePigmentMixingTest {

    private fun red(argb: Int): Int = argb ushr 16 and 0xFF
    private fun green(argb: Int): Int = argb ushr 8 and 0xFF
    private fun blue(argb: Int): Int = argb and 0xFF

    private val w = 24
    private val h = 16
    private val stroke = listOf(Offset(8f, 8f), Offset(14f, 8f))
    private val opaqueBlue = 0xFF0000FF.toInt()
    private val opaqueYellow = 0xFFFFFF00.toInt()

    @Test
    fun `default mixing model is byte-identical to explicit legacy rgb`() {
        val defaults = ColorSmudgeEngine.Settings(
            mode = ColorSmudgeEngine.Mode.DULLING,
            radiusPx = 4f,
            smudgeRate = 0.4f,
            colorRate = 0.65f,
            dilution = 0.45f,
            paintColor = opaqueYellow,
        )
        val explicitLegacy = defaults.copy(mixingModel = MaterialMixingModel.LEGACY_RGB)
        val a = IntArray(w * h) { opaqueBlue }
        val b = IntArray(w * h) { opaqueBlue }

        ColorSmudgeEngine.apply(a, w, h, stroke, defaults)
        ColorSmudgeEngine.apply(b, w, h, stroke, explicitLegacy)

        assertArrayEquals(a, b)
    }

    @Test
    fun `pigment dilution of yellow into blue produces green dominant paint`() {
        val settings = ColorSmudgeEngine.Settings(
            mode = ColorSmudgeEngine.Mode.DULLING,
            radiusPx = 4f,
            smudgeRate = 0f,
            colorRate = 1f,
            dilution = 0.5f,
            opacity = 1f,
            paintColor = opaqueYellow,
            mixingModel = MaterialMixingModel.PIGMENT_RYB,
        )
        val pixels = IntArray(w * h) { opaqueBlue }

        ColorSmudgeEngine.apply(pixels, w, h, stroke, settings)

        val mixed = pixels[8 * w + 12]
        assertTrue(
            "pigment yellow/blue interaction should be green-dominant, got #${mixed.toUInt().toString(16)}",
            green(mixed) > red(mixed) && green(mixed) > blue(mixed),
        )
    }

    @Test
    fun `pigment mode visibly differs from legacy rgb for yellow blue mixing`() {
        val base = ColorSmudgeEngine.Settings(
            mode = ColorSmudgeEngine.Mode.DULLING,
            radiusPx = 4f,
            smudgeRate = 0f,
            colorRate = 1f,
            dilution = 0.5f,
            opacity = 1f,
            paintColor = opaqueYellow,
        )
        val legacy = IntArray(w * h) { opaqueBlue }
        val pigment = IntArray(w * h) { opaqueBlue }

        ColorSmudgeEngine.apply(legacy, w, h, stroke, base.copy(mixingModel = MaterialMixingModel.LEGACY_RGB))
        ColorSmudgeEngine.apply(pigment, w, h, stroke, base.copy(mixingModel = MaterialMixingModel.PIGMENT_RYB))

        val index = 8 * w + 12
        assertTrue("material mode must not collapse to legacy RGB", legacy[index] != pigment[index])
        assertTrue("pigment result should contain more green than legacy", green(pigment[index]) > green(legacy[index]))
    }
}
