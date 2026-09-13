package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.MaterialMixingModel
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class ColorSmudgeReservoirPickupTest {

    private val width = 64
    private val height = 16
    private val stroke = List(52) { Offset((6 + it).toFloat(), 8f) }

    private fun red(argb: Int): Int = argb ushr 16 and 0xFF
    private fun green(argb: Int): Int = argb ushr 8 and 0xFF
    private fun blue(argb: Int): Int = argb and 0xFF

    private fun greenDominance(argb: Int): Int = green(argb) - max(red(argb), blue(argb))

    private fun flat(color: Int): IntArray = IntArray(width * height) { color }

    private fun blueBandSource(): IntArray = IntArray(width * height) { Color.TRANSPARENT }.also { pixels ->
        for (y in 0 until height) {
            for (x in 24..31) {
                pixels[y * width + x] = Color.BLUE
            }
        }
    }

    private fun pickupSettings(): ColorSmudgeEngine.Settings = ColorSmudgeEngine.Settings(
        mode = ColorSmudgeEngine.Mode.DULLING,
        radiusPx = 2f,
        smudgeRate = 0f,
        colorRate = 1f,
        chargeDecayRate = 0.045f,
        dilution = 0f,
        pickupRate = 0.4f,
        mixingModel = MaterialMixingModel.PIGMENT_RYB,
        opacity = 1f,
        paintColor = Color.YELLOW,
        sampleMerged = true,
    )

    @Test
    fun `pickup default zero remains byte-identical to explicit zero`() {
        val implicit = ColorSmudgeEngine.Settings(
            mode = ColorSmudgeEngine.Mode.DULLING,
            radiusPx = 3f,
            smudgeRate = 0.2f,
            colorRate = 0.7f,
            chargeDecayRate = 0.03f,
            dilution = 0.15f,
            mixingModel = MaterialMixingModel.PIGMENT_RYB,
            paintColor = Color.YELLOW,
        )
        val explicit = implicit.copy(pickupRate = 0f)
        val a = flat(Color.WHITE)
        val b = flat(Color.WHITE)

        ColorSmudgeEngine.apply(a, width, height, stroke, implicit, strokeSeed = 99L)
        ColorSmudgeEngine.apply(b, width, height, stroke, explicit, strokeSeed = 99L)

        assertArrayEquals(a, b)
    }

    @Test
    fun `crossing blue material contaminates later yellow pigment toward green`() {
        val source = blueBandSource()
        val withPickup = flat(Color.WHITE)
        val withoutPickup = flat(Color.WHITE)
        val settings = pickupSettings()

        ColorSmudgeEngine.apply(
            withPickup, width, height, stroke, settings,
            strokeSeed = 17L, sampleSource = source,
        )
        ColorSmudgeEngine.apply(
            withoutPickup, width, height, stroke, settings.copy(pickupRate = 0f),
            strokeSeed = 17L, sampleSource = source,
        )

        val lateIndex = 8 * width + 46
        val contaminated = withPickup[lateIndex]
        val clean = withoutPickup[lateIndex]

        assertTrue("pickup should change paint after crossing sampled material", contaminated != clean)
        assertTrue(
            "yellow + blue reservoir contamination should increase green dominance after the crossing",
            greenDominance(contaminated) > greenDominance(clean),
        )
    }

    @Test
    fun `sample merged reservoir pickup reads the supplied composite`() {
        val settings = pickupSettings()
        val transparentSource = IntArray(width * height) { Color.TRANSPARENT }
        val blueSource = blueBandSource()
        val noMaterial = flat(Color.WHITE)
        val blueMaterial = flat(Color.WHITE)

        ColorSmudgeEngine.apply(
            noMaterial, width, height, stroke, settings,
            strokeSeed = 23L, sampleSource = transparentSource,
        )
        ColorSmudgeEngine.apply(
            blueMaterial, width, height, stroke, settings,
            strokeSeed = 23L, sampleSource = blueSource,
        )

        val lateIndex = 8 * width + 46
        assertTrue(
            "opaque material in the merged sample should contaminate later paint",
            blueMaterial[lateIndex] != noMaterial[lateIndex],
        )
        assertTrue(
            "blue merged material should bend yellow reservoir pigment toward green",
            greenDominance(blueMaterial[lateIndex]) > greenDominance(noMaterial[lateIndex]),
        )
    }

    @Test
    fun `reservoir pickup is deterministic and forces CPU until Vulkan parity exists`() {
        val settings = pickupSettings().copy(pickupRate = 0.65f)
        val source = blueBandSource()
        val a = flat(Color.WHITE)
        val b = flat(Color.WHITE)

        ColorSmudgeEngine.apply(a, width, height, stroke, settings, strokeSeed = 777L, sampleSource = source)
        ColorSmudgeEngine.apply(b, width, height, stroke, settings, strokeSeed = 777L, sampleSource = source)

        assertArrayEquals(a, b)
        assertTrue(ColorSmudgeEngine.requiresCpuReservoirSimulation(settings))
        assertFalse(ColorSmudgeEngine.requiresCpuReservoirSimulation(settings.copy(pickupRate = 0f)))
        assertFalse(ColorSmudgeEngine.requiresCpuReservoirSimulation(settings.copy(pickupRate = -1f)))
    }
}
