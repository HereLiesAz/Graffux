package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrokeProgramTest {
    /** A left-to-right stroke of [length] px sampled every [stepPx], 8 ms apart. */
    private fun line(length: Int = 120, stepPx: Int = 2, pressure: Float = 0.8f): List<BrushSample> {
        val builder = BrushSampleBuilder()
        return (0..length step stepPx).mapIndexed { i, x ->
            builder.add(x.toFloat(), 40f, uptimeMillis = i * 8L, pressure = pressure)
        }
    }

    private fun brush(step: String, init: String = "", state: Map<String, Float> = emptyMap()) = AzphaltBrush(
        name = "program",
        spacing = 0.25f,
        program = StrokeProgramSpec(state = state, init = init, step = step),
    )

    private fun dabs(brush: AzphaltBrush, seed: Long = 11L, samples: List<BrushSample> = line()) =
        LiveStrokeDabs.movementDabs(samples, 16f, brush, seed)

    private fun error(step: String, init: String = "", state: Map<String, Float> = emptyMap()): String =
        (StrokeProgram.compile(StrokeProgramSpec(state, init, step)) as StrokeProgramCompilation.Error).message

    // ---- the language ------------------------------------------------------------------------

    @Test
    fun aProgramRoutesTheBrushThroughTheDynamicGenerator() {
        assertTrue(LiveStrokeDabs.usesDynamicGenerator(brush("sizeMul = 2;")))
        assertFalse(LiveStrokeDabs.usesDynamicGenerator(AzphaltBrush(name = "plain")))
    }

    @Test
    fun outputsReshapeTheBrushsOwnDabs() {
        val plain = dabs(AzphaltBrush(name = "p", spacing = 0.25f, taper = BrushTaper(startLengthPx = 0.001f)))
        val shaped = dabs(brush("sizeMul = 0.5; alphaMul = 0.5; shiftY = 3; rotate = 10;"))
        assertEquals(plain.size, shaped.size)
        val a = plain[5]
        val b = shaped[5]
        assertEquals(a.radius * 0.5f, b.radius, 1e-4f)
        assertEquals(a.alpha * 0.5f, b.alpha, 1e-4f)
        assertEquals(a.y + 3f, b.y, 1e-4f)
        assertEquals(a.angleDeg + 10f, b.angleDeg, 1e-4f)
    }

    @Test
    fun emitAddsDabsAndHideDropsTheBrushsOwn() {
        val plain = dabs(brush("")).size
        val extra = dabs(brush("emit { x: px, y: py + 10, size: 4 };"))
        assertEquals(plain * 2, extra.size)
        val replaced = dabs(brush("hide = 1; emit { x: px, y: py + 10, size: 4 };"))
        assertEquals(plain, replaced.size)
        assertTrue(replaced.all { it.radius == 2f && it.y == 50f })
    }

    @Test
    fun emitFieldsDefaultToTheBrushsOwnDab() {
        val out = dabs(brush("hide = 1; emit { x: px + 1 };"))
        val plain = dabs(brush(""))
        assertEquals(plain[3].radius, out[3].radius, 1e-5f)
        assertEquals(plain[3].x + 1f, out[3].x, 1e-4f)
    }

    @Test
    fun stateSurvivesFromStepToStep() {
        val out = dabs(brush("n += 1; hide = 1; emit { x: n, y: 0, size: 2 };", state = mapOf("n" to 0f)))
        assertEquals((1..out.size).map { it.toFloat() }, out.map { it.x })
    }

    @Test
    fun initRunsOnceBeforeTheFirstStep() {
        val out = dabs(brush("hide = 1; emit { x: seeded, y: 0, size: 2 };", init = "seeded = 7;", state = mapOf("seeded" to 0f)))
        assertTrue(out.all { it.x == 7f })
    }

    @Test
    fun loopsLocalsAndFunctionsEvaluate() {
        val out = dabs(
            brush(
                """
                hide = 1;
                let acc = 0;
                for i in 0..4 { acc += i; }
                emit { x: acc, y: sin(90) + cos(0) + floor(2.7) + clamp(9, 0, 3) + mod(7, 4) + abs(-1), size: 2 };
                """.trimIndent(),
            ),
        )
        assertEquals(6f, out.first().x) // 0+1+2+3
        assertEquals(1f + 1f + 2f + 3f + 3f + 1f, out.first().y, 1e-4f)
    }

    @Test
    fun comparisonsTernaryAndElseBranch() {
        val out = dabs(
            brush(
                """
                hide = 1;
                let a = 0;
                if (index < 3) { a = 1; } else if (index < 6) { a = 2; } else { a = 3; }
                emit { x: a, y: (index >= 3 && index < 6) ? 100 : 0, size: 2 };
                """.trimIndent(),
            ),
        )
        assertEquals(listOf(1f, 1f, 1f, 2f, 2f, 2f, 3f), out.take(7).map { it.x })
        assertEquals(100f, out[4].y)
        assertEquals(0f, out[1].y)
    }

    @Test
    fun divisionByZeroAndNonFiniteResultsAreHarmless() {
        val out = dabs(brush("hide = 1; emit { x: 1 / 0, y: log(0) + sqrt(-4), size: 2 };"))
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.x == 0f && it.y == 0f })
    }

    @Test
    fun gapMultiplierSpacesTheDabs() {
        val tight = dabs(brush("")).size
        val wide = dabs(brush("gapMul = 2;")).size
        assertTrue(wide < tight * 0.6f && wide > tight * 0.4f, "tight=$tight wide=$wide")
    }

    @Test
    fun inputsDescribeTheStroke() {
        val out = dabs(brush("hide = 1; emit { x: dist, y: pressure * 100, size: 2, angle: heading };"))
        assertEquals(0f, out.first().x)
        assertTrue(out.last().x > 80f)
        assertEquals(80f, out[2].y, 1.5f)
        assertEquals(0f, out[2].angleDeg, 1e-3f)
    }

    // ---- compile errors ----------------------------------------------------------------------

    @Test
    fun badProgramsAreRefusedWithAReason() {
        assertTrue("'nope' is not defined" in error("sizeMul = nope;"))
        assertTrue("read-only" in error("px = 3;"))
        assertTrue("at most 64" in error("for i in 0..65 { }"))
        assertTrue("whole-number literals" in error("for i in 0..px { }"))
        assertTrue("missing '}'" in error("if (1) { sizeMul = 2;"))
        assertTrue("not an emit field" in error("emit { colour: 1 };"))
        assertTrue("takes 2 argument" in error("sizeMul = max(1);"))
        assertTrue("not a function" in error("sizeMul = fetch(1);"))
        assertTrue("line 3" in error("sizeMul = 1;\nsizeMul = 2;\nsizeMul = ;"))
        assertTrue("shadows a built-in" in error("", state = mapOf("px" to 0f)))
        assertTrue("already defined" in error("let a = 1; let a = 2;"))
        assertTrue("nested" in error("if (1) { ".repeat(20) + "}".repeat(20)))
        assertTrue("expression nested" in error("sizeMul = " + "(".repeat(40) + "1" + ")".repeat(40) + ";"))
    }

    @Test
    fun aBrokenProgramLeavesAPlainBrush() {
        val broken = brush("sizeMul = nope;")
        assertTrue(dabs(broken).isNotEmpty())
        val plain = LiveStrokeDabs.movementDabs(line(), 16f, broken.copy(program = null), 11L)
        assertEquals(plain.size, dabs(broken).size)
    }

    // ---- limits ------------------------------------------------------------------------------

    @Test
    fun aStepOverItsBudgetPaintsTheBrushUnchangedAndKeepsItsState() {
        val spec = """
            n += 1;
            for a in 0..64 { for b in 0..64 { for c in 0..64 { n += 1; } } }
            emit { x: 0, y: 0, size: 5 };
        """.trimIndent()
        val plain = dabs(brush(""))
        val out = dabs(brush(spec, state = mapOf("n" to 0f)))
        assertEquals(plain.size, out.size)
        assertTrue(out.none { it.radius == 2.5f })
    }

    @Test
    fun emitsAreCappedPerStepAndPerStroke() {
        val perStep = dabs(brush("for i in 0..64 { for j in 0..64 { emit { size: 2 }; } }"))
        val plain = dabs(brush("")).size
        assertTrue(perStep.size <= plain + (plain * StrokeProgram.MAX_EMITS_PER_STEP))
        val long = line(length = 4000, stepPx = 1)
        val capped = LiveStrokeDabs.movementDabs(long, 16f, brush("for i in 0..64 { emit { size: 2 }; }"), 3L)
        val emitted = capped.size - LiveStrokeDabs.movementDabs(long, 16f, brush(""), 3L).size
        assertTrue(emitted <= StrokeProgram.MAX_EMITS_PER_STROKE, "emitted=$emitted")
    }

    @Test
    fun sourceLengthIsBounded() {
        val huge = "sizeMul = 1;\n".repeat(StrokeProgram.MAX_SOURCE_CHARS)
        assertTrue("longer than" in error(huge))
    }

    // ---- determinism and replay --------------------------------------------------------------

    private val randomProgram = """
        hide = 1;
        for i in 0..6 {
          emit { x: px + rands() * 6, y: py + noise2(index * 0.3, i) * 12, size: 2 + hash(i) * 4, alpha: 0.5 };
        }
    """.trimIndent()

    @Test
    fun theSameSamplesAndSeedReproduceEveryDab() {
        val b = brush(randomProgram)
        assertEquals(dabs(b, seed = 5L), dabs(b, seed = 5L))
        assertTrue(dabs(b, seed = 5L) != dabs(b, seed = 6L))
    }

    @Test
    fun liveAndReplayAgree() {
        val b = brush(randomProgram)
        val samples = line()
        val live = IncrementalDynamicDabGenerator(16f, b, 5L).appendLive(samples)
        assertEquals(live, LiveStrokeDabs.movementDabs(samples, 16f, b, 5L))
    }

    @Test
    fun aStrokeIsIndependentOfWhichStrokeCameBefore() {
        val b = brush("n += 1; hide = 1; emit { x: n, size: 2 };", state = mapOf("n" to 0f))
        dabs(b)
        assertEquals(1f, dabs(b).first().x)
    }

    // ---- lift-off tail (spec/painterly.md E6) --------------------------------------------------

    /** [samples] with the final one marked as the pen leaving the surface. */
    private fun lifted(samples: List<BrushSample>): List<BrushSample> {
        val last = samples.last()
        return samples.dropLast(1) + last.copy(
            telemetry = last.telemetry.copy(contactPhase = BrushContactPhase.LIFT_OFF),
        )
    }

    /** A tail: at lift-off (phase 3) lay three shrinking dabs ahead of the pen along +x, by pen speed. */
    private val tailProgram = """
        if (phase == 3) {
          let reach = speed * 200;
          for k in 0..3 {
            emit { x: px + reach * (k + 1) / 3, y: py, size: 6 - 2 * k, alpha: 1 };
          }
        }
    """.trimIndent()

    @Test
    fun aLiftOffTailIsPaintedPastTheLastSample() {
        val samples = lifted(line())
        val last = samples.last()
        val out = dabs(brush(tailProgram), samples = samples)
        val tail = out.filter { it.x > last.x + 0.5f }
        assertEquals(3, tail.size, "three tail dabs")
        assertEquals(listOf(6f, 4f, 2f), tail.map { it.radius * 2f })
        assertTrue(tail.zipWithNext().all { (a, b) -> b.x > a.x }, "the tail runs ahead of the pen")
    }

    @Test
    fun noTailWithoutALiftOffSample() {
        val samples = line()
        val out = dabs(brush(tailProgram), samples = samples)
        assertTrue(out.none { it.x > samples.last().x + 0.5f })
    }

    @Test
    fun theTailFollowsPenSpeedAndVanishesAtRest() {
        val moving = lifted(line())
        val builder = BrushSampleBuilder()
        // The same stroke, then the pen sits still for 400 ms before it lifts.
        val resting = lifted(
            moving.dropLast(1) + (1..4).map { i ->
                builder.add(moving[moving.size - 2].x, 40f, uptimeMillis = moving.size * 8L + i * 100L, pressure = 0.8f)
            },
        )
        val reachMoving = dabs(brush(tailProgram), samples = moving).filter { it.x > moving.last().x + 0.5f }
        assertEquals(3, reachMoving.size)
        val restLast = resting.last()
        val reachResting = dabs(brush(tailProgram), samples = resting).filter { it.x > restLast.x + 0.5f }
        assertTrue(reachResting.isEmpty() || reachResting.last().x - restLast.x < reachMoving.last().x - moving.last().x)
    }

    @Test
    fun theTailIsTheSameLiveAndOnReplay() {
        val b = brush(tailProgram)
        val samples = lifted(line())
        val live = IncrementalDynamicDabGenerator(16f, b, 5L).appendLive(samples)
        assertEquals(live, LiveStrokeDabs.movementDabs(samples, 16f, b, 5L))
    }

    @Test
    fun theTailIsLaidOnlyOnce() {
        val samples = lifted(line())
        // A repeated lift-off sample (a duplicate event) must not paint a second tail.
        val out = dabs(brush(tailProgram), samples = samples + samples.last())
        assertEquals(3, out.count { it.x > samples.last().x + 0.5f })
    }

    // ---- package parsing ---------------------------------------------------------------------

    @Test
    fun aPackageDeclaresAProgramAsLinesOrAString() {
        val json = Json.parseToJsonElement(
            """{"spacing":0.2,"program":{"state":{"load":1,"bad":"x"},"init":"load = 1;","step":["load -= 0.01;","sizeMul = load;"]}}""",
        ) as JsonObject
        val brush = AzphaltBrush.fromParams("pack", json)
        val program = assertNotNull(brush.program)
        assertEquals(mapOf("load" to 1f), program.state)
        assertEquals("load -= 0.01;\nsizeMul = load;", program.step)
        assertTrue(StrokeProgram.compile(program) is StrokeProgramCompilation.Ok)
    }

    @Test
    fun noStepMeansNoProgram() {
        val json = Json.parseToJsonElement("""{"program":{"init":"x = 1;"}}""") as JsonObject
        assertNull(AzphaltBrush.fromParams("pack", json).program)
        assertNull(AzphaltBrush.fromParams("pack", Json.parseToJsonElement("""{"program":"sizeMul = 2;"}""") as JsonObject).program)
    }
}
