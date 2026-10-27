package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrokeProgramLedgerTest {
    // ---- the ledger itself -------------------------------------------------------------------

    @Test
    fun filmDriesWithTime() {
        val ledger = PaintLedger(dryMs = 10_000f)
        val stroke = ledger.beginStroke()
        stroke.spray(100f, 100f, 40f, 100f, nowMs = 0L)
        ledger.overlay(stroke.film(0L))
        val wet = ledger.filmAt(100f, 100f, 0L)
        assertTrue(wet > 50f, "wet=$wet")
        assertEquals(wet * 0.3679f, ledger.filmAt(100f, 100f, 10_000L), wet * 0.01f)
        assertTrue(ledger.filmAt(100f, 100f, 120_000L) < 0.01f)
    }

    @Test
    fun aStrokesWritesStayPrivateUntilApplied() {
        val ledger = PaintLedger()
        val a = ledger.beginStroke()
        a.spray(50f, 50f, 30f, 80f, 0L)
        assertTrue(a.wetAt(50f, 50f, 30f, 0L) > 20f)
        assertEquals(0f, ledger.filmAt(50f, 50f, 0L))
        assertEquals(0f, ledger.beginStroke().wetAt(50f, 50f, 30f, 0L))
        ledger.overlay(a.film(0L))
        assertTrue(ledger.beginStroke().wetAt(50f, 50f, 30f, 0L) > 20f)
    }

    @Test
    fun aSnapshotRebuildsTheLedgerExactly() {
        val live = PaintLedger()
        val first = live.beginStroke()
        first.spray(60f, 60f, 50f, 70f, 0L)
        first.spray(70f, 60f, 50f, 70f, 500L)
        live.overlay(first.film(500L))
        val second = live.beginStroke()
        second.spray(65f, 62f, 50f, 40f, 4_000L)
        second.drain(65f, 62f, 20f, 30f, 4_500L)
        live.overlay(second.film(4_500L))

        val rebuilt = live.snapshot(4_500L).toLedger()
        for (probe in listOf(60f to 60f, 70f to 60f, 65f to 62f, 100f to 60f)) {
            assertEquals(live.filmAt(probe.first, probe.second, 9_000L), rebuilt.filmAt(probe.first, probe.second, 9_000L))
        }
    }

    @Test
    fun drainNeverGoesBelowDry() {
        val stroke = PaintLedger().beginStroke()
        stroke.spray(40f, 40f, 24f, 20f, 0L)
        stroke.drain(40f, 40f, 24f, 500f, 0L)
        assertEquals(0f, stroke.wetAt(40f, 40f, 24f, 0L), 1e-4f)
    }

    // ---- programs reading and writing it -----------------------------------------------------

    private fun sample(t: Long, x: Float = 100f, y: Float = 100f, phase: BrushContactPhase = BrushContactPhase.CONTACT) =
        BrushSample(
            x = x, y = y, uptimeMillis = t,
            telemetry = BrushTelemetryMetadata(contactPhase = phase),
        )

    private fun brush(step: String, tickMs: Float = 0f, state: Map<String, Float> = emptyMap()) = AzphaltBrush(
        name = "spray",
        spacing = 0.25f,
        program = StrokeProgramSpec(state = state, step = step, tickMs = tickMs),
    )

    @Test
    fun aHeldPenKeepsSteppingOnTheClock() {
        val b = brush("hide = 1; emit { x: px, y: py, size: 6 };", tickMs = 16f)
        val samples = (0..20).map { sample(it * 16L) }
        val dabs = LiveStrokeDabs.movementDabs(samples, 20f, b, 1L)
        // The touchdown step plus one tick per 16 ms of hold.
        assertEquals(1 + 20, dabs.size)
        assertTrue(dabs.all { it.x == 100f && it.y == 100f })
    }

    @Test
    fun withoutATickMsAHeldPenPaintsNothingNew() {
        val b = brush("hide = 1; emit { x: px, y: py, size: 6 };")
        val dabs = LiveStrokeDabs.movementDabs((0..20).map { sample(it * 16L) }, 20f, b, 1L)
        assertEquals(1, dabs.size)
    }

    @Test
    fun theProgramSeesTheHoldAndTheLift() {
        val b = brush(
            """
            hide = 1;
            emit { x: px, y: py, size: 4 + phase, alpha: 1 };
            """.trimIndent(),
            tickMs = 50f,
        )
        val samples = listOf(sample(0), sample(60), sample(120, phase = BrushContactPhase.LIFT_OFF))
        val sizes = LiveStrokeDabs.movementDabs(samples, 20f, b, 1L).map { it.radius * 2f }
        // touchdown (phase 0), tick at 50 (2), tick at 100 (2), lift (3)
        assertEquals(listOf(4f, 6f, 6f, 7f), sizes)
    }

    @Test
    fun liftRunsOnceEvenIfTheSampleRepeats() {
        val b = brush("if (phase == 3) { emit { x: px, y: py, size: 9 }; } hide = 1;", tickMs = 50f)
        val samples = listOf(sample(0), sample(20, phase = BrushContactPhase.LIFT_OFF), sample(40, phase = BrushContactPhase.LIFT_OFF))
        assertEquals(1, LiveStrokeDabs.movementDabs(samples, 20f, b, 1L).count { it.radius == 4.5f })
    }

    @Test
    fun layerSpeedFollowsTheDrawnLine() {
        val b = brush("hide = 1; emit { x: px, y: py, size: 2 + layerSpeed };")
        // 10 layer px every 10 ms: 1 px/ms.
        val samples = (0..30).map { sample(it * 10L, x = it * 10f) }
        val sizes = LiveStrokeDabs.movementDabs(samples, 20f, b, 1L).map { it.radius * 2f - 2f }
        assertTrue(sizes.last() in 0.8f..1.1f, "speed=${sizes.last()}")
        assertEquals(0f, sizes.first(), 1e-4f)
    }

    @Test
    fun thePenAngleTravelsWithTheSample() {
        val b = brush("hide = 1; emit { x: px, y: py, size: 2, angle: penAngle };")
        val samples = (0..3).map {
            sample(it * 16L, x = it * 10f).let { s -> s.copy(telemetry = s.telemetry.copy(penAngleDegrees = 70f)) }
        }
        val angles = LiveStrokeDabs.movementDabs(samples, 20f, b, 1L).map { it.angleDeg }
        assertTrue(angles.isNotEmpty() && angles.all { it == 70f }, "angles=$angles")
        // Nothing set: the default hand.
        val plain = LiveStrokeDabs.movementDabs(listOf(sample(0)), 20f, b, 1L).single().angleDeg
        assertEquals(DEFAULT_PEN_ANGLE_DEG, plain)
    }

    @Test
    fun penAnglesWrap() {
        assertEquals(-170f, wrapPenAngle(190f))
        assertEquals(40f, wrapPenAngle(400f))
        assertEquals(DEFAULT_PEN_ANGLE_DEG, wrapPenAngle(DEFAULT_PEN_ANGLE_DEG))
        assertEquals(DEFAULT_PEN_ANGLE_DEG, wrapPenAngle(Float.NaN))
    }

    private val sprayAndDrip = """
        spray(px, py, diameter * 0.5, 40);
        let film = wetAt(px, py, diameter * 0.5);
        hide = 1;
        if (film > 100) { emit { x: px, y: py + 30, size: 5 }; }
    """.trimIndent()

    @Test
    fun aSecondBurstOverTheSameSpotDripsWhereTheFirstDidNot() {
        val b = brush(sprayAndDrip, tickMs = 16f)
        val ledger = PaintLedger()
        fun burst(t0: Long): Int {
            val samples = (0..2).map { sample(t0 + it * 16L, phase = if (it == 2) BrushContactPhase.LIFT_OFF else BrushContactPhase.CONTACT) }
            val result = LiveStrokeDabs.movementStroke(samples, 40f, b, 3L, ledger.snapshot(t0))
            result.film?.let(ledger::overlay)
            return result.dabs.size
        }
        val first = burst(0L)
        val second = burst(2_000L)
        val third = burst(4_000L)
        assertEquals(0, first, "a first burst on a dry wall")
        assertTrue(second > 0 && third >= second, "dabs: $first $second $third")
    }

    @Test
    fun theSameBurstOnADryWallNeverDrips() {
        val b = brush(sprayAndDrip, tickMs = 16f)
        val ledger = PaintLedger()
        fun burst(t0: Long): Int {
            val samples = (0..2).map { sample(t0 + it * 16L) }
            val result = LiveStrokeDabs.movementStroke(samples, 40f, b, 3L, ledger.snapshot(t0))
            result.film?.let(ledger::overlay)
            return result.dabs.size
        }
        // Minutes apart: the first burst has dried by the next.
        val sizes = listOf(0L, 600_000L, 1_200_000L).map(::burst)
        assertEquals(sizes[0], sizes[1])
        assertEquals(sizes[1], sizes[2])
    }

    @Test
    fun aReplayWithTheSamePriorReproducesTheStrokeAndItsFilm() {
        val b = brush(sprayAndDrip, tickMs = 16f)
        val earlier = LiveStrokeDabs.movementStroke((0..6).map { sample(it * 16L) }, 40f, b, 1L, null)
        val prior = earlier.film!!
        val samples = (0..8).map { sample(3_000L + it * 16L) }
        val one = LiveStrokeDabs.movementStroke(samples, 40f, b, 2L, prior)
        val two = LiveStrokeDabs.movementStroke(samples, 40f, b, 2L, prior)
        assertEquals(one.dabs, two.dabs)
        assertEquals(one.film, two.film)
    }

    @Test
    fun aStrokeWithoutALedgerStartsDry() {
        val b = brush(sprayAndDrip, tickMs = 16f)
        val samples = (0..4).map { sample(it * 16L) }
        assertEquals(
            LiveStrokeDabs.movementDabs(samples, 40f, b, 1L),
            LiveStrokeDabs.movementStroke(samples, 40f, b, 1L, PaintLedger().snapshot(0L)).dabs,
        )
    }

    @Test
    fun ledgerCallsAreRefusedAtCompileWhenMisused() {
        val message = (StrokeProgram.compile(StrokeProgramSpec(step = "spray(1, 2, 3);")) as StrokeProgramCompilation.Error).message
        assertTrue("takes 4 argument" in message, message)
    }
}
