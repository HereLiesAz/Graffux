package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushScatterRotationTest {

    private val straight = listOf(0f, 0f, 100f, 0f)
    private val straightSamples = listOf(
        BrushSample(0f, 0f, uptimeMillis = 0L, speedPxPerMs = 1f),
        BrushSample(100f, 0f, uptimeMillis = 100L, speedPxPerMs = 1f),
    )

    // A neutral (1x) route that forces dynamicDabs into its sensor-aware do-while loop instead of
    // falling back to the legacy dabs() path, without changing any resolved value.
    private val neutralRoute = BrushSensorBinding(
        sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 1f, outputMax = 1f,
    )

    @Test
    fun `default scatter and rotation fields do not change dab output`() {
        val plain = AzphaltBrush(name = "plain", spacing = 0.25f)
        val a = BrushStamps.dabs(straight, 20f, plain, seed = 1L)
        val b = BrushStamps.dabs(straight, 20f, plain.copy(scatterLongitudinal = 0f, rotationPerPx = 0f), seed = 1L)
        assertEquals(a, b)
    }

    @Test
    fun `longitudinal scatter moves dabs along the heading, not perpendicular to it`() {
        // Heading is along +x for this straight horizontal stroke, so longitudinal scatter should
        // perturb x but never y — the opposite axis from the existing perpendicular `scatter` field.
        val brush = AzphaltBrush(name = "long", spacing = 0.5f, scatterLongitudinal = 0.8f)
        val plain = brush.copy(scatterLongitudinal = 0f)
        val dabs = BrushStamps.dabs(straight, 20f, brush, seed = 3L)
        val nominal = BrushStamps.dabs(straight, 20f, plain, seed = 3L)

        dabs.forEach { assertEquals(0f, it.y, 1e-4f) }
        assertTrue(dabs.map { it.x }.zip(nominal.map { it.x }).any { (actual, base) -> actual != base })
    }

    @Test
    fun `longitudinal scatter is deterministic and does not perturb perpendicular scatter or jitter`() {
        val base = AzphaltBrush(
            name = "base", spacing = 0.25f, sizeJitter = 0.3f, opacityJitter = 0.4f, scatter = 0.6f,
        )
        val withLongitudinal = base.copy(scatterLongitudinal = 0.9f)
        val baseDabs = BrushStamps.dabs(straight, 20f, base, seed = 9L)
        val longDabs1 = BrushStamps.dabs(straight, 20f, withLongitudinal, seed = 9L)
        val longDabs2 = BrushStamps.dabs(straight, 20f, withLongitudinal, seed = 9L)
        // Radius/alpha/perpendicular-y are unaffected by turning longitudinal scatter on.
        assertEquals(baseDabs.map { it.radius to it.alpha }, longDabs1.map { it.radius to it.alpha })
        assertEquals(baseDabs.map { it.y }, longDabs1.map { it.y })
        // But its own effect is deterministic given the same seed.
        assertEquals(longDabs1.map { it.x }, longDabs2.map { it.x })
    }

    @Test
    fun `distance rotation accumulates linearly with travelled distance on the static path`() {
        val brush = AzphaltBrush(name = "spin", spacing = 0.25f, rotationPerPx = 2f)
        val dabs = BrushStamps.dabs(straight, 20f, brush, seed = 1L)
        val step = brush.spacing * 20f
        dabs.forEachIndexed { i, dab ->
            assertEquals(brush.rotationPerPx * i * step, dab.angleDeg, 0.01f)
        }
        assertTrue(dabs.last().angleDeg > dabs.first().angleDeg)
    }

    @Test
    fun `distance rotation accumulates with cumulative arc length on the sensor-aware path`() {
        val brush = AzphaltBrush(
            name = "spin-dynamic", spacing = 0.25f, rotationPerPx = 1.5f, dynamics = listOf(neutralRoute),
        )
        val dabs = BrushStamps.dynamicDabs(straightSamples, 20f, brush, seed = 1L)
        assertEquals(0f, dabs.first().angleDeg, 0.01f)
        assertEquals(brush.rotationPerPx * 100f, dabs.last().angleDeg, 0.5f)
        // Monotonic for a straight, constant-heading stroke.
        for (i in 1 until dabs.size) {
            assertTrue(dabs[i].angleDeg >= dabs[i - 1].angleDeg)
        }
    }

    @Test
    fun `distance rotation is independent of followStroke and static angle, and composes additively`() {
        val plain = AzphaltBrush(name = "plain", spacing = 0.5f, angle = 10f, rotationPerPx = 3f)
        val dabs = BrushStamps.dabs(straight, 20f, plain, seed = 1L)
        val step = plain.spacing * 20f
        // Heading is 0deg throughout (horizontal line), so only the static angle and the
        // distance-driven rotation contribute — and they add rather than one overriding the other.
        dabs.forEachIndexed { i, dab ->
            assertEquals(plain.angle + plain.rotationPerPx * i * step, dab.angleDeg, 0.01f)
        }
        assertNotEquals(dabs.first().angleDeg, dabs.last().angleDeg)
    }

    @Test
    fun `scatter and rotation fields round trip through extension params`() {
        val b = AzphaltBrush.fromParams(
            "params",
            buildJsonObject {
                put("scatterLongitudinal", JsonPrimitive(0.4f))
                put("rotationPerPx", JsonPrimitive(1.25f))
            },
        )
        assertEquals(0.4f, b.scatterLongitudinal, 1e-6f)
        assertEquals(1.25f, b.rotationPerPx, 1e-6f)
    }

    @Test
    fun `negative scatterLongitudinal is clamped like the existing perpendicular scatter`() {
        val brush = AzphaltBrush(name = "wild", scatterLongitudinal = -5f).sanitized()
        assertEquals(0f, brush.scatterLongitudinal, 0f)
    }
}
