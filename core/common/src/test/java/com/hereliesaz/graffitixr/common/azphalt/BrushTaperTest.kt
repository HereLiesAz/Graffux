package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushTaperTest {

    private val straight200 = listOf(
        BrushSample(0f, 0f, uptimeMillis = 0L, speedPxPerMs = 5f),
        BrushSample(200f, 0f, uptimeMillis = 40L, speedPxPerMs = 5f),
    )

    @Test
    fun `default taper does not change dab output`() {
        val brush = AzphaltBrush(name = "plain", spacing = 0.25f)
        val withTaper = brush.copy(taper = BrushTaper())
        val a = BrushStamps.dynamicDabs(straight200, 20f, brush, seed = 3L)
        val b = BrushStamps.dynamicDabs(straight200, 20f, withTaper, seed = 3L)
        assertEquals(a.map { it.radius to it.alpha }, b.map { it.radius to it.alpha })
    }

    @Test
    fun `start taper shrinks the opening dabs and leaves the tail alone`() {
        val brush = AzphaltBrush(
            name = "start",
            spacing = 0.25f,
            taper = BrushTaper(startLengthPx = 60f, minSize = 0.1f, minOpacity = 0.2f),
        )
        val dabs = BrushStamps.dynamicDabs(straight200, 20f, brush, seed = 5L)
        val first = dabs.first()
        val last = dabs.last()
        assertEquals(10f * 0.1f, first.radius, 0.05f) // baseRadius(10) * minSize at x=0.
        assertTrue(first.alpha < 0.25f)
        assertEquals(10f, last.radius, 0.05f) // fully outside the 60px start zone.
        assertEquals(1f, last.alpha, 0.01f)
    }

    @Test
    fun `end taper shrinks the closing dabs and leaves the head alone`() {
        val brush = AzphaltBrush(
            name = "end",
            spacing = 0.25f,
            taper = BrushTaper(endLengthPx = 60f, minSize = 0.1f, minOpacity = 0.2f),
        )
        val dabs = BrushStamps.dynamicDabs(straight200, 20f, brush, seed = 5L)
        val first = dabs.first()
        val last = dabs.last()
        assertEquals(10f, first.radius, 0.05f)
        assertEquals(1f, first.alpha, 0.01f)
        assertEquals(10f * 0.1f, last.radius, 0.05f)
        assertTrue(last.alpha < 0.25f)
    }

    @Test
    fun `overlapping start and end zones use the more tapered factor`() {
        // 200px stroke, both zones 150px wide: every dab is inside both zones, so the smaller
        // (more tapered) of the two factors wins rather than double-multiplying them together.
        val brush = AzphaltBrush(
            name = "both",
            spacing = 0.25f,
            taper = BrushTaper(startLengthPx = 150f, endLengthPx = 150f, minSize = 0f, minOpacity = 0f),
        )
        val dabs = BrushStamps.dynamicDabs(straight200, 20f, brush, seed = 5L)
        // Midpoint (x=100) is 100px from the start and 100px from the end: both factors are
        // 100/150 = 0.667, so the min is also 0.667 rather than 0.667*0.667.
        val mid = dabs.minByOrNull { kotlin.math.abs(it.x - 100f) }!!
        assertEquals(10f * (100f / 150f), mid.radius, 0.3f)
    }

    @Test
    fun `lift-off synthesizes a stronger tail fade from a slow deceleration than a fast one`() {
        val decelerating = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L, speedPxPerMs = 5f),
            BrushSample(150f, 0f, uptimeMillis = 30L, speedPxPerMs = 5f),
            BrushSample(200f, 0f, uptimeMillis = 60L, speedPxPerMs = 0.1f),
        )
        val constantSpeed = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L, speedPxPerMs = 5f),
            BrushSample(150f, 0f, uptimeMillis = 30L, speedPxPerMs = 5f),
            BrushSample(200f, 0f, uptimeMillis = 40L, speedPxPerMs = 5f),
        )
        val liftOffBrush = AzphaltBrush(
            name = "liftoff",
            spacing = 0.25f,
            taper = BrushTaper(endLengthPx = 60f, minSize = 0f, minOpacity = 0f, liftOffSynthesizesPressure = true),
        )
        val plainTaperBrush = liftOffBrush.copy(taper = liftOffBrush.taper.copy(liftOffSynthesizesPressure = false))

        val decelDabs = BrushStamps.dynamicDabs(decelerating, 20f, liftOffBrush, seed = 7L)
        val constDabs = BrushStamps.dynamicDabs(constantSpeed, 20f, liftOffBrush, seed = 7L)
        val decelTail = decelDabs.last { it.x < 195f }
        val constTail = constDabs.last { it.x < 195f }
        assertTrue(decelTail.radius < constTail.radius)

        // Without lift-off, distance-only taper is identical regardless of recorded speed.
        val decelPlain = BrushStamps.dynamicDabs(decelerating, 20f, plainTaperBrush, seed = 7L)
        val constPlain = BrushStamps.dynamicDabs(constantSpeed, 20f, plainTaperBrush, seed = 7L)
        assertEquals(
            decelPlain.last { it.x < 195f }.radius,
            constPlain.last { it.x < 195f }.radius,
            0.01f,
        )
    }

    @Test
    fun `taper alone is enough to force the sensor-aware placement path`() {
        // No dynamics/masked-brush bindings, just a taper: dynamicDabs must not silently fall back
        // to the legacy dabs() path (which would ignore taper entirely).
        val brush = AzphaltBrush(
            name = "taper-only",
            spacing = 0.25f,
            taper = BrushTaper(startLengthPx = 40f, minSize = 0f, minOpacity = 0f),
        )
        val dabs = BrushStamps.dynamicDabs(straight200, 20f, brush, seed = 1L)
        assertTrue(dabs.first().radius < 1f)
    }

    @Test
    fun `taper resolution is deterministic for identical input`() {
        val brush = AzphaltBrush(
            name = "det",
            spacing = 0.25f,
            taper = BrushTaper(startLengthPx = 40f, endLengthPx = 40f, minSize = 0.2f, minOpacity = 0.2f, liftOffSynthesizesPressure = true),
        )
        val a = BrushStamps.dynamicDabs(straight200, 20f, brush, seed = 11L)
        val b = BrushStamps.dynamicDabs(straight200, 20f, brush, seed = 11L)
        assertEquals(a.map { it.radius to it.alpha }, b.map { it.radius to it.alpha })
    }
}
