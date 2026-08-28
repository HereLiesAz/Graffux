package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconciliation coverage: Procreate's Wet Mix (Charge decay, Dilution) is not a second engine —
 * it's a generalization of [ColorSmudgeEngine]'s existing Krita-shaped Color Rate, expressed as
 * two new [ColorSmudgeEngine.Settings] fields that default to the historical flat-rate behaviour.
 * See the class doc on [ColorSmudgeEngine] for the full parameter-mapping rationale.
 */
class ColorSmudgeWetMixTest {

    // Plain JVM Android tests use the mockable android.jar, whose Color.red()/green()/blue() are
    // stubs; read packed ARGB channels directly. Color.RED/WHITE/BLUE are compile-time constants
    // and safe to use as fill values.
    private fun blue(argb: Int): Int = argb and 0xFF
    private fun red(argb: Int): Int = argb ushr 16 and 0xFF

    private fun flat(width: Int, height: Int, color: Int): IntArray = IntArray(width * height) { color }

    private val w = 64
    private val h = 16
    private val stroke = List(48) { Offset((8 + it).toFloat(), 8f) }

    @Test
    fun `default chargeDecayRate and dilution reproduce flat Color Rate exactly`() {
        val settingsFlat = ColorSmudgeEngine.Settings(radiusPx = 5f, smudgeRate = 0f, colorRate = 1f, paintColor = Color.BLUE)
        val settingsExplicitDefaults = settingsFlat.copy(chargeDecayRate = 0f, dilution = 0f)
        val a = flat(w, h, Color.WHITE)
        val b = flat(w, h, Color.WHITE)
        ColorSmudgeEngine.apply(a, w, h, stroke, settingsFlat)
        ColorSmudgeEngine.apply(b, w, h, stroke, settingsExplicitDefaults)
        assertArrayEquals(a, b)
    }

    @Test
    fun `charge decay deposits less pigment as the stroke travels`() {
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = 5f, smudgeRate = 0f, colorRate = 1f, chargeDecayRate = 0.05f, paintColor = Color.BLUE,
        )
        val pixels = flat(w, h, Color.WHITE)
        ColorSmudgeEngine.apply(pixels, w, h, stroke, settings)

        val early = pixels[8 * w + 10]
        val late = pixels[8 * w + 52]
        // WHITE's blue channel is already 255 (same as BLUE paint), so blue can't show a
        // difference here — red is the discriminating channel: fully blue-tinted suppresses it,
        // an untouched-white pixel keeps it at 255.
        assertTrue(
            "expected the early dab to have displaced more red (i.e. more blue pigment) than the late one",
            red(early) < red(late),
        )
    }

    @Test
    fun `depleted charge settles into a pure smudge, matching Procreate's dry-brush end state`() {
        // High decay rate: charge is effectively zero well before the stroke ends, so late dabs
        // should deposit no new pigment at all — the canvas there stays exactly what it was.
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = 5f, smudgeRate = 0f, colorRate = 1f, chargeDecayRate = 5f, paintColor = Color.BLUE,
        )
        val pixels = flat(w, h, Color.WHITE)
        ColorSmudgeEngine.apply(pixels, w, h, stroke, settings)

        val late = pixels[8 * w + 52]
        assertEquals("depleted charge must not tint an untouched pixel", Color.WHITE, late)
    }

    @Test
    fun `resolvePlans exposes the same charge decay CPU raster consumes, for GPU parity`() {
        val settings = ColorSmudgeEngine.Settings(
            radiusPx = 5f, colorRate = 1f, chargeDecayRate = 0.05f,
        )
        val plans = ColorSmudgeEngine.resolvePlans(stroke, w, h, settings)
        val rates = plans.single().dabs.map { it.colorRate }
        assertTrue("expected colorRate to decay, not stay flat", rates.first() > rates.last())
        for (i in 1 until rates.size) {
            assertTrue("expected monotonically non-increasing colorRate along the stroke", rates[i] <= rates[i - 1])
        }
    }

    @Test
    fun `dilution mixes deposited pigment toward the colour already under the brush`() {
        val pureSettings = ColorSmudgeEngine.Settings(
            radiusPx = 5f, smudgeRate = 0f, colorRate = 1f, dilution = 0f, paintColor = Color.BLUE,
        )
        val dilutedSettings = pureSettings.copy(dilution = 1f)
        val pure = flat(w, h, Color.RED)
        val diluted = flat(w, h, Color.RED)
        ColorSmudgeEngine.apply(pure, w, h, stroke, pureSettings)
        ColorSmudgeEngine.apply(diluted, w, h, stroke, dilutedSettings)

        val pureAt = pure[8 * w + 30]
        val dilutedAt = diluted[8 * w + 30]
        assertTrue("dilution=0 should deposit strong blue pigment", blue(pureAt) > blue(dilutedAt))
        assertTrue("dilution=1 should barely disturb the existing red canvas", red(dilutedAt) > red(pureAt))
    }

    @Test
    fun `dilution=0 is byte-identical to omitting it`() {
        val settingsA = ColorSmudgeEngine.Settings(radiusPx = 5f, colorRate = 0.6f, paintColor = Color.BLUE)
        val settingsB = settingsA.copy(dilution = 0f)
        val a = flat(w, h, Color.RED)
        val b = flat(w, h, Color.RED)
        ColorSmudgeEngine.apply(a, w, h, stroke, settingsA)
        ColorSmudgeEngine.apply(b, w, h, stroke, settingsB)
        assertArrayEquals(a, b)
    }
}
