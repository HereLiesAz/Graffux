package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatmullRomTest {

    @Test
    fun fewerThanTwoPointsYieldsNoSegments() {
        assertTrue(CatmullRom.segments(emptyList()).isEmpty())
        assertTrue(CatmullRom.segments(listOf(1f, 2f)).isEmpty())
    }

    @Test
    fun densifyOfFewerThanTwoPointsReturnsInputUnchanged() {
        assertEquals(emptyList<Float>(), CatmullRom.densify(emptyList()))
        assertEquals(listOf(1f, 2f), CatmullRom.densify(listOf(1f, 2f)))
    }

    @Test
    fun onePerSegmentDegeneratesToTheStraightChord() {
        // samplesPerSegment = 1 evaluates only t=0 and t=1 — exactly the original two endpoints,
        // whatever the curve does in between.
        val points = listOf(0f, 0f, 3f, 0f, 3f, 4f, 10f, 4f)
        val segs = CatmullRom.segments(points, samplesPerSegment = 1)
        assertEquals(3, segs.size)
        assertEquals(listOf(0f, 0f, 3f, 0f), segs[0].toList())
        assertEquals(listOf(3f, 0f, 3f, 4f), segs[1].toList())
        assertEquals(listOf(3f, 4f, 10f, 4f), segs[2].toList())
    }

    @Test
    fun collinearEvenlySpacedPointsProduceAnExactStraightLine() {
        // No curvature to fit when every point already lies on one line — the spline must
        // reproduce it exactly, not introduce artificial bends.
        val points = listOf(0f, 0f, 10f, 0f, 20f, 0f, 30f, 0f)
        val curved = CatmullRom.densify(points, samplesPerSegment = 5)
        var i = 0
        while (i < curved.size) {
            assertEquals(0f, curved[i + 1], 1e-3f) // y stays exactly 0 throughout
            i += 2
        }
        // x increases monotonically from 0 to 30 — no doubling back.
        for (k in 0 until curved.size / 2 - 1) {
            assertTrue(curved[2 * k] <= curved[2 * (k + 1)] + 1e-3f)
        }
        assertEquals(0f, curved[0], 1e-3f)
        assertEquals(30f, curved[curved.size - 2], 1e-3f)
    }

    @Test
    fun everySegmentPassesThroughItsOriginalEndpointsExactly() {
        val points = listOf(0f, 0f, 4f, 6f, 9f, 2f, 15f, 15f, 20f, 3f)
        val segs = CatmullRom.segments(points, samplesPerSegment = 6)
        val n = points.size / 2
        for (i in 0 until n - 1) {
            val run = segs[i]
            assertEquals(points[2 * i], run[0], 1e-3f)
            assertEquals(points[2 * i + 1], run[1], 1e-3f)
            assertEquals(points[2 * (i + 1)], run[run.size - 2], 1e-3f)
            assertEquals(points[2 * (i + 1) + 1], run[run.size - 1], 1e-3f)
        }
    }

    @Test
    fun densifyConcatenatesSegmentsWithoutDuplicatingSharedBoundaryPoints() {
        val points = listOf(0f, 0f, 4f, 6f, 9f, 2f, 15f, 15f)
        val samplesPerSegment = 4
        val curved = CatmullRom.densify(points, samplesPerSegment)
        // 3 segments * (samplesPerSegment + 1) points, minus (segments - 1) shared boundaries.
        val expectedPointCount = 3 * (samplesPerSegment + 1) - 2
        assertEquals(expectedPointCount * 2, curved.size)
        // Still starts and ends exactly on the original first/last point.
        assertEquals(0f, curved[0], 1e-3f)
        assertEquals(0f, curved[1], 1e-3f)
        assertEquals(15f, curved[curved.size - 2], 1e-3f)
        assertEquals(15f, curved[curved.size - 1], 1e-3f)
    }

    @Test
    fun aSharpCornerIsRoundedNotKeptAsAHardAngle() {
        // A right-angle turn: (0,0) -> (10,0) -> (10,10). The midpoint of the curved second
        // segment must have moved off the hard corner at (10,0), unlike a straight chord which
        // would still pass exactly through it.
        val points = listOf(0f, 0f, 10f, 0f, 10f, 10f)
        val segs = CatmullRom.segments(points, samplesPerSegment = 10)
        val secondSegMidpoint = segs[1][10] to segs[1][11] // step 5 of 10, i.e. t = 0.5
        assertTrue(
            "expected the curve to round the corner, not sit exactly on it",
            kotlin.math.abs(secondSegMidpoint.first - 10f) > 0.5f,
        )
    }

    @Test
    fun nonPositiveSamplesPerSegmentIsTreatedAsOne() {
        val points = listOf(0f, 0f, 5f, 5f)
        assertEquals(CatmullRom.segments(points, 1).size, CatmullRom.segments(points, 0).size)
        assertEquals(CatmullRom.segments(points, 1)[0].toList(), CatmullRom.segments(points, -3)[0].toList())
    }
}
