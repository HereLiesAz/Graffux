package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hereliesaz.graffitixr.common.azphalt.MaterialMixingModel
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import com.hereliesaz.graffitixr.nativebridge.ColorSmudgeDab
import com.hereliesaz.graffitixr.nativebridge.VulkanStampEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Phase 1 material-paint gate: the exact RYB CPU reference must survive the Kotlin -> JNI -> C++ ->
 * GLSL path on a real Vulkan Android device. Native Color Smudge modes are intentionally ABI-
 * compatible: 0/1 remain legacy Smear/Dulling and 2/3 are pigment Smear/Dulling.
 */
@RunWith(AndroidJUnit4::class)
class VulkanPigmentMixingInstrumentedTest {

    @Test
    fun pigmentSmear_matchesCpuReference() {
        comparePigmentMode(ColorSmudgeEngine.Mode.SMEAR)
    }

    @Test
    fun pigmentDulling_matchesCpuReference() {
        comparePigmentMode(ColorSmudgeEngine.Mode.DULLING)
    }

    private fun comparePigmentMode(mode: ColorSmudgeEngine.Mode) {
        val width = 96
        val height = 64
        val blue = Color.rgb(20, 70, 230)
        val yellow = Color.rgb(245, 225, 20)
        val warmWhite = Color.rgb(242, 236, 218)
        val basePixels = IntArray(width * height) { index ->
            when (index % width) {
                in 0..31 -> blue
                in 32..63 -> yellow
                else -> warmWhite
            }
        }
        val source = Bitmap.createBitmap(basePixels, width, height, Bitmap.Config.ARGB_8888)
        val expected = basePixels.copyOf()
        val legacy = basePixels.copyOf()
        val stroke = List(58) { i ->
            Offset(18f + i, 32f + ((i % 7) - 3) * 0.28f)
        }
        val settings = ColorSmudgeEngine.Settings(
            mode = mode,
            smudgeRate = 0.68f,
            colorRate = 0.42f,
            dilution = 0.48f,
            opacity = 0.86f,
            radiusPx = 8f,
            smudgeRadius = 1.35f,
            feathering = 0.2f,
            smearAlpha = true,
            paintColor = yellow,
            mixingModel = MaterialMixingModel.PIGMENT_RYB,
        )
        ColorSmudgeEngine.apply(expected, width, height, stroke, settings, strokeSeed = 91L)
        ColorSmudgeEngine.apply(
            legacy,
            width,
            height,
            stroke,
            settings.copy(mixingModel = MaterialMixingModel.LEGACY_RGB),
            strokeSeed = 91L,
        )

        assertFalse(
            "pigment fixture must differ from legacy RGB or it cannot detect a missing shader branch",
            expected.contentEquals(legacy),
        )

        val engine = VulkanStampEngine()
        assumeTrue("Vulkan compute unavailable on this device", engine.init(width, height))
        try {
            assertTrue(engine.upload(source))
            val plans = ColorSmudgeEngine.resolvePlans(
                stroke, width, height, settings, strokeSeed = 91L,
            )
            val nativeMode = if (mode == ColorSmudgeEngine.Mode.SMEAR) 2 else 3
            for (plan in plans) {
                if (plan.dabs.size < 2) continue
                assertTrue(
                    engine.colorSmudge(
                        plan.dabs.map {
                            ColorSmudgeDab(
                                it.x,
                                it.y,
                                it.smudgeRate,
                                it.colorRate,
                                it.opacity,
                                it.smudgeRadius,
                            )
                        },
                        nativeMode,
                        settings.radiusPx,
                        settings.feathering,
                        settings.smearAlpha,
                        settings.paintColor,
                        settings.dilution,
                    ),
                )
            }

            val actual = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            assertTrue(engine.readback(actual))
            val actualPixels = IntArray(width * height)
            actual.getPixels(actualPixels, 0, width, 0, 0, width, height)

            var maxDelta = 0
            for (i in expected.indices) {
                val cpu = expected[i]
                val gpu = actualPixels[i]
                maxDelta = maxOf(
                    maxDelta,
                    abs(Color.alpha(cpu) - Color.alpha(gpu)),
                    abs(Color.red(cpu) - Color.red(gpu)),
                    abs(Color.green(cpu) - Color.green(gpu)),
                    abs(Color.blue(cpu) - Color.blue(gpu)),
                )
            }
            assertTrue("pigment CPU/GPU channel delta was $maxDelta", maxDelta <= 3)
        } finally {
            engine.destroy()
            VulkanStampEngine.trimPool()
            source.recycle()
        }
    }
}
