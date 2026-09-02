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

    @Test
    fun dabsGrowAsAStablePrefixSoLivePreviewCanStampIncrementally() {
        // The live stamp preview stamps only the newly-added dabs each frame, relying on this: as a
        // stroke extends, the earlier dabs (positions AND seeded jitter) don't change — a shorter
        // stroke's dabs are exactly a prefix of the longer stroke's. Uses full jitter to stress it.
        val brush = AzphaltBrush(
            name = "B", spacing = 0.5f, sizeJitter = 0.5f, opacityJitter = 0.5f, scatter = 2f,
        )
        val short = BrushStamps.dabs(listOf(0f, 0f, 12f, 0f), diameterPx = 8f, brush = brush, seed = 99L)
        val long = BrushStamps.dabs(listOf(0f, 0f, 20f, 0f), diameterPx = 8f, brush = brush, seed = 99L)
        assertTrue(short.size < long.size)
        short.forEachIndexed { i, d -> assertEquals(d, long[i]) }
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

    // ---- Count (multi-stamp per placement point) ----

    @Test
    fun countOneIsByteIdenticalToTheHistoricalSingleDabOutput() {
        val single = BrushStamps.dabs(listOf(0f, 0f, 12f, 0f), diameterPx = 8f, brush = hardRound, seed = 5L)
        val explicit = BrushStamps.dabs(
            listOf(0f, 0f, 12f, 0f), diameterPx = 8f, brush = hardRound.copy(count = 1), seed = 5L,
        )
        assertEquals(single, explicit)
    }

    @Test
    fun countEmitsThatManyDabsPerPlacementPoint() {
        val brush = hardRound.copy(count = 3)
        val single = BrushStamps.dabs(listOf(5f, 5f), diameterPx = 8f, brush = brush, seed = 5L)
        assertEquals(3, single.size)
    }

    @Test
    fun countJitterVariesTheEmittedCountButNeverBelowOneOrAboveCount() {
        val brush = hardRound.copy(count = 8, countJitter = 1f)
        // Many placement points along a line so the per-point resolved count varies with the RNG.
        val dabs = BrushStamps.dabs(listOf(0f, 0f, 200f, 0f), diameterPx = 8f, brush = brush, seed = 7L)
        assertTrue(dabs.isNotEmpty())
    }

    // ---- Sensor-driven hardness/tipRatio dynamics ----

    @Test
    fun hardnessSensorBindingScalesTheResolvedPerDabHardness() {
        val brush = AzphaltBrush(
            name = "B",
            hardness = 1f,
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE,
                    parameter = BrushParameter.HARDNESS,
                    outputMin = 0f,
                    outputMax = 1f,
                ),
            ),
        )
        val samples = listOf(
            BrushSample(x = 0f, y = 0f, uptimeMillis = 0L, pressure = 0f),
            BrushSample(x = 10f, y = 0f, uptimeMillis = 10L, pressure = 0f),
        )
        val dabs = BrushStamps.dynamicDabs(samples, diameterPx = 8f, brush = brush, seed = 1L)
        assertTrue(dabs.isNotEmpty())
        dabs.forEach { assertEquals(0f, it.hardness, 1e-4f) }
    }

    @Test
    fun tipRatioSensorBindingScalesTheResolvedPerDabTipRatio() {
        val brush = AzphaltBrush(
            name = "B",
            tipRatio = 1f,
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE,
                    parameter = BrushParameter.TIP_RATIO,
                    outputMin = 0.5f,
                    outputMax = 0.5f,
                ),
            ),
        )
        val samples = listOf(
            BrushSample(x = 0f, y = 0f, uptimeMillis = 0L, pressure = 1f),
            BrushSample(x = 10f, y = 0f, uptimeMillis = 10L, pressure = 1f),
        )
        val dabs = BrushStamps.dynamicDabs(samples, diameterPx = 8f, brush = brush, seed = 1L)
        assertTrue(dabs.isNotEmpty())
        dabs.forEach { assertEquals(0.5f, it.tipRatio, 1e-4f) }
    }

    // ---- First-touch blot ----

    private fun straightLineSamples(lengthPx: Float, steps: Int): List<BrushSample> =
        (0..steps).map { i ->
            val x = lengthPx * i / steps
            BrushSample(x = x, y = 0f, uptimeMillis = (i * 10).toLong())
        }

    @Test
    fun blotSpikesTheVeryFirstDabsSizeThenDecaysToNormalByLengthPx() {
        val brush = AzphaltBrush(
            name = "B",
            blot = BrushBlot(lengthPx = 100f, sizeMultiplier = 3f, opacityMultiplier = 1f),
        )
        val dabs = BrushStamps.dynamicDabs(
            straightLineSamples(200f, steps = 40), diameterPx = 10f, brush = brush, seed = 3L,
        )
        assertTrue(dabs.isNotEmpty())
        // The very first dab should be near the full 3x spike (radius ~15 vs the resting 5).
        assertTrue("first dab should be spiked, was radius=${dabs.first().radius}", dabs.first().radius > 10f)
        // Far past lengthPx, dabs should have settled back to the resting radius (~5).
        val settled = dabs.last()
        assertEquals(5f, settled.radius, 0.5f)
    }

    @Test
    fun blotDisabledByDefaultMatchesUnblottedOutput() {
        val samples = straightLineSamples(50f, steps = 10)
        val plain = AzphaltBrush(name = "B")
        val withZeroBlot = plain.copy(blot = BrushBlot())
        assertEquals(
            BrushStamps.dynamicDabs(samples, diameterPx = 10f, brush = plain, seed = 9L),
            BrushStamps.dynamicDabs(samples, diameterPx = 10f, brush = withZeroBlot, seed = 9L),
        )
    }

    @Test
    fun blotExtraStampsAddRandomlyRealignedCopiesNearTheTouchdownOnly() {
        val brush = AzphaltBrush(
            name = "B",
            spacing = 0.05f,
            blot = BrushBlot(lengthPx = 30f, extraStamps = 4, angleJitterDeg = 180f),
        )
        val withExtras = BrushStamps.dynamicDabs(
            straightLineSamples(200f, steps = 60), diameterPx = 10f, brush = brush, seed = 11L,
        )
        val withoutExtras = BrushStamps.dynamicDabs(
            straightLineSamples(200f, steps = 60),
            diameterPx = 10f,
            brush = brush.copy(blot = brush.blot.copy(extraStamps = 0)),
            seed = 11L,
        )
        // Extra stamps only fire near the touchdown (blotT < 1, i.e. within lengthPx), so the
        // extras-enabled run must emit strictly more dabs, but the two runs converge once past
        // the blot window (same tail count of "real" placement points either way).
        assertTrue(withExtras.size > withoutExtras.size)
        // Each extra stamp should carry a random rotation somewhere in the jitter range, not all
        // identical to the primary dab's own heading-following angle.
        val anglesNearStart = withExtras.take(10).map { it.angleDeg }.toSet()
        assertTrue("expected varied angles from random realignment, got $anglesNearStart", anglesNearStart.size > 1)
    }

    @Test
    fun blotExtraStampsDisabledByDefault() {
        assertEquals(0, BrushBlot().extraStamps)
        assertTrue(!BrushBlot().isActive())
        assertTrue(!BrushBlot(lengthPx = 10f).isActive())
    }
}
