package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A dual-tip stroke stamps a secondary for every primary dab or none (the GPU takes them as
 * parallel arrays, and the commit refuses a batch with a dab missing one: "That stroke didn't
 * paint"). Blot extra stamps and a stroke program's emits and tick steps used to come out without
 * one, so every such stroke failed to commit.
 */
class DualTipSecondaryCoverageTest {
    private val secondTip = MaskedBrushConfig(sizeRatio = 0.5f, opacity = 0.8f, angle = 30f)
    private val seed = 7L
    private val diameter = 24f

    private val samples = (0..30).map { i ->
        BrushSample(
            i * 4f, 0f, uptimeMillis = i * 40L, pressure = 1f, distancePx = i * 4f,
            telemetry = BrushTelemetryMetadata(
                contactPhase = if (i == 30) BrushContactPhase.LIFT_OFF else BrushContactPhase.CONTACT,
            ),
        )
    }

    private fun movement(brush: AzphaltBrush) =
        LiveStrokeDabs.movementStroke(samples, diameter, brush, seed, null).dabs

    private fun assertEveryDabHasASecondary(dabs: List<Dab>) {
        assertTrue(dabs.isNotEmpty())
        val missing = dabs.count { it.mask == null }
        assertEquals(0, missing, "$missing of ${dabs.size} dabs have no secondary tip")
    }

    @Test
    fun blotExtraStampsCarryTheSecondTip() {
        val brush = AzphaltBrush(
            name = "Blot dual",
            spacing = 0.1f,
            maskedBrush = secondTip,
            blot = BrushBlot(lengthPx = 60f, sizeMultiplier = 1.5f, extraStamps = 3),
        )
        val dabs = movement(brush)
        assertEveryDabHasASecondary(dabs)
    }

    @Test
    fun blotExtraStampSecondaryMovesWithItsDab() {
        val brush = AzphaltBrush(
            name = "Blot dual",
            spacing = 0.1f,
            maskedBrush = secondTip,
            blot = BrushBlot(lengthPx = 60f, extraStamps = 2, angleJitterDeg = 0f),
        )
        // No scatter on the second tip: every secondary sits on its own dab's centre.
        for (dab in movement(brush)) {
            assertEquals(dab.x, dab.mask!!.x, 1e-3f)
            assertEquals(dab.y, dab.mask!!.y, 1e-3f)
        }
    }

    @Test
    fun strokeProgramEmitsAndTicksCarryTheSecondTip() {
        // Spray-cap shape: hides the brush's own dab and emits particles, on distance steps and on
        // the 16 ms ticks a held or slow pen takes, plus the lift-off step.
        val brush = AzphaltBrush(
            name = "Spray dual",
            spacing = 0.2f,
            buildUp = true,
            maskedBrush = secondTip,
            program = StrokeProgramSpec(step = "hide = 1; emit { x: px + 2, y: py, size: 6 };", tickMs = 16f),
        )
        val dabs = movement(brush)
        assertEveryDabHasASecondary(dabs)
        val emitted = dabs.first()
        assertEquals(emitted.x, emitted.mask!!.x, 1e-3f)
        assertEquals(emitted.radius * 0.5f, emitted.mask!!.radius, 1e-3f)
    }

    @Test
    fun liveAndReplayGiveTheSameSecondaries() {
        val brush = AzphaltBrush(
            name = "Spray blot dual",
            spacing = 0.2f,
            maskedBrush = secondTip,
            blot = BrushBlot(lengthPx = 60f, extraStamps = 2),
            program = StrokeProgramSpec(step = "emit { x: px, y: py + 3, size: 4 };", tickMs = 16f),
        )
        val generator = IncrementalDynamicDabGenerator(diameter, brush, seed)
        val live = samples.flatMap { generator.append(it) }
        assertEquals(movement(brush).map { it.mask }, live.map { it.mask })
    }

    @Test
    fun singleTipBrushesAreUntouched() {
        val brush = AzphaltBrush(
            name = "Spray",
            spacing = 0.2f,
            program = StrokeProgramSpec(step = "emit { x: px, y: py, size: 6 };", tickMs = 16f),
        )
        assertTrue(movement(brush).all { it.mask == null })
    }
}
