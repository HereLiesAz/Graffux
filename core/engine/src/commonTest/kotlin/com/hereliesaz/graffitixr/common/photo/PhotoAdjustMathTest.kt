package com.hereliesaz.graffitixr.common.photo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The adjustment formulas against values worked out by hand (the expected numbers come from the
 * written-out formula in a calculator, never from the code under test).
 */
class PhotoAdjustMathTest {

    private fun near(expected: Float, actual: Float, tol: Float = 1e-4f) =
        assertTrue(kotlin.math.abs(expected - actual) <= tol, "expected $expected, was $actual")

    @Test
    fun `exposure doubles linear light per stop`() {
        // sRGB 0.5 is linear 0.21404; doubled 0.42809, back to sRGB 0.68584.
        near(0.68584f, PhotoAdjustMath.exposure(0.5f, 1f))
        // Halved: linear 0.10702, sRGB 0.36078.
        near(0.36078f, PhotoAdjustMath.exposure(0.5f, -1f))
        near(0.5f, PhotoAdjustMath.exposure(0.5f, 0f))
        // Clips at white rather than overflowing.
        near(1f, PhotoAdjustMath.exposure(0.9f, 5f))
    }

    @Test
    fun `the tone curve at its reference points`() {
        // Neutral: identity.
        near(0.3f, PhotoAdjustMath.tone(0.3f, 0f, 0f, 0f, 0f, 0f))
        // Contrast +100 is a smoothstep: 0.25 -> 0.15625; +50 is halfway: 0.203125.
        near(0.15625f, PhotoAdjustMath.tone(0.25f, 1f, 0f, 0f, 0f, 0f))
        near(0.203125f, PhotoAdjustMath.tone(0.25f, 0.5f, 0f, 0f, 0f, 0f))
        // Shadows +100 lifts 1/3 by 0.9 * (1/3)(2/3)^2: 0.46667. Highlights -100 pulls 2/3 to 0.53333.
        near(0.46667f, PhotoAdjustMath.tone(1f / 3f, 0f, 0f, 1f, 0f, 0f))
        near(0.53333f, PhotoAdjustMath.tone(2f / 3f, 0f, -1f, 0f, 0f, 0f))
        // Blacks -100 moves the black point up 15%: 0.2 -> (0.2 - 0.15) / 0.85 = 0.05882.
        near(0.05882f, PhotoAdjustMath.tone(0.2f, 0f, 0f, 0f, 0f, -1f))
        // End points stay put under contrast and shadows/highlights.
        near(0f, PhotoAdjustMath.tone(0f, 1f, 1f, 1f, 0f, 0f))
        near(1f, PhotoAdjustMath.tone(1f, 1f, -1f, -1f, 0f, 0f))
    }

    @Test
    fun `the tone curve never folds back`() {
        for (s in listOf(-1f, 1f)) for (hl in listOf(-1f, 1f)) for (ct in listOf(-1f, 1f)) {
            var last = -1f
            for (i in 0..100) {
                val y = PhotoAdjustMath.tone(i / 100f, ct, hl, s, 0f, 0f)
                assertTrue(y >= last - 1e-6f, "folds at ${i / 100f} for s=$s h=$hl c=$ct")
                last = y
            }
        }
    }

    @Test
    fun `levels stretch and bend the midtones`() {
        // ((0.5 - 12/255) / (241/255 - 12/255)) ^ (1 / 1.1) = 0.53675.
        near(0.53675f, PhotoAdjustMath.levels(0.5f, 12f / 255f, 1.1f, 241f / 255f))
        near(0f, PhotoAdjustMath.levels(0.04f, 12f / 255f, 1f, 1f))
        near(1f, PhotoAdjustMath.levels(0.96f, 0f, 1f, 241f / 255f))
    }

    @Test
    fun `white balance is neutral at 6500 K and warms above it`() {
        val n = PhotoAdjustParams.whiteBalance(6500f, 0f)
        n.forEach { near(1f, it, 1e-5f) }
        val warm = PhotoAdjustParams.whiteBalance(9000f, 0f)
        assertTrue(warm[0] > 1f && warm[2] < 1f, "warmer: ${warm.toList()}")
        val cool = PhotoAdjustParams.whiteBalance(4000f, 0f)
        assertTrue(cool[0] < 1f && cool[2] > 1f, "cooler: ${cool.toList()}")
        // Luminance is kept: 0.2126 r + 0.7152 g + 0.0722 b = 1.
        near(1f, 0.2126f * warm[0] + 0.7152f * warm[1] + 0.0722f * warm[2], 1e-5f)
        // Tint +100 (magenta) takes green down.
        val magenta = PhotoAdjustParams.whiteBalance(6500f, 100f)
        assertTrue(magenta[1] < 1f && magenta[0] > 1f)
    }

    @Test
    fun `hsl ranges cover the wheel and peak at their centres`() {
        // Pure orange (1, 0.5, 0) is hue 30: all orange, no red or yellow.
        val hsl = PhotoAdjustMath.rgbToHsl(1f, 0.5f, 0f)
        near(30f, hsl[0])
        near(1f, PhotoAdjustMath.rangeWeight(30f, HueRange.ORANGE.ordinal))
        near(0f, PhotoAdjustMath.rangeWeight(30f, HueRange.RED.ordinal))
        // Halfway between green (120) and aqua (180): half each.
        near(0.5f, PhotoAdjustMath.rangeWeight(150f, HueRange.GREEN.ordinal))
        near(0.5f, PhotoAdjustMath.rangeWeight(150f, HueRange.AQUA.ordinal))
        // Across 0: 330 is half magenta (300), half red (360).
        near(0.5f, PhotoAdjustMath.rangeWeight(330f, HueRange.RED.ordinal))
        for (h in 0 until 360 step 7) {
            val sum = (0 until 8).sumOf { PhotoAdjustMath.rangeWeight(h.toFloat(), it).toDouble() }
            near(1f, sum.toFloat(), 1e-4f)
        }
        val back = PhotoAdjustMath.hslToRgb(hsl[0], hsl[1], hsl[2])
        near(1f, back[0]); near(0.5f, back[1]); near(0f, back[2])
    }

    @Test
    fun `a neutral adjustment renders the source unchanged`() {
        val w = 6
        val h = 5
        val src = ByteArray(w * h * 4) { i -> if (i % 4 == 3) -1 else ((i * 37) % 256).toByte() }
        val out = ByteArray(src.size)
        val p = PhotoAdjustParams.pack(PhotoAdjustment(PhotoTool.LIGHT))
        PhotoAdjustMath.render(src, w, h, p, PhotoAdjustParams.curvesTable(PhotoAdjustment(PhotoTool.LIGHT)), null, out)
        for (i in src.indices) {
            assertTrue(kotlin.math.abs((src[i].toInt() and 255) - (out[i].toInt() and 255)) <= 1, "byte $i")
        }
    }

    @Test
    fun `the mask scales the result and the inverted mask its complement`() {
        val src = byteArrayOf(100, 100, 100, -1)
        val out = ByteArray(4)
        val adj = PhotoAdjustment(PhotoTool.LIGHT, mask = AdjustmentMask(enabled = true))
        val p = PhotoAdjustParams.pack(adj)
        PhotoAdjustMath.render(src, 1, 1, p, null, byteArrayOf(64), out)
        // Alpha 255 * 64/255 = 64; colour premultiplied by it: 100/255 * 64/255 * 255 = 25.1.
        assertEquals(64, out[3].toInt() and 255)
        assertEquals(25, out[0].toInt() and 255)
        val inv = PhotoAdjustParams.pack(adj.copy(mask = adj.mask.copy(inverted = true)))
        PhotoAdjustMath.render(src, 1, 1, inv, null, byteArrayOf(64), out)
        assertEquals(191, out[3].toInt() and 255)
    }

    @Test
    fun `curves pass through their points and stay monotone`() {
        val lut = CurveLut.build(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.75f), CurvePoint(1f, 1f)))
        near(0f, lut[0]); near(1f, lut[255])
        // 0.5 is between entries 127 and 128.
        assertTrue(lut[127] in 0.74f..0.76f && lut[128] in 0.74f..0.76f, "${lut[127]} ${lut[128]}")
        for (i in 1 until 256) assertTrue(lut[i] >= lut[i - 1])
        val straight = CurveLut.build(PhotoAdjustment.IDENTITY_CURVE)
        for (i in 0 until 256) near(i / 255f, straight[i])
    }

    @Test
    fun `sliders format their values as the mockup writes them`() {
        assertEquals("+0.35", PhotoSliders.EXPOSURE.display(0.35f))
        assertEquals("−1.20", PhotoSliders.EXPOSURE.display(-1.2f))
        assertEquals("−38", PhotoSliders.HIGHLIGHTS.display(-38f))
        assertEquals("+41", PhotoSliders.SHADOWS.display(41f))
        assertEquals("0", PhotoSliders.CONTRAST.display(0f))
        assertEquals("5900 K", PhotoSliders.TEMPERATURE.display(5900f))
        assertEquals("30", PhotoSliders.NOISE_REDUCTION.display(30f))
        assertEquals("1.10", PhotoSliders.LEVELS_MID.display(1.1f))
        assertEquals(0.5f, PhotoSliders.CONTRAST.fraction(0f))
        assertEquals(0.35f, PhotoSliders.EXPOSURE.valueAt(0.535f))
    }

    @Test
    fun `an adjustment drops a slider set back to its default`() {
        val a = PhotoAdjustment(PhotoTool.LIGHT).with(PhotoSliders.CONTRAST, 12f)
        assertEquals(12f, a.value(PhotoSliders.CONTRAST))
        assertEquals(emptyMap(), a.with(PhotoSliders.CONTRAST, 0f).values)
        assertEquals(100f, a.with(PhotoSliders.CONTRAST, 400f).value(PhotoSliders.CONTRAST))
        assertTrue(a.reset().isNeutral)
    }

    @Test
    fun `the shader hash matches its unsigned reference`() {
        // The WGSL formula worked out in unsigned 32-bit arithmetic outside Kotlin (Python, masking
        // every product to 32 bits): hash(0) = 129708002, hash(1) = 2831084092.
        assertEquals(129708002L, PhotoAdjustMath.hash(0).toLong() and 0xFFFFFFFFL)
        assertEquals(2831084092L, PhotoAdjustMath.hash(1).toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun vignetteEdgeStaysPastTheMidpointAtTheSliderMaximum() {
        // Audit round 2, #7: midpoint 100 made the falloff zero-width (smoothstep(1, 1, r)).
        for (feather in listOf(0f, 0.5f, 1f)) {
            val edge = PhotoAdjustMath.vignetteEdge(1f, feather)
            kotlin.test.assertTrue(edge > 1f, "feather $feather: edge $edge")
        }
        kotlin.test.assertEquals(0.75f, PhotoAdjustMath.vignetteEdge(0.5f, 0.5f), 1e-6f)
    }
}
