package com.hereliesaz.graffitixr.feature.editor

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurvesUtilTest {

    private fun pointF(x: Float, y: Float) = PointF().also { it.x = x; it.y = y }

    @Test
    fun `calculateAdjustmentCurve returns 256-element array`() {
        val points = listOf(pointF(0f, 0f), pointF(1f, 1f))
        val lut = CurvesUtil.calculateAdjustmentCurve(points)
        assertEquals(256, lut.size)
    }

    @Test
    fun `calculateAdjustmentCurve values are clamped to 0-255`() {
        val points = listOf(pointF(0f, 0f), pointF(1f, 1f))
        val lut = CurvesUtil.calculateAdjustmentCurve(points)
        for (i in 0..255) {
            assert(lut[i] in 0..255) { "lut[$i] = ${lut[i]} out of range" }
        }
    }

    @Test
    fun `calculateAdjustmentCurve identity points produce identity LUT`() {
        val points = listOf(pointF(0f, 0f), pointF(1f, 1f))
        val lut = CurvesUtil.calculateAdjustmentCurve(points)
        assertEquals(0, lut[0])
        assertEquals(255, lut[255])
    }

    // The three tests above all use a 2-point input. With n=2 the interior tangent loop (the
    // Fritsch-Carlson averaging, the local-extremum zeroing, and the overshoot clamp — the whole
    // reason this class exists rather than a plain linear LUT) never executes at all: m[0] and
    // m[1] are just set to the single secant slope, which is exactly what a straight line would
    // give you too. All three would pass identically if the clamping code were deleted outright.

    @Test
    fun `the curve stays monotone even when adjacent segments have very wildly different slopes`() {
        // A steep segment (0 to 0.01) immediately followed by a near-flat one (0.01 to 0.02) is
        // exactly the shape a plain (unclamped) monotone-cubic spline overshoots on: the averaged
        // tangent it would otherwise use at x=0.01 is wildly out of proportion to either
        // neighbouring secant, which is what the docstring's "a plain natural cubic can bulge past
        // the control points and invert tones" is describing. Overshoot shows up as the LUT
        // dipping below an earlier value — i.e. the curve going non-monotone despite every control
        // point being non-decreasing.
        val points = listOf(pointF(0f, 0f), pointF(0.01f, 0.5f), pointF(0.02f, 0.51f), pointF(1f, 1f))
        val lut = CurvesUtil.calculateAdjustmentCurve(points)
        for (i in 0 until 255) {
            assertTrue("lut[$i]=${lut[i]} > lut[${i + 1}]=${lut[i + 1]}: curve is not monotone", lut[i] <= lut[i + 1])
        }
    }

    @Test
    fun `an asymmetric peak turns down immediately instead of overshooting past it`() {
        // The local-extremum zeroing (delta[i-1]*delta[i] <= 0f -> m[i] = 0f) is what makes the
        // curve turn around cleanly at a peak or valley control point. An asymmetric peak — unequal
        // rise and fall, unlike a symmetric one where the naive average happens to land on zero
        // anyway — is what actually distinguishes "zeroed" from "not zeroed": without the explicit
        // zero, the averaged tangent here is a nonzero ~1.1, so the curve would keep rising for a
        // few more LUT entries past the control point before turning down, producing a second,
        // later local maximum instead of turning at the control point itself.
        val points = listOf(pointF(0f, 0f), pointF(0.3f, 1f), pointF(1f, 0.2f))
        val lut = CurvesUtil.calculateAdjustmentCurve(points)
        val peakIndex = lut.indices.maxByOrNull { lut[it] }!!
        for (i in 0 until peakIndex) {
            assertTrue("expected the rising half to be monotone up to the peak", lut[i] <= lut[i + 1])
        }
        for (i in peakIndex until 255) {
            assertTrue("expected the falling half to be monotone after the peak", lut[i] >= lut[i + 1])
        }
    }
}
