package com.hereliesaz.graffitixr.common.util

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeStabilizerTest {

    @Test
    fun `level 0 passes every algorithm through unchanged`() {
        for (algo in StabilizerAlgorithm.entries) {
            val s = StrokeStabilizer()
            val raw = Offset(5f, 7f)
            assertEquals(raw, s.stabilize(raw, level = 0, algorithm = algo))
        }
    }

    @Test
    fun `default algorithm argument is Stabilization, the original moving-average behaviour`() {
        val s = StrokeStabilizer()
        val raw = Offset(10f, 0f)
        // Two-arg call (no algorithm) must match an explicit STABILIZATION call, point for point.
        val a = s.stabilize(raw, level = 50)
        val s2 = StrokeStabilizer()
        val b = s2.stabilize(raw, level = 50, algorithm = StabilizerAlgorithm.STABILIZATION)
        assertEquals(a, b)
    }

    @Test
    fun `streamLine lags behind a sudden jump and converges toward it`() {
        val s = StrokeStabilizer()
        s.stabilize(Offset(0f, 0f), level = 80, algorithm = StabilizerAlgorithm.STREAMLINE)
        val afterJump = s.stabilize(Offset(100f, 0f), level = 80, algorithm = StabilizerAlgorithm.STREAMLINE)
        // Heavy damping (level 80): the output must still be well short of the raw target on the
        // very next sample — that lag IS the "ink trails the stylus" effect.
        assertTrue("expected lag, got x=${afterJump.x}", afterJump.x in 0f..80f)
        var last = afterJump
        repeat(200) { last = s.stabilize(Offset(100f, 0f), level = 80, algorithm = StabilizerAlgorithm.STREAMLINE) }
        assertEquals(100f, last.x, 0.5f) // it must eventually catch up, never freeze short forever
    }

    @Test
    fun `motionFiltering also lags and converges, independent of moving-average history`() {
        val s = StrokeStabilizer()
        s.stabilize(Offset(0f, 0f), level = 80, algorithm = StabilizerAlgorithm.MOTION_FILTERING)
        var last = Offset.Zero
        repeat(100) { last = s.stabilize(Offset(50f, 0f), level = 80, algorithm = StabilizerAlgorithm.MOTION_FILTERING) }
        assertEquals(50f, last.x, 1f)
    }

    @Test
    fun `only StreamLine damps pressure — the others pass it through unchanged`() {
        for (algo in StabilizerAlgorithm.entries) {
            val s = StrokeStabilizer()
            s.stabilizePressure(0f, level = 90, algorithm = algo)
            val next = s.stabilizePressure(1f, level = 90, algorithm = algo)
            if (algo == StabilizerAlgorithm.STREAMLINE) {
                assertTrue("expected damped pressure < 1, got $next", next < 1f)
            } else {
                assertEquals(1f, next, 0f)
            }
        }
    }

    @Test
    fun `reset clears lag state so a new stroke does not inherit the old one's position`() {
        val s = StrokeStabilizer()
        s.stabilize(Offset(1000f, 1000f), level = 90, algorithm = StabilizerAlgorithm.STREAMLINE)
        s.reset()
        // First point of a fresh stroke: no prior lag to pull away from, so it must land exactly on
        // the raw point, however far the old stroke's position was.
        val first = s.stabilize(Offset(0f, 0f), level = 90, algorithm = StabilizerAlgorithm.STREAMLINE)
        assertEquals(Offset(0f, 0f), first)
    }
}
