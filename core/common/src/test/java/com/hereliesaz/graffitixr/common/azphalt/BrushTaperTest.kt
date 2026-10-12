package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `retired end taper settings change nothing`() {
        // End taper needed the final stroke length, which the live stroke never has; it is gone.
        val samples = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L, speedPxPerMs = 5f),
            BrushSample(150f, 0f, uptimeMillis = 30L, speedPxPerMs = 5f),
            BrushSample(200f, 0f, uptimeMillis = 60L, speedPxPerMs = 0.1f),
        )
        val start = BrushTaper(startLengthPx = 40f, minSize = 0.2f, minOpacity = 0.2f)
        val plain = AzphaltBrush(name = "start-only", spacing = 0.25f, taper = start)
        val retired = plain.copy(taper = start.copy(endLengthPx = 60f, liftOffSynthesizesPressure = true))
        assertEquals(
            BrushStamps.dynamicDabs(samples, 20f, plain, seed = 7L).map { it.radius to it.alpha },
            BrushStamps.dynamicDabs(samples, 20f, retired, seed = 7L).map { it.radius to it.alpha },
        )
    }

    @Test
    fun `an end length alone no longer makes a taper active`() {
        assertFalse(BrushTaper(endLengthPx = 60f).isActive())
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

    @Test
    fun `no configured taper means no automatic start or end tail`() {
        // A steady stroke used to grow feathered size/opacity tails at both ends on its own.
        val samples = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L, speedPxPerMs = 5f),
            BrushSample(150f, 0f, uptimeMillis = 30L, speedPxPerMs = 5f),
            BrushSample(300f, 0f, uptimeMillis = 60L, speedPxPerMs = 5f),
        )
        val brush = AzphaltBrush(
            name = "plain",
            spacing = 0.25f,
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE,
                    parameter = BrushParameter.SIZE,
                    outputMin = 1f,
                    outputMax = 1f,
                ),
            ),
        )
        val dabs = BrushStamps.dynamicDabs(samples, 20f, brush, seed = 3L)
        val mid = dabs[dabs.size / 2]
        assertEquals(mid.radius, dabs.first().radius, 1e-3f)
        assertEquals(mid.radius, dabs.last().radius, 1e-3f)
        assertEquals(mid.alpha, dabs.first().alpha, 1e-3f)
        assertEquals(mid.alpha, dabs.last().alpha, 1e-3f)
    }
}
