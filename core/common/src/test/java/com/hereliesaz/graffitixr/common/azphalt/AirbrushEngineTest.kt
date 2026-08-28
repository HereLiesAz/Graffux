package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirbrushEngineTest {

    private val plainBrush = AzphaltBrush(name = "airbrush", spacing = 0.25f, sizeJitter = 0f, opacityJitter = 0f)

    @Test
    fun `non-positive dabsPerSecond disables airbrush entirely`() {
        val samples = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L),
            BrushSample(0f, 0f, uptimeMillis = 1000L),
        )
        val dabs = AirbrushEngine.heldDabs(samples, 20f, plainBrush, dabsPerSecond = 0f, stillnessRadiusPx = 4f, seed = 1L)
        assertTrue(dabs.isEmpty())
    }

    @Test
    fun `fewer than two real samples produces no held dabs`() {
        val one = listOf(BrushSample(0f, 0f, uptimeMillis = 0L))
        assertTrue(AirbrushEngine.heldDabs(one, 20f, plainBrush, 10f, 4f, 1L).isEmpty())
        assertTrue(AirbrushEngine.heldDabs(emptyList(), 20f, plainBrush, 10f, 4f, 1L).isEmpty())
    }

    @Test
    fun `a held run deposits dabs at a fixed cadence starting after one interval`() {
        // 0..1000ms held at the same spot, 10 dabs/sec = 100ms interval: dabs at 100,200,...,900
        // (never at t=0 itself, since the movement path already dabs there) — 9 dabs total.
        val samples = listOf(
            BrushSample(10f, 10f, uptimeMillis = 0L),
            BrushSample(10f, 10f, uptimeMillis = 50L),
            BrushSample(10f, 10f, uptimeMillis = 1000L),
        )
        val dabs = AirbrushEngine.heldDabs(samples, 20f, plainBrush, dabsPerSecond = 10f, stillnessRadiusPx = 4f, seed = 1L)
        assertEquals(9, dabs.size)
        dabs.forEach {
            assertEquals(10f, it.x, 1e-4f)
            assertEquals(10f, it.y, 1e-4f)
        }
    }

    @Test
    fun `movement past the stillness radius resets the run without emitting mid-move dabs`() {
        val samples = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L),
            BrushSample(0f, 0f, uptimeMillis = 500L),
            BrushSample(100f, 100f, uptimeMillis = 600L), // far jump: breaks the first run
            BrushSample(100f, 100f, uptimeMillis = 1200L),
        )
        val dabs = AirbrushEngine.heldDabs(samples, 20f, plainBrush, dabsPerSecond = 10f, stillnessRadiusPx = 4f, seed = 1L)

        val atOrigin = dabs.count { it.x == 0f && it.y == 0f }
        val atSecondAnchor = dabs.count { it.x == 100f && it.y == 100f }
        assertEquals(9, dabs.size)
        assertEquals(4, atOrigin) // 100,200,300,400
        assertEquals(5, atSecondAnchor) // 700,800,900,1000,1100 (anchor resets to t=600)
        // No dab anywhere between the two anchors.
        assertTrue(dabs.all { (it.x == 0f && it.y == 0f) || (it.x == 100f && it.y == 100f) })
    }

    @Test
    fun `a sample just outside the stillness radius starts a new run, just inside extends it`() {
        val insideSamples = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L),
            BrushSample(3.9f, 0f, uptimeMillis = 200L), // inside a radius of 4
        )
        val outsideSamples = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L),
            BrushSample(4.1f, 0f, uptimeMillis = 200L), // just outside
        )
        val inside = AirbrushEngine.heldDabs(insideSamples, 20f, plainBrush, dabsPerSecond = 10f, stillnessRadiusPx = 4f, seed = 1L)
        val outside = AirbrushEngine.heldDabs(outsideSamples, 20f, plainBrush, dabsPerSecond = 10f, stillnessRadiusPx = 4f, seed = 1L)
        // Inside: the run continues from the original anchor and still emits (100ms interval).
        assertTrue(inside.isNotEmpty())
        // Outside: the second sample resets the anchor immediately, leaving no time to emit before
        // the sample list ends.
        assertTrue(outside.isEmpty())
    }

    @Test
    fun `held dab size responds to sensor dynamics resolved at the anchor`() {
        val brush = plainBrush.copy(
            dynamics = listOf(
                BrushSensorBinding(BrushSensor.PRESSURE, BrushParameter.SIZE, outputMin = 0.2f, outputMax = 1f),
            ),
        )
        val lowPressure = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L, pressure = 0f),
            BrushSample(0f, 0f, uptimeMillis = 500L, pressure = 0f),
        )
        val highPressure = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L, pressure = 1f),
            BrushSample(0f, 0f, uptimeMillis = 500L, pressure = 1f),
        )
        val lowDabs = AirbrushEngine.heldDabs(lowPressure, 20f, brush, dabsPerSecond = 10f, stillnessRadiusPx = 4f, seed = 1L)
        val highDabs = AirbrushEngine.heldDabs(highPressure, 20f, brush, dabsPerSecond = 10f, stillnessRadiusPx = 4f, seed = 1L)
        assertTrue(lowDabs.isNotEmpty() && highDabs.isNotEmpty())
        assertTrue(lowDabs.first().radius < highDabs.first().radius)
    }

    @Test
    fun `held dabs are deterministic for identical input`() {
        val samples = listOf(
            BrushSample(5f, 5f, uptimeMillis = 0L),
            BrushSample(5f, 5f, uptimeMillis = 800L),
        )
        val a = AirbrushEngine.heldDabs(samples, 20f, plainBrush, 10f, 4f, seed = 42L)
        val b = AirbrushEngine.heldDabs(samples, 20f, plainBrush, 10f, 4f, seed = 42L)
        assertEquals(a.map { it.radius to it.alpha }, b.map { it.radius to it.alpha })
    }
}
