package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushStampsTest {

    @Test
    fun emptyInputYieldsNoDabs() {
        assertTrue(BrushStamps.place(emptyList(), 5f).isEmpty())
    }

    @Test
    fun singlePointYieldsOneDab() {
        assertEquals(listOf(3f, 4f), BrushStamps.place(listOf(3f, 4f), 5f))
    }

    @Test
    fun evenlySpacesDabsAlongAStraightLine() {
        // 0..10 on X, step 2 → dabs at x = 0,2,4,6,8,10.
        val dabs = BrushStamps.place(listOf(0f, 0f, 10f, 0f), stepPx = 2f)
        assertEquals(listOf(0f, 0f, 2f, 0f, 4f, 0f, 6f, 0f, 8f, 0f, 10f, 0f), dabs)
    }

    @Test
    fun spacingIsMeasuredAlongArcLengthAcrossSegments() {
        // Right 3 then up 4 = an L of total length 7; step 5 → dab at start, at 5 (2 up the vertical
        // leg → (3,2)), and none at 10 (past the end).
        val dabs = BrushStamps.place(listOf(0f, 0f, 3f, 0f, 3f, 4f), stepPx = 5f)
        assertEquals(listOf(0f, 0f, 3f, 2f), dabs)
    }

    @Test
    fun zeroLengthSegmentsAreSkippedNotDuplicated() {
        // A repeated point in the middle must not spawn a dab nor stall the walk.
        val dabs = BrushStamps.place(listOf(0f, 0f, 5f, 0f, 5f, 0f, 10f, 0f), stepPx = 5f)
        assertEquals(listOf(0f, 0f, 5f, 0f, 10f, 0f), dabs)
    }

    @Test
    fun nonPositiveStepDoesNotHang() {
        // Guarded to a tiny step — finite output, first dab still on the start.
        val dabs = BrushStamps.place(listOf(0f, 0f, 0.05f, 0f), stepPx = 0f)
        assertTrue(dabs.size >= 2)
        assertEquals(0f, dabs[0], 0f)
        assertEquals(0f, dabs[1], 0f)
    }

    @Test
    fun lengthSumsSegmentDistances() {
        assertEquals(7f, BrushStamps.length(listOf(0f, 0f, 3f, 0f, 3f, 4f)), 1e-4f)
        assertEquals(0f, BrushStamps.length(listOf(2f, 2f)), 0f)
    }

    // ---- Dab expansion (brush → concrete stamps) ----

    private val hardRound = AzphaltBrush(name = "Round", spacing = 0.25f)

    @Test
    fun noJitterBrushYieldsSolidDabsOnEachCentre() {
        // spacing 0.25 × diameter 8 = step 2 → centres at x = 0,2,4 along a length-4 line.
        val dabs = BrushStamps.dabs(listOf(0f, 0f, 4f, 0f), diameterPx = 8f, brush = hardRound, seed = 1L)
        assertEquals(3, dabs.size)
        assertEquals(listOf(0f, 2f, 4f), dabs.map { it.x })
        dabs.forEach { d ->
            assertEquals(0f, d.y, 1e-4f)
            assertEquals(4f, d.radius, 1e-4f)   // diameter/2, no size jitter
            assertEquals(1f, d.alpha, 1e-4f)    // full opacity, no opacity jitter
        }
    }

    @Test
    fun sameSeedIsDeterministicAndDifferentSeedDiffers() {
        val jitter = hardRound.copy(sizeJitter = 0.5f, opacityJitter = 0.5f, scatter = 1f)
        val a = BrushStamps.dabs(listOf(0f, 0f, 10f, 0f), 8f, jitter, seed = 42L)
        val b = BrushStamps.dabs(listOf(0f, 0f, 10f, 0f), 8f, jitter, seed = 42L)
        val c = BrushStamps.dabs(listOf(0f, 0f, 10f, 0f), 8f, jitter, seed = 43L)
        assertEquals(a, b)                       // replay identically
        assertTrue(a != c)                       // a different seed shifts the jitter
    }

    @Test
    fun sizeJitterOnlyShrinksAndStaysInBounds() {
        val jitter = hardRound.copy(sizeJitter = 0.5f)
        val dabs = BrushStamps.dabs(listOf(0f, 0f, 20f, 0f), 8f, jitter, seed = 7L)
        // radius ∈ [ (diameter/2)*(1-0.5), diameter/2 ] = [2, 4]
        dabs.forEach { d -> assertTrue(d.radius in 2f..4f) }
        assertTrue(dabs.any { it.radius < 4f })  // at least one actually jittered
    }

    @Test
    fun followStrokeRotatesDabsToTheHeading() {
        // A straight vertical stroke heads at +90°; followStroke should set every dab's angle to it.
        val brush = hardRound.copy(followStroke = true)
        val dabs = BrushStamps.dabs(listOf(0f, 0f, 0f, 10f), 8f, brush, seed = 1L)
        dabs.forEach { d -> assertEquals(90f, d.angleDeg, 1e-3f) }
        // Without followStroke the angle is just the brush's base angle.
        val fixed = BrushStamps.dabs(listOf(0f, 0f, 0f, 10f), 8f, hardRound.copy(angle = 15f), seed = 1L)
        fixed.forEach { d -> assertEquals(15f, d.angleDeg, 1e-3f) }
    }

    @Test
    fun scatterDisplacesPerpendicularToTravel() {
        // Horizontal stroke → scatter must move dabs only in Y (perpendicular), never off the X centres.
        val brush = hardRound.copy(scatter = 2f)
        val dabs = BrushStamps.dabs(listOf(0f, 0f, 20f, 0f), 8f, brush, seed = 5L)
        assertTrue(dabs.any { kotlin.math.abs(it.y) > 0.01f })   // actually scattered
        // |offset| ≤ scatter·diameter = 16
        dabs.forEach { d -> assertTrue(kotlin.math.abs(d.y) <= 16f + 1e-3f) }
    }

    @Test
    fun emptyStrokeYieldsNoDabs() {
        assertTrue(BrushStamps.dabs(emptyList(), 8f, hardRound, seed = 1L).isEmpty())
    }

    // ---- Stamp coverage + flow build-up ----

    @Test
    fun hardStampIsADiscSoftStampFadesFromCentre() {
        // Hard: full coverage everywhere inside, zero at the edge.
        assertEquals(1f, BrushStamps.stampCoverage(0f, hardness = 1f), 1e-4f)
        assertEquals(1f, BrushStamps.stampCoverage(0.99f, hardness = 1f), 1e-4f)
        assertEquals(0f, BrushStamps.stampCoverage(1f, hardness = 1f), 1e-4f)
        // Soft (hardness 0): linear falloff — half coverage at half radius.
        assertEquals(1f, BrushStamps.stampCoverage(0f, hardness = 0f), 1e-4f)
        assertEquals(0.5f, BrushStamps.stampCoverage(0.5f, hardness = 0f), 1e-4f)
        assertEquals(0f, BrushStamps.stampCoverage(1f, hardness = 0f), 1e-4f)
    }

    @Test
    fun mediumHardnessIsSolidToTheCoreThenRamps() {
        // hardness 0.5: solid to r=0.5, then ramps to 0 at r=1 → r=0.75 gives 0.5.
        assertEquals(1f, BrushStamps.stampCoverage(0.5f, hardness = 0.5f), 1e-4f)
        assertEquals(0.5f, BrushStamps.stampCoverage(0.75f, hardness = 0.5f), 1e-4f)
    }

    @Test
    fun buildUpApproachesButNeverExceedsFull() {
        assertEquals(0.5f, BrushStamps.buildUp(0f, 0.5f), 1e-4f)          // first dab
        assertEquals(0.75f, BrushStamps.buildUp(0.5f, 0.5f), 1e-4f)      // second dab builds up
        assertEquals(1f, BrushStamps.buildUp(0.9f, 1f), 1e-4f)          // full flow snaps to full
        // Many low-flow dabs converge toward 1 without overshooting.
        var c = 0f
        repeat(50) { c = BrushStamps.buildUp(c, 0.2f) }
        assertTrue(c > 0.99f && c <= 1f)
    }
}
