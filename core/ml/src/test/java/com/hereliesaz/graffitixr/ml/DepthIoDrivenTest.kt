package com.hereliesaz.graffitixr.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Preprocessing for the three real shapes, size policy, output cropping and polarity. */
class DepthIoDrivenTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun solid(w: Int, h: Int, c: Int) = IntArray(w * h) { c }
    private val imagenet = Normalization.meanStd(listOf(0.485f, 0.456f, 0.406f), listOf(0.229f, 0.224f, 0.225f))

    private fun prep(px: IntArray, w: Int, h: Int, spec: DepthModelSpec): Pair<FloatArray, Letterbox> {
        val box = Letterbox.compute(w, h, spec.size)
        return DepthPipeline.preprocess(px, w, h, spec, box) to box
    }

    // ---- shape 1: fixed 4-D (MiDaS) ----
    @Test fun `fixed 4-D MiDaS keeps unit scaling and a 1x3x256x256 shape`() {
        val spec = DepthModelSpec()
        val (t, box) = prep(solid(64, 32, argb(255, 255, 0, 51)), 64, 32, spec)
        assertArrayEquals(longArrayOf(1, 3, 256, 256), spec.inputShape(box))
        assertEquals(3 * 256 * 256, t.size)
        val plane = 256 * 256
        val mid = 128 * 256 + 128
        assertEquals(1f, t[mid], 1e-5f)
        assertEquals(0f, t[plane + mid], 1e-5f)
        assertEquals(0.2f, t[2 * plane + mid], 1e-5f)
        assertEquals(0.5f, t[0], 1e-5f) // letterbox padding at the fill grey
    }

    // ---- shape 2: dynamic 4-D, multiple of 14 (Depth Anything V2) ----
    private val v2 = DepthModelSpec(size = SizePolicy.Dynamic(14), normalization = imagenet)

    @Test fun `dynamic sizes snap to multiples of 14 under the cap and never upscale`() {
        val big = Letterbox.compute(4000, 3000, v2.size)
        assertEquals(518, big.inputWidth)
        assertEquals(392, big.inputHeight)
        assertEquals(0, big.padX)
        assertEquals(big.inputWidth, big.contentWidth)
        val small = Letterbox.compute(100, 50, v2.size)
        assertEquals(98, small.inputWidth) // 100 -> nearest multiple of 14
        assertEquals(56, small.inputHeight) // 50 -> 56
        val tall = Letterbox.compute(3000, 4000, v2.size)
        assertEquals(392, tall.inputWidth)
        assertEquals(518, tall.inputHeight)
    }

    @Test fun `dynamic size never goes below the safe minimum`() {
        val sliver = Letterbox.compute(10_000, 1, v2.size)
        assertEquals(518, sliver.inputWidth)
        assertEquals(56, sliver.inputHeight)
        val tiny = Letterbox.compute(3, 3, v2.size)
        assertEquals(56, tiny.inputWidth)
        assertTrue(tiny.inputWidth % 14 == 0 && tiny.inputHeight % 14 == 0)
    }

    @Test fun `dynamic 4-D applies ImageNet statistics and is NCHW`() {
        val (t, box) = prep(solid(140, 70, argb(255, 255, 255, 255)), 140, 70, v2)
        assertArrayEquals(longArrayOf(1, 3, 70, 140), v2.inputShape(box))
        assertEquals((1f - 0.485f) / 0.229f, t[0], 1e-4f)
        assertEquals((1f - 0.406f) / 0.225f, t[2 * 70 * 140], 1e-4f)
    }

    @Test fun `dynamic output of 14 floor is cropped proportionally with no padding`() {
        val box = Letterbox.compute(100, 100, v2.size) // 98x98
        val raw = RawDepth(FloatArray(84 * 84) { 1f }, 84, 84)
        val map = DepthPipeline.postprocess(raw, box, v2)
        assertEquals(84, map.width)
        assertEquals(84, map.height)
    }

    // ---- shape 3: 5-D views (Depth Anything V3) ----
    private val v3 = DepthModelSpec(
        rank = 5, size = SizePolicy.Dynamic(14), normalization = imagenet, higherIsNearer = false,
    )

    @Test fun `5-D views input is 1x1x3xHxW with one view`() {
        val (t, box) = prep(solid(140, 70, argb(255, 0, 0, 0)), 140, 70, v3)
        assertArrayEquals(longArrayOf(1, 1, 3, 70, 140), v3.inputShape(box))
        assertEquals(3 * 70 * 140, t.size)
        assertEquals(-0.485f / 0.229f, t[0], 1e-4f)
    }

    @Test fun `NHWC and BGR reorder channels`() {
        val spec = DepthModelSpec(
            size = SizePolicy.Fixed(2, 2), layout = TensorLayout.NHWC, colorOrder = ColorOrder.BGR,
        )
        val (t, box) = prep(solid(2, 2, argb(255, 255, 0, 51)), 2, 2, spec)
        assertArrayEquals(longArrayOf(1, 2, 2, 3), spec.inputShape(box))
        assertEquals(0.2f, t[0], 1e-5f) // B first
        assertEquals(0f, t[1], 1e-5f)
        assertEquals(1f, t[2], 1e-5f) // R last
    }

    // ---- output shapes ----
    @Test fun `output shapes of all three models are accepted`() {
        val v = FloatArray(6)
        for (shape in listOf(longArrayOf(2, 3), longArrayOf(1, 2, 3), longArrayOf(1, 1, 2, 3))) {
            val r = DepthPipeline.rawFromShape(v, shape)
            assertEquals(3, r.width)
            assertEquals(2, r.height)
        }
        assertThrows(DepthEstimationException.UnexpectedOutput::class.java) {
            DepthPipeline.rawFromShape(FloatArray(12), longArrayOf(1, 2, 2, 3)) // two views
        }
        assertThrows(DepthEstimationException.UnexpectedOutput::class.java) {
            DepthPipeline.rawFromShape(FloatArray(5), longArrayOf(1, 2, 3))
        }
    }

    // ---- polarity ----
    @Test fun `V3-like monotone depth becomes nearer-larger`() {
        val depth = FloatArray(10) { 1f + it } // farther to the right
        val spec = v3.copy(size = SizePolicy.Fixed(10, 1))
        val map = DepthPipeline.postprocess(RawDepth(depth, 10, 1), Letterbox.compute(10, 1, spec.size), spec)
        val v = map.values.toList()
        assertTrue(v.zipWithNext().all { (a, b) -> a > b }) // strictly decreasing with distance
        assertTrue(v.all { it.isFinite() && it > 0f })
        val n = map.normalized()
        assertEquals(1f, n[0, 0], 1e-6f) // nearest sample is 1
        assertEquals(0f, n[9, 0], 1e-6f)
    }

    @Test fun `higherIsNearer output is left alone`() {
        val out = DepthPipeline.toNearerLarger(floatArrayOf(1f, 2f, 3f), true, Inversion.RECIPROCAL)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), out, 0f)
    }

    @Test fun `inversion survives zero negative and tiny depths`() {
        val out = DepthPipeline.toNearerLarger(floatArrayOf(0f, -2f, 1e-12f, 10f), false, Inversion.RECIPROCAL)
        assertTrue(out.all { it.isFinite() })
        assertTrue(out[0] > out[3] && out[1] > out[3] && out[2] > out[3])
    }

    @Test fun `non-finite samples become the far plane in both polarities and modes`() {
        val d = floatArrayOf(1f, Float.NaN, 4f, Float.POSITIVE_INFINITY)
        val cases = listOf(false to Inversion.RECIPROCAL, false to Inversion.REFLECT, true to Inversion.RECIPROCAL)
        for ((near, mode) in cases) {
            val out = DepthPipeline.toNearerLarger(d, near, mode)
            assertTrue(out.all { it.isFinite() })
            val far = out.min()
            assertEquals(far, out[1], 0f)
            assertEquals(far, out[3], 0f)
        }
    }

    @Test fun `reflect order-reverses including negative values`() {
        val out = DepthPipeline.toNearerLarger(floatArrayOf(-3f, 0f, 5f), false, Inversion.REFLECT)
        assertArrayEquals(floatArrayOf(5f, 2f, -3f), out, 1e-6f)
    }

    @Test fun `an all-NaN output is refused rather than returned`() {
        assertThrows(DepthEstimationException.UnexpectedOutput::class.java) {
            DepthPipeline.toNearerLarger(FloatArray(4) { Float.NaN }, false, Inversion.RECIPROCAL)
        }
    }

    // ---- float16 ----
    @Test fun `float16 round trips representable values and saturates`() {
        for (f in listOf(0f, 1f, -1f, 0.5f, 2.25f, -0.0009765625f, 65504f, 6.1035156E-5f, 5.9604645E-8f)) {
            assertEquals(f, Float16.toFloat(Float16.toHalfBits(f)), 0f)
        }
        assertEquals(Float.POSITIVE_INFINITY, Float16.toFloat(Float16.toHalfBits(1e9f)), 0f)
        assertTrue(Float16.toFloat(Float16.toHalfBits(Float.NaN)).isNaN())
        assertEquals(1f, Float16.toFloat(Float16.toHalfBits(1.0001f)), 1e-3f)
        assertFalse(Float16.toFloat(Float16.toHalfBits(-3.3f)) > 0f)
    }
}
