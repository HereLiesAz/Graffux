package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import com.hereliesaz.graffitixr.nativebridge.ColorSmudgeDab
import com.hereliesaz.graffitixr.nativebridge.VulkanStampEngine
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Runs only on an actual ARM Android device. It is both the CPU/GPU visible-parity gate and the
 * hardware strategy probe: the first native Color Smudge call times 8x8 and 16x16 workgroups on
 * that device's Vulkan driver and caches the winner by vendor/device ID.
 */
@RunWith(AndroidJUnit4::class)
class VulkanColorSmudgeInstrumentedTest {

    @Test
    fun smear_matchesCpuReference_andBenchmarksDevice() {
        compareMode(ColorSmudgeEngine.Mode.SMEAR, colorRate = 0.2f)
    }

    @Test
    fun dulling_matchesCpuReference_withColorRate() {
        compareMode(ColorSmudgeEngine.Mode.DULLING, colorRate = 0.35f)
    }

    private fun compareMode(mode: ColorSmudgeEngine.Mode, colorRate: Float) {
        val width = 96
        val height = 64
        val basePixels = IntArray(width * height) { index ->
            val x = index % width
            when {
                x < 28 -> Color.rgb(230, 30, 40)
                x < 56 -> Color.rgb(35, 85, 220)
                else -> Color.rgb(240, 240, 240)
            }
        }
        val source = Bitmap.createBitmap(basePixels, width, height, Bitmap.Config.ARGB_8888)
        val expected = basePixels.copyOf()
        val stroke = List(52) { i -> Offset(20f + i, 32f + (i % 5 - 2) * 0.35f) }
        val settings = ColorSmudgeEngine.Settings(
            mode = mode,
            smudgeRate = 0.72f,
            colorRate = colorRate,
            opacity = 0.83f,
            radiusPx = 7f,
            smudgeRadius = 1.4f,
            feathering = 0.25f,
            smearAlpha = true,
            paintColor = Color.rgb(20, 210, 90),
        )
        ColorSmudgeEngine.apply(expected, width, height, stroke, settings, strokeSeed = 77L)

        val engine = VulkanStampEngine()
        assumeTrue("Vulkan compute unavailable on this device", engine.init(width, height))
        try {
            assertTrue(engine.upload(source))
            val plans = ColorSmudgeEngine.resolvePlans(
                stroke, width, height, settings, strokeSeed = 77L,
            )
            val nativeMode = if (mode == ColorSmudgeEngine.Mode.SMEAR) 0 else 1
            for (plan in plans) {
                if (plan.dabs.size < 2) continue
                assertTrue(
                    engine.colorSmudge(
                        plan.dabs.map {
                            ColorSmudgeDab(
                                it.x, it.y, it.smudgeRate, it.colorRate, it.opacity, it.smudgeRadius,
                            )
                        },
                        nativeMode,
                        settings.radiusPx,
                        settings.feathering,
                        settings.smearAlpha,
                        settings.paintColor,
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
            assertTrue("CPU/GPU channel delta was $maxDelta", maxDelta <= 2)

            val benchmark = engine.colorSmudgeBenchmarkInfo()
            assertTrue("benchmark result missing", benchmark != null)
            benchmark!!
            assertTrue(benchmark.selectedTileSize == 8 || benchmark.selectedTileSize == 16)
            assertTrue(benchmark.nanos8 > 0L)
            assertTrue(benchmark.nanos16 > 0L)
            android.util.Log.i(
                "VulkanColorSmudgeTest",
                "vendor=0x${benchmark.vendorId.toUInt().toString(16)} " +
                    "device=0x${benchmark.deviceId.toUInt().toString(16)} " +
                    "tile=${benchmark.selectedTileSize} ns8=${benchmark.nanos8} ns16=${benchmark.nanos16}",
            )
        } finally {
            engine.destroy()
            VulkanStampEngine.trimPool()
            source.recycle()
        }
    }
}
