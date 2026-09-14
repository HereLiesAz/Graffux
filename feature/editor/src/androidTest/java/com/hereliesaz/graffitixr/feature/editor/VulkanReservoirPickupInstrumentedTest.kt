package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hereliesaz.graffitixr.common.azphalt.MaterialMixingModel
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import com.hereliesaz.graffitixr.nativebridge.ColorSmudgeDab
import com.hereliesaz.graffitixr.nativebridge.VulkanStampEngine
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VulkanReservoirPickupInstrumentedTest {

    @Test
    fun smear_reservoirPickup_matchesCpuReference() {
        comparePickup(ColorSmudgeEngine.Mode.SMEAR, MaterialMixingModel.LEGACY_RGB)
    }

    @Test
    fun dulling_reservoirPickup_matchesCpuReference() {
        comparePickup(ColorSmudgeEngine.Mode.DULLING, MaterialMixingModel.LEGACY_RGB)
    }

    @Test
    fun smear_pigmentReservoirPickup_matchesCpuReference() {
        comparePickup(ColorSmudgeEngine.Mode.SMEAR, MaterialMixingModel.PIGMENT_RYB)
    }

    private fun comparePickup(
        mode: ColorSmudgeEngine.Mode,
        mixingModel: MaterialMixingModel,
    ) {
        val width = 112
        val height = 72
        val basePixels = IntArray(width * height) { index ->
            val x = index % width
            when {
                x < 32 -> Color.rgb(230, 45, 35)
                x < 62 -> Color.rgb(35, 95, 225)
                x < 88 -> Color.rgb(245, 205, 35)
                else -> Color.rgb(235, 235, 235)
            }
        }
        val source = Bitmap.createBitmap(basePixels, width, height, Bitmap.Config.ARGB_8888)
        val expected = basePixels.copyOf()
        val stroke = List(68) { i -> Offset(18f + i, 36f + (i % 7 - 3) * 0.32f) }
        val settings = ColorSmudgeEngine.Settings(
            mode = mode,
            smudgeRate = 0.58f,
            colorRate = 0.78f,
            chargeDecayRate = 0.045f,
            dilution = 0.22f,
            pickupRate = 0.72f,
            mixingModel = mixingModel,
            opacity = 0.86f,
            radiusPx = 7f,
            smudgeRadius = 1.35f,
            feathering = 0.3f,
            smearAlpha = true,
            paintColor = Color.rgb(30, 210, 85),
        )
        ColorSmudgeEngine.apply(expected, width, height, stroke, settings, strokeSeed = 991L)

        val engine = VulkanStampEngine()
        assumeTrue("Vulkan compute unavailable on this device", engine.init(width, height))
        try {
            assertTrue(engine.upload(source))
            val plans = ColorSmudgeEngine.resolvePlans(
                stroke, width, height, settings, strokeSeed = 991L,
            )
            val baseMode = if (mode == ColorSmudgeEngine.Mode.SMEAR) 0 else 1
            val nativeMode = baseMode + if (mixingModel == MaterialMixingModel.PIGMENT_RYB) 2 else 0

            for (plan in plans) {
                if (plan.dabs.size < 2) continue
                assertTrue(
                    engine.colorSmudge(
                        dabs = plan.dabs.map {
                            ColorSmudgeDab(
                                x = it.x,
                                y = it.y,
                                smudgeRate = it.smudgeRate,
                                colorRate = it.colorRate,
                                opacity = it.opacity,
                                smudgeRadius = it.smudgeRadius,
                                colorRateMultiplier = it.colorRateMultiplier,
                                distanceDeltaPx = it.distanceDeltaPx,
                            )
                        },
                        mode = nativeMode,
                        radiusPx = settings.radiusPx,
                        feathering = settings.feathering,
                        smearAlpha = settings.smearAlpha,
                        paintColorArgb = settings.paintColor,
                        dilution = settings.dilution,
                        baseColorRate = settings.colorRate,
                        chargeDecayRate = settings.chargeDecayRate,
                        pickupRate = settings.pickupRate,
                    )
                )
            }

            val actual = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            assertTrue(engine.readback(actual))
            val actualPixels = IntArray(width * height)
            actual.getPixels(actualPixels, 0, width, 0, 0, width, height)

            var maxDelta = 0
            for (i in expected.indices) {
                val a = expected[i]
                val b = actualPixels[i]
                maxDelta = maxOf(
                    maxDelta,
                    abs(Color.alpha(a) - Color.alpha(b)),
                    abs(Color.red(a) - Color.red(b)),
                    abs(Color.green(a) - Color.green(b)),
                    abs(Color.blue(a) - Color.blue(b)),
                )
            }
            assertTrue(
                "CPU/GPU reservoir channel delta was $maxDelta for $mode/$mixingModel",
                maxDelta <= 2,
            )
        } finally {
            engine.destroy()
            VulkanStampEngine.trimPool()
            source.recycle()
        }
    }
}
