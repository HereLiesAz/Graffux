package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals

class IncrementalDabPlacerTest {
    @Test
    fun incrementalPlacementMatchesLegacyAcrossSegments() {
        val points = listOf(
            0f, 0f,
            3f, 4f,
            9f, 4f,
            9f, 12f,
            15f, 12f,
        )
        val step = 2.75f
        val expected = BrushStamps.place(points, step)
        val placer = IncrementalDabPlacer(step)
        val actual = buildList {
            var i = 0
            while (i + 1 < points.size) {
                addAll(placer.append(points[i], points[i + 1]))
                i += 2
            }
        }
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals(expected[index], actual[index], 1e-4f, "coordinate $index")
        }
    }

    @Test
    fun duplicatePointsDoNotEmitExtraDabs() {
        val placer = IncrementalDabPlacer(4f)
        assertEquals(listOf(1f, 2f), placer.append(1f, 2f))
        assertEquals(emptyList(), placer.append(1f, 2f))
        assertEquals(emptyList(), placer.append(1f, 2f))
        assertEquals(listOf(5f, 2f), placer.append(5f, 2f))
    }

    @Test
    fun appendCostDoesNotDependOnStrokeHistory() {
        val placer = IncrementalDabPlacer(1f)
        repeat(10_000) { placer.append(it.toFloat(), 0f) }
        val before = placer.emittedCount
        val fresh = placer.append(10_000f, 0f)
        assertEquals(2, fresh.size)
        assertEquals(before + 1, placer.emittedCount)
    }

    @Test
    fun nonFinitePointsAreDroppedInsteadOfPoisoningOrHangingTheStroke() {
        val placer = IncrementalDabPlacer(4f)
        assertEquals(listOf(0f, 0f), placer.append(0f, 0f))
        assertEquals(emptyList(), placer.append(Float.NaN, 0f))
        assertEquals(emptyList(), placer.append(Float.POSITIVE_INFINITY, 0f))
        assertEquals(listOf(4f, 0f), placer.append(4f, 0f))
    }

    @Test
    fun batchPlacementSkipsAnInfiniteSegment() {
        val centres = BrushStamps.place(listOf(0f, 0f, Float.POSITIVE_INFINITY, 0f, 8f, 0f), 4f)
        assertEquals(listOf(0f, 0f), centres)
    }
}
