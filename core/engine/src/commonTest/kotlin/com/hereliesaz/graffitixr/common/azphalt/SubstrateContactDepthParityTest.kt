package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubstrateContactDepthParityTest {
    @Test
    fun resolvedContactDepthMatchesIncrementalAndCanonicalNormalAndBlotDabs() {
        val brush = AzphaltBrush(
            name = "Substrate contact depth parity",
            spacing = 0.25f,
            contact = BrushContactConfig(
                enabled = true,
                drag = 0f,
                pressureCoupling = 0.65f,
                pressureSplay = 0f,
                tufts = BrushTuftConfig(enabled = false),
            ),
            // Force the separate blot-parent constructor to participate in the same one-sample
            // parity fixture without depending on the repo's pre-existing incremental spacing
            // size-parity debt for multi-segment strokes.
            blot = BrushBlot(
                lengthPx = 20f,
                extraStamps = 2,
                positionJitter = 0f,
                angleJitterDeg = 0f,
            ),
        )

        fun assertPressure(pressure: Float): Float {
            val sample = BrushSample(
                x = 12f,
                y = 8f,
                uptimeMillis = 100L,
                pressure = pressure,
                distancePx = 0f,
                speedPxPerMs = 0.5f,
                drawingAngleDeg = 0f,
            )
            val canonical = BrushStamps.dynamicDabs(
                listOf(sample), diameterPx = 10f, brush = brush, seed = 77L,
            )
            val live = IncrementalDynamicDabGenerator(
                diameterPx = 10f, brush = brush, seed = 77L,
            ).append(sample, predictedTotal = 0f)

            assertEquals(3, canonical.size, "canonical normal + two blot dabs")
            assertEquals(canonical.size, live.size, "single-sample canonical/live dab count")
            canonical.zip(live).forEachIndexed { index, (expected, actual) ->
                assertTrue(
                    abs(expected.contactDepth - actual.contactDepth) <= 1e-5f,
                    "contactDepth[$index]: expected ${expected.contactDepth}, got ${actual.contactDepth}",
                )
            }
            // The extra stamps are alternate geometry from the same resolved contact, so both blot
            // parents must carry the exact same penetration state as the normal parent.
            live.drop(1).forEachIndexed { index, blot ->
                assertTrue(
                    abs(live.first().contactDepth - blot.contactDepth) <= 1e-5f,
                    "blot contactDepth[$index] did not inherit the resolved contact",
                )
            }
            return live.first().contactDepth
        }

        val light = assertPressure(0.2f)
        val heavy = assertPressure(0.9f)
        assertTrue(light < heavy, "resolved substrate contact depth should increase with pressure")
    }
}
