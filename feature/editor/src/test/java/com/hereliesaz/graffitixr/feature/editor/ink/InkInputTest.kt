package com.hereliesaz.graffitixr.feature.editor.ink

import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.common.util.StrokeStabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkInputTest {

    @Test
    fun levelZeroIsNotStabilized() {
        val s = InkStabilizer()
        assertFalse(s.isActive(0))
        assertTrue(s.isActive(1))
    }

    @Test
    fun matchesTheEditorsOwnStabilizerSampleForSample() {
        // Same implementation, same inputs, same outputs: the Ink path smooths exactly as the
        // editor's Brush path would.
        for (algorithm in StabilizerAlgorithm.entries) {
            val ink = InkStabilizer()
            val reference = StrokeStabilizer()
            ink.reset()
            reference.reset()
            val raw = listOf(0f to 0f, 10f to 2f, 20f to 9f, 35f to 4f, 50f to 20f)
            raw.forEachIndexed { i, (x, y) ->
                val pressure = 0.2f + i * 0.1f
                val got = ink.apply(x, y, pressure, LEVEL, algorithm)
                val want = reference.stabilize(androidx.compose.ui.geometry.Offset(x, y), LEVEL, algorithm)
                val wantP = reference.stabilizePressure(pressure, LEVEL, algorithm)
                assertEquals("$algorithm x[$i]", want.x, got.x, 1e-5f)
                assertEquals("$algorithm y[$i]", want.y, got.y, 1e-5f)
                assertEquals("$algorithm p[$i]", wantP, got.pressure, 1e-5f)
                assertEquals(
                    "$algorithm lag[$i]",
                    (androidx.compose.ui.geometry.Offset(x, y) - want).getDistance(), got.lagPx, 1e-4f,
                )
            }
        }
    }

    @Test
    fun resetStartsTheNextStrokeFresh() {
        val s = InkStabilizer()
        s.apply(0f, 0f, 1f, LEVEL, StabilizerAlgorithm.entries.first())
        s.apply(100f, 100f, 1f, LEVEL, StabilizerAlgorithm.entries.first())
        s.reset()
        val first = s.apply(500f, 500f, 1f, LEVEL, StabilizerAlgorithm.entries.first())
        assertEquals(500f, first.x, 1e-4f)
        assertEquals(0f, first.lagPx, 1e-4f)
    }

    @Test
    fun latencyPrefersTheOsEventTime() {
        assertEquals(12.0, InkLatency.latencyMs(1_000_000L, true, 5_000_000L, true, 13_000_000L)!!, 1e-9)
    }

    @Test
    fun latencyFallsBackToViewReceiptOnTheStrokeInputRoute() {
        assertEquals(8.0, InkLatency.latencyMs(0L, false, 5_000_000L, true, 13_000_000L)!!, 1e-9)
    }

    @Test
    fun latencyIsNullWithoutAStartOrAPresentation() {
        assertNull(InkLatency.latencyMs(0L, false, 0L, false, 13_000_000L))
        assertNull(InkLatency.latencyMs(1_000_000L, true, 0L, false, 0L))
    }

    private companion object {
        const val LEVEL = 60
    }
}
