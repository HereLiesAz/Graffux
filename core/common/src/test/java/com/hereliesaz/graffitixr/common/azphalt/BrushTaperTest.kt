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
    fun `start taper shrinks the opening dabs and leaves the mid-stroke alone`() {
        val brush = AzphaltBrush(
            name = "start",
            spacing = 0.25f,
            taper = BrushTaper(startLengthPx = 60f, minSize = 0.1f, minOpacity = 0.2f),
        )
        val dabs = BrushStamps.dynamicDabs(straight200, 20f, brush, seed = 5L)
        val first = dabs.first()
        // straight200 is at constant peak speed so speedSizeFactor=0.8; first dab: minSize*0.8 ≈ 0.8.
        // The taper is the dominant effect; first.radius is well below the mid-stroke value.
        assertTrue(first.radius < 1.5f)
        assertTrue(first.alpha < 0.25f)
        // straight200 ends at constant speed so liftDecelT=0 → no natural end zone. x≈100 is
        // clear of the 60px start zone. At peak speed, speedSizeFactor=0.8 applies throughout.
        val mid = dabs.minByOrNull { kotlin.math.abs(it.x - 100f) }!!
        // Taper fully released at mid-stroke; speed sensitivity at constant peak ≈ 80% of baseRadius.
        assertEquals(10f * 0.8f, mid.radius, 0.3f)
        assertEquals(1f, mid.alpha, 0.02f)
    }

    @Test
    fun `end taper shrinks the closing dabs`() {
        // straight200 has constant speed=5 at both samples, so startSpeedT=1 and the natural start
        // zone (160px) also applies. This test focuses on the END zone behavior: the tail must be
        // well below full size regardless of what the head is doing.
        val brush = AzphaltBrush(
            name = "end",
            spacing = 0.25f,
            taper = BrushTaper(endLengthPx = 60f, minSize = 0.1f, minOpacity = 0.2f),
        )
        val dabs = BrushStamps.dynamicDabs(straight200, 20f, brush, seed = 5L)
        val last = dabs.last()
        // The convex taper curve keeps the brush wide for most of the end zone and drops sharply
        // only at the very tip. The last placed dab must be well below the full speed-adjusted size.
        assertTrue(last.radius < 10f * 0.5f)
        assertTrue(last.alpha < 0.5f)
        // Mid-stroke dab should be larger than the tail, confirming end taper is zone-limited.
        val mid = dabs.minByOrNull { kotlin.math.abs(it.x - 100f) }!!
        assertTrue(mid.radius > last.radius * 2f)
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
        // Midpoint (x=100) is 100px from the start and 100px from the end: both taper factors are
        // 100/150 = 0.667, so the min is 0.667 rather than 0.667*0.667 (zones use min, not product).
        // Additionally, straight200 runs at constant peak speed, so speedSizeFactor=0.8 also applies.
        val mid = dabs.minByOrNull { kotlin.math.abs(it.x - 100f) }!!
        assertEquals(10f * (100f / 150f) * 0.8f, mid.radius, 0.4f)
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

        // Without liftOffSynthesizesPressure the natural speed-adaptive zone still gives a
        // decelerating stroke more taper than a constant-speed one (larger zone, lower floor).
        val decelPlain = BrushStamps.dynamicDabs(decelerating, 20f, plainTaperBrush, seed = 7L)
        val constPlain = BrushStamps.dynamicDabs(constantSpeed, 20f, plainTaperBrush, seed = 7L)
        assertTrue(decelPlain.last { it.x < 195f }.radius < constPlain.last { it.x < 195f }.radius)
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
