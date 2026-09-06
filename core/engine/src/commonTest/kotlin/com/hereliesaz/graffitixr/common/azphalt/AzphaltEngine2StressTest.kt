package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class AzphaltEngine2StressTest {
    @Test
    fun static_generator_remains_linear_over_long_scattered_stroke() {
        val brush = AzphaltBrush(
            name = "stress-static",
            spacing = 0.08f,
            scatter = 0.45f,
            scatterLongitudinal = 0.35f,
            count = 3,
            countJitter = 0.4f,
            sizeJitter = 0.3f,
            opacityJitter = 0.25f,
        )
        val generator = IncrementalStaticDabGenerator(32f, brush, 1234L)
        var emitted = 0
        val samples = 20_000
        repeat(samples) { i ->
            emitted += generator.appendPoint(i * 0.75f, sin(i * 0.03f) * 24f).size
        }
        assertTrue(emitted > 1_000)
        // A prefix-regeneration regression turns this into O(n²) output very quickly.
        assertTrue(emitted < samples * 30, "emitted=$emitted samples=$samples")
    }

    @Test
    fun dynamic_masked_blot_generator_does_not_regenerate_history() {
        val brush = AzphaltBrush(
            name = "stress-dynamic",
            spacing = 0.1f,
            scatter = 0.25f,
            scatterLongitudinal = 0.2f,
            count = 2,
            countJitter = 0.35f,
            taper = BrushTaper(startLengthPx = 160f, endLengthPx = 160f, minSize = 0.1f, minOpacity = 0.1f),
            blot = BrushBlot(
                lengthPx = 90f,
                sizeMultiplier = 1.8f,
                opacityMultiplier = 1.4f,
                extraStamps = 2,
                positionJitter = 0.35f,
            ),
            maskedBrush = MaskedBrushConfig(
                sizeRatio = 0.8f,
                tipRatio = 0.45f,
                scatter = 0.2f,
                scatterLongitudinal = 0.15f,
            ),
        )
        val generator = IncrementalDynamicDabGenerator(36f, brush, 5678L)
        var emitted = 0
        val samples = 15_000
        repeat(samples) { i ->
            emitted += generator.append(
                BrushSample(
                    x = i * 0.8f,
                    y = sin(i * 0.025f) * 30f,
                    uptimeMillis = i.toLong() * 4L,
                    pressure = 0.25f + (i % 100) / 140f,
                )
            ).size
        }
        assertTrue(emitted > 1_000)
        assertTrue(emitted < samples * 40, "emitted=$emitted samples=$samples")
    }

    @Test
    fun held_airbrush_output_tracks_elapsed_time_not_history_length() {
        val brush = AzphaltBrush(
            name = "stress-airbrush",
            spacing = 0.12f,
            airbrushDabsPerSecond = 120f,
            airbrushStillnessRadiusPx = 3f,
        )
        val generator = IncrementalAirbrushGenerator(
            diameterPx = 40f,
            brush = brush,
            dabsPerSecond = brush.airbrushDabsPerSecond,
            stillnessRadiusPx = brush.airbrushStillnessRadiusPx,
            seed = 999L,
        )
        var emitted = 0
        val samples = 12_000
        repeat(samples) { i ->
            emitted += generator.append(
                BrushSample(
                    x = (i % 3) * 0.2f,
                    y = (i % 5) * 0.15f,
                    uptimeMillis = i.toLong() * 4L,
                )
            ).size
        }
        // ~48 seconds * 120 dabs/s. Keep the bound broad but linear.
        assertTrue(emitted in 4_000..8_000, "emitted=$emitted")
    }
}
