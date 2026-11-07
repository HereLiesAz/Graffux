package com.hereliesaz.graffitixr.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthPipelineTest {
    private val spec = DepthModelSpec(inputSize = 8)

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun solid(w: Int, h: Int, color: Int) = IntArray(w * h) { color }

    /** Channel value at (x,y) of the CHW tensor. */
    private fun FloatArray.at(c: Int, x: Int, y: Int, size: Int = 8) = this[c * size * size + y * size + x]

    @Test fun `letterbox keeps aspect and centres a wide image`() {
        val box = Letterbox.compute(400, 100, 256)
        assertEquals(256, box.contentWidth)
        assertEquals(64, box.contentHeight)
        assertEquals(0, box.padX)
        assertEquals(96, box.padY)
    }

    @Test fun `letterbox keeps aspect for a tall image and a square one`() {
        val tall = Letterbox.compute(100, 400, 256)
        assertEquals(64, tall.contentWidth)
        assertEquals(96, tall.padX)
        val sq = Letterbox.compute(1000, 1000, 256)
        assertEquals(256, sq.contentWidth)
        assertEquals(0, sq.padY)
    }

    @Test fun `letterbox never collapses a sliver to zero`() {
        val box = Letterbox.compute(10_000, 1, 256)
        assertEquals(1, box.contentHeight)
    }

    @Test fun `tensor is CHW RGB scaled to 0 1`() {
        val t = DepthPipeline.preprocess(solid(8, 8, argb(255, 255, 0, 51)), 8, 8, spec)
        assertEquals(3 * 64, t.size)
        assertEquals(1f, t.at(0, 3, 3), 1e-5f)
        assertEquals(0f, t.at(1, 3, 3), 1e-5f)
        assertEquals(0.2f, t.at(2, 3, 3), 1e-5f)
    }

    @Test fun `padding is the fill colour and the image area is not`() {
        // 8x2 white image in an 8x8 input: rows 3..4 are image, the rest padding at 0.5.
        val t = DepthPipeline.preprocess(solid(8, 2, argb(255, 255, 255, 255)), 8, 2, spec)
        assertEquals(0.5f, t.at(0, 0, 0), 1e-5f)
        assertEquals(0.5f, t.at(1, 7, 7), 1e-5f)
        assertEquals(1f, t.at(0, 0, 3), 1e-5f)
        assertEquals(1f, t.at(2, 7, 4), 1e-5f)
    }

    @Test fun `transparent pixels become the fill colour so alpha never reaches the model`() {
        val t = DepthPipeline.preprocess(solid(8, 8, argb(0, 255, 0, 0)), 8, 8, spec)
        assertEquals(0.5f, t.at(0, 2, 2), 1e-5f)
        assertEquals(0.5f, t.at(1, 2, 2), 1e-5f)
    }

    @Test fun `half transparent pixels blend with the fill`() {
        val t = DepthPipeline.preprocess(solid(8, 8, argb(128, 255, 255, 255)), 8, 8, spec)
        val a = 128 / 255f
        assertEquals(a + 0.5f * (1 - a), t.at(0, 4, 4), 1e-4f)
    }

    @Test fun `transparent neighbours do not bleed their colour into opaque ones when downscaling`() {
        // Left half opaque green, right half transparent red; shrink 8x1 -> 4x1 content.
        val px = IntArray(16 * 1) { x -> if (x < 8) argb(255, 0, 255, 0) else argb(0, 255, 0, 0) }
        val t = DepthPipeline.preprocess(px, 16, 1, DepthModelSpec(inputSize = 4))
        // content is 4x1 at padY 1; pixel 0 covers only opaque green.
        assertEquals(0f, t.at(0, 0, 1, 4), 1e-5f)
        assertEquals(1f, t.at(1, 0, 1, 4), 1e-5f)
        // Pixel 3 covers only transparent red -> pure fill.
        assertEquals(0.5f, t.at(0, 3, 1, 4), 1e-5f)
        assertEquals(0.5f, t.at(1, 3, 1, 4), 1e-5f)
    }

    @Test fun `mean and std are applied after scaling`() {
        val s = DepthModelSpec(
            inputSize = 8, mean = floatArrayOf(0.5f, 0.5f, 0.5f), std = floatArrayOf(0.25f, 0.25f, 0.25f),
        )
        val t = DepthPipeline.preprocess(solid(8, 8, argb(255, 255, 255, 255)), 8, 8, s)
        assertEquals(2f, t.at(1, 1, 1), 1e-5f)
    }

    @Test fun `downscale averages the source footprint`() {
        // 16x16 checker of black/white -> mean grey after reduction to 8x8.
        val px = IntArray(256) { i -> if ((i % 16 + i / 16) % 2 == 0) argb(255, 0, 0, 0) else argb(255, 255, 255, 255) }
        val t = DepthPipeline.preprocess(px, 16, 16, spec)
        assertEquals(0.5f, t.at(0, 3, 3), 1e-5f)
    }

    @Test fun `upscale is smooth not blocky`() {
        val px = intArrayOf(argb(255, 0, 0, 0), argb(255, 255, 255, 255))
        val t = DepthPipeline.preprocess(px, 2, 1, DepthModelSpec(inputSize = 8))
        // 2x1 -> 8x4 content; left edge black, right edge white, strictly increasing between.
        val row = (0 until 8).map { t.at(0, it, 4 - 1 + 0, 8) }
        assertTrue(row.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(row.first() < 0.05f && row.last() > 0.95f)
    }

    @Test fun `postprocess removes padding and returns the source aspect`() {
        val size = 8
        val out = FloatArray(size * size) { (it / size).toFloat() } // value = row index
        val box = Letterbox.compute(8, 2, size)
        val map = DepthPipeline.postprocess(out, box)
        assertEquals(8, map.width)
        assertEquals(2, map.height)
        assertEquals(3f, map[0, 0], 0f)
        assertEquals(4f, map[7, 1], 0f)
    }

    @Test fun `postprocess rejects a wrong sized output`() {
        val box = Letterbox.compute(8, 8, 8)
        assertThrows(DepthEstimationException.UnexpectedOutput::class.java) {
            DepthPipeline.postprocess(FloatArray(63), box)
        }
    }

    @Test fun `end to end with a fake runner maps the output back and resamples to layer size`() {
        // Runner returns a left-to-right ramp, so the map's x gradient survives the round trip.
        val runner = DepthModelRunner { input, s ->
            assertEquals(s.tensorSize, input.size)
            FloatArray(s.inputSize * s.inputSize) { (it % s.inputSize).toFloat() }
        }
        val map = DepthPipeline.estimate(solid(16, 4, argb(255, 9, 9, 9)), 16, 4, spec, runner)
        assertEquals(8, map.width)
        assertEquals(2, map.height)
        val layer = map.normalized().resampledTo(160, 40)
        assertEquals(160, layer.width)
        assertTrue(layer[0, 0] < 0.1f && layer[159, 39] > 0.9f)
    }

    @Test fun `runner failure propagates`() {
        val runner = DepthModelRunner { _, _ -> throw DepthEstimationException.InferenceFailed("boom") }
        assertThrows(DepthEstimationException.InferenceFailed::class.java) {
            DepthPipeline.estimate(solid(8, 8, 0), 8, 8, spec, runner)
        }
    }

    @Test fun `spec rejects bad statistics`() {
        assertThrows(IllegalArgumentException::class.java) { DepthModelSpec(std = floatArrayOf(1f, 0f, 1f)) }
        assertThrows(IllegalArgumentException::class.java) { DepthModelSpec(mean = floatArrayOf(0f)) }
    }
}
