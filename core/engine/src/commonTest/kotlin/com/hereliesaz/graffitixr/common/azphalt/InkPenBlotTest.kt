package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Ink Pen's start blot stays small however the pen lands (the "big spots at one end of pen
 * strokes" report), and the live preview draws the same first dab the committed stroke does.
 */
class InkPenBlotTest {
    private val inkPen = BuiltInBrushes.presets.single { it.name == "Ink Pen" }
    private val diameter = 10f
    private val seed = 42L

    /** A hard stylus slam: pressure 0 -> 1 in one 8 ms sample, then a straight 200 px line. */
    private val sharpPress = listOf(BrushSample(0f, 0f, uptimeMillis = 0L, pressure = 0f)) +
        line(startMs = 8L, fromX = 0f)

    /** The pen rests on the touchdown point for 600 ms (past the old 400 ms dwell ramp), then moves. */
    private val restingPress = listOf(
        BrushSample(0f, 0f, uptimeMillis = 0L, pressure = 1f),
        BrushSample(0f, 0f, uptimeMillis = 300L, pressure = 1f),
        BrushSample(0f, 0f, uptimeMillis = 600L, pressure = 1f),
    ) + line(startMs = 608L, fromX = 0f)

    private fun line(startMs: Long, fromX: Float): List<BrushSample> = (1..20).map { i ->
        val x = fromX + i * 10f
        // Speed left at 0 so the speed-thinning factor is 1 everywhere and cannot mask the blot.
        BrushSample(x, 0f, uptimeMillis = startMs + i * 8L, pressure = 1f, distancePx = x)
    }

    private fun steadyRadius(dabs: List<Dab>): Float = dabs.last().radius

    @Test
    fun firstDabIsAtMostTheCapForASharpPress() = assertBlotCapped(sharpPress)

    @Test
    fun firstDabIsAtMostTheCapWhenThePenRestsOnTouchdown() = assertBlotCapped(restingPress)

    private fun assertBlotCapped(samples: List<BrushSample>) {
        val dabs = BrushStamps.dynamicDabs(samples, diameter, inkPen, seed)
        val steady = steadyRadius(dabs)
        val peak = dabs.maxOf { it.radius }
        assertTrue(steady > 0f)
        assertTrue(
            peak <= steady * BuiltInBrushes.INK_PEN_BLOT_MAX_SIZE * 1.0001f,
            "start blot ${peak / steady}x the steady dab, cap ${BuiltInBrushes.INK_PEN_BLOT_MAX_SIZE}x",
        )
        assertEquals(peak, dabs.first().radius, 1e-4f, "the first dab is the blot's peak")
    }

    @Test
    fun previewFirstDabEqualsCommittedFirstDab() {
        for (samples in listOf(sharpPress, restingPress)) {
            val committed = BrushStamps.dynamicDabs(samples, diameter, inkPen, seed).first()
            val generator = IncrementalDynamicDabGenerator(diameter, inkPen, seed)
            val live = generator.append(samples.first(), predictedTotal = samples.last().distancePx).first()
            assertEquals(committed.x, live.x, 1e-4f)
            assertEquals(committed.y, live.y, 1e-4f)
            assertEquals(committed.radius, live.radius, 1e-4f)
            assertEquals(committed.alpha, live.alpha, 1e-4f)
        }
    }
}
