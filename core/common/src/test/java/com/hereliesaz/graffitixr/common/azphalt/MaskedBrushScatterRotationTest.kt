package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 10's longitudinal-scatter/distance-rotation primitives, extended to the secondary/masked
 * tip -- the follow-up explicitly called out as remaining scope once the primary tip got them.
 * [MaskedBrushConfig] keeps its own independent `scatterLongitudinal`/`rotationPerPx` fields,
 * mirroring [AzphaltBrush]'s but resolved against the mask's own size/heading, same as its
 * existing `scatter`/`angle` fields already do.
 */
class MaskedBrushScatterRotationTest {

    private val straight = listOf(0f, 0f, 100f, 0f)
    private val straightSamples = listOf(
        BrushSample(0f, 0f, uptimeMillis = 0L, speedPxPerMs = 1f),
        BrushSample(100f, 0f, uptimeMillis = 100L, speedPxPerMs = 1f),
    )
    private val neutralRoute = BrushSensorBinding(
        sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 1f, outputMax = 1f,
    )

    private fun withMask(config: MaskedBrushConfig, spacing: Float = 0.5f) =
        AzphaltBrush(name = "masked", spacing = spacing, maskedBrush = config)

    @Test
    fun `default masked scatter and rotation fields do not change mask output`() {
        val brush = withMask(MaskedBrushConfig())
        val a = BrushStamps.dabs(straight, 20f, brush, seed = 1L)
        val b = BrushStamps.dabs(
            straight, 20f, brush.copy(maskedBrush = MaskedBrushConfig(scatterLongitudinal = 0f, rotationPerPx = 0f)), seed = 1L,
        )
        assertEquals(a.map { it.mask }, b.map { it.mask })
    }

    @Test
    fun `masked longitudinal scatter moves the mask along the heading, not perpendicular to it`() {
        val brush = withMask(MaskedBrushConfig(scatterLongitudinal = 0.8f))
        val plain = withMask(MaskedBrushConfig(scatterLongitudinal = 0f))
        val dabs = BrushStamps.dabs(straight, 20f, brush, seed = 3L)
        val nominal = BrushStamps.dabs(straight, 20f, plain, seed = 3L)

        dabs.forEach { assertEquals(0f, it.mask!!.y, 1e-4f) }
        assertTrue(
            dabs.map { it.mask!!.x }.zip(nominal.map { it.mask!!.x }).any { (actual, base) -> actual != base },
        )
    }

    @Test
    fun `masked longitudinal scatter does not perturb the primary dab or the mask's own perpendicular scatter`() {
        val base = withMask(MaskedBrushConfig(scatter = 0.6f))
        val withLongitudinal = withMask(MaskedBrushConfig(scatter = 0.6f, scatterLongitudinal = 0.9f))
        val baseDabs = BrushStamps.dabs(straight, 20f, base, seed = 9L)
        val longDabs = BrushStamps.dabs(straight, 20f, withLongitudinal, seed = 9L)

        assertEquals(baseDabs.map { it.x to it.y }, longDabs.map { it.x to it.y })
        assertEquals(baseDabs.map { it.mask!!.y }, longDabs.map { it.mask!!.y })
    }

    @Test
    fun `masked distance rotation accumulates linearly on the static path`() {
        val brush = withMask(MaskedBrushConfig(rotationPerPx = 2f))
        val dabs = BrushStamps.dabs(straight, 20f, brush, seed = 1L)
        val step = brush.spacing * 20f
        dabs.forEachIndexed { i, dab ->
            assertEquals(2f * i * step, dab.mask!!.angleDeg, 0.01f)
        }
        assertTrue(dabs.last().mask!!.angleDeg > dabs.first().mask!!.angleDeg)
    }

    @Test
    fun `masked distance rotation accumulates with cumulative arc length on the sensor-aware path`() {
        val brush = AzphaltBrush(
            name = "masked-dynamic", spacing = 0.25f, dynamics = listOf(neutralRoute),
            maskedBrush = MaskedBrushConfig(rotationPerPx = 1.5f),
        )
        val dabs = BrushStamps.dynamicDabs(straightSamples, 20f, brush, seed = 1L)
        assertEquals(0f, dabs.first().mask!!.angleDeg, 0.01f)
        assertEquals(1.5f * 100f, dabs.last().mask!!.angleDeg, 0.5f)
    }

    @Test
    fun `masked rotationPerPx composes additively with the mask's own static angle`() {
        val brush = withMask(MaskedBrushConfig(angle = 10f, rotationPerPx = 3f))
        val dabs = BrushStamps.dabs(straight, 20f, brush, seed = 1L)
        val step = brush.spacing * 20f
        dabs.forEachIndexed { i, dab ->
            assertEquals(10f + 3f * i * step, dab.mask!!.angleDeg, 0.01f)
        }
    }

    @Test
    fun `negative masked scatterLongitudinal is clamped like the mask's existing scatter`() {
        val cfg = MaskedBrushConfig(scatterLongitudinal = -5f).sanitized()
        assertEquals(0f, cfg.scatterLongitudinal, 0f)
    }
}
