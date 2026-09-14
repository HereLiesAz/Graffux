package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubstrateContactDepthParityTest {
    @Test
    fun resolvedContactDepthMatchesIncrementalAndCanonicalDabStreams() {
        val brush = AzphaltBrush(
            spacing = 0.25f,
            contact = BrushContactConfig(
                enabled = true,
                drag = 0f,
                pressureCoupling = 0.65f,
                pressureSplay = 0f,
                tufts = BrushTuftConfig(enabled = false),
            ),
        )
        val samples = listOf(
            BrushSample(
                x = 0f,
                y = 0f,
                uptimeMillis = 0L,
                pressure = 0.2f,
                distancePx = 0f,
                speedPxPerMs = 0.5f,
                drawingAngleDeg = 0f,
            ),
            BrushSample(
                x = 12f,
                y = 0f,
                uptimeMillis = 16L,
                pressure = 0.4f,
                distancePx = 12f,
                speedPxPerMs = 0.5f,
                drawingAngleDeg = 0f,
            ),
            BrushSample(
                x = 24f,
                y = 0f,
                uptimeMillis = 32L,
                pressure = 0.7f,
                distancePx = 24f,
                speedPxPerMs = 0.5f,
                drawingAngleDeg = 0f,
            ),
            BrushSample(
                x = 36f,
                y = 0f,
                uptimeMillis = 48L,
                pressure = 1f,
                distancePx = 36f,
                speedPxPerMs = 0.5f,
                drawingAngleDeg = 0f,
            ),
        )

        val canonical = BrushStamps.dynamicDabs(samples, baseRadius = 10f, brush = brush, seed = 77L)
        val generator = IncrementalDynamicDabGenerator(baseRadius = 10f, brush = brush, seed = 77L)
        val total = samples.last().distancePx
        val live = samples.flatMap { generator.append(it, predictedTotal = total) }

        assertTrue(canonical.isNotEmpty())
        assertEquals(canonical.size, live.size)
        canonical.zip(live).forEachIndexed { index, (expected, actual) ->
            assertTrue(
                abs(expected.contactDepth - actual.contactDepth) <= 1e-5f,
                "contactDepth[$index]: expected ${expected.contactDepth}, got ${actual.contactDepth}",
            )
        }
        assertTrue(canonical.minOf { it.contactDepth } < canonical.maxOf { it.contactDepth })
    }
}
