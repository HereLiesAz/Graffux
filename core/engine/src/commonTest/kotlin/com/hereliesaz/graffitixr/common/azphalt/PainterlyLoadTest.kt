package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** spec/painterly.md phase P4: brush load config, the Kotlin load budget, program functions and emit intents. */
class PainterlyLoadTest {
    private val params = """
        {"painterly":1,"load":{"lanes":8,"depth":2,"capacity":1,
          "fill":[{"pigment":"cadmium-red","lanes":[0,1],"amount":0.9},
                  {"pigment":"cadmium-red:1+titanium-white:1","lanes":[2,5],"amount":1.0},
                  {"pigment":"cadmium-red","lanes":[6,7],"amount":0.9}]},
         "program":{"step":"hide = 1; emit { x: px, y: py, size: 4, lane: -1, draw: 0.1 };"}}
    """.trimIndent()

    private fun brush(json: String = params) = AzphaltBrush.fromParams("p", Json.parseToJsonElement(json) as JsonObject)

    private fun samples() = BrushSampleBuilder().let { b -> (0..60 step 2).mapIndexed { i, x -> b.add(x.toFloat(), 40f, i * 8L, 0.8f) } }

    @Test
    fun loadParsesAndSanitises() {
        val load = assertNotNull(brush().load)
        assertEquals(8, load.lanes)
        assertEquals(3, load.fill.size)
        val wild = brush("""{"painterly":1,"load":{"lanes":999,"depth":99,"capacity":-3,"fill":[{"pigment":"x","lanes":[9,-4],"amount":7}]}}""").load!!
        assertEquals(BrushLoadConfig.MAX_LANES, wild.lanes)
        assertEquals(BrushLoadConfig.MAX_DEPTH, wild.depth)
        assertTrue(wild.capacity > 0f)
        assertEquals(0, wild.fill[0].laneFrom)
        assertEquals(9, wild.fill[0].laneTo)
        assertEquals(1f, wild.fill[0].amount)
    }

    @Test
    fun aBrushWithoutThePainterlyFlagHasNoLoadAndIsOtherwiseUnchanged() {
        val plain = brush("""{"load":{"lanes":8},"spacing":0.2}""")
        assertNull(plain.load)
        assertEquals(0, plain.painterly)
        assertEquals(AzphaltBrush(name = "p", spacing = 0.2f), plain)
    }

    @Test
    fun theBudgetReportsFillMixAndDepletesTipFirst() {
        val b = BrushLoadBudget(brush().load!!)
        assertEquals(0.9f, b.amount(-1f), 1e-5f)
        assertEquals(1f, b.amount(0f), 1e-5f)
        assertEquals(0f, b.mix(-1f))
        assertEquals(0.5f, b.mix(0f), 1e-5f)
        val before = b.amount(-1f)
        val taken = b.consume(-1f, 0.2f)
        assertEquals(0.2f, taken, 1e-6f)
        assertTrue(b.amount(-1f) < before)
        // asking for more than is left gives only what is left, and never goes negative
        assertEquals(0.7f, b.consume(-1f, 5f), 1e-5f)
        assertEquals(0f, b.amount(-1f), 1e-6f)
        assertEquals(0f, b.consume(-1f, 0.1f))
        assertEquals(0f, b.consume(0f, -1f))
        assertEquals(0f, b.consume(0f, Float.NaN))
    }

    @Test
    fun laneCoordinatesMapToColumns() {
        val b = BrushLoadBudget(BrushLoadConfig(lanes = 5))
        assertEquals(listOf(0, 0, 2, 4, 4), listOf(-1f, -5f, 0f, 1f, 9f).map(b::laneIndex))
        assertEquals(2, b.laneIndex(Float.NaN))
    }

    @Test
    fun recipesParse() {
        assertEquals(listOf("a" to 0.5f, "b" to 0.5f), PigmentRecipe.parse("a+b"))
        assertEquals(listOf("a" to 0.25f, "b" to 0.75f), PigmentRecipe.parse("a:1 + b:3"))
        assertEquals(emptyList(), PigmentRecipe.parse("  "))
    }

    @Test
    fun emitIntentsReachTheDabAndDefaultToNone() {
        val b = brush("""{"painterly":1,"program":{"step":"hide = 1; emit { x: px, y: py, size: 4, lane: 0.5, draw: 0.25, lift: 0.1, mixWet: 3, dispose: 1 }; emit { x: px, size: 2 };"}}""")
        val dabs = LiveStrokeDabs.movementDabs(samples(), 16f, b, 3L)
        val withIntent = dabs.filter { it.hasLaneIntent }
        val without = dabs.filter { !it.hasLaneIntent }
        assertTrue(withIntent.isNotEmpty() && without.isNotEmpty())
        assertTrue(withIntent.all { it.lane == 0.5f && it.draw == 0.25f && it.lift == 0.1f && it.mixWet == 1f && it.dispose })
        assertTrue(without.all { it.lane == Dab.NO_LANE && it.draw == 0f && !it.dispose })
        val plain = LiveStrokeDabs.movementDabs(samples(), 16f, AzphaltBrush(name = "n", spacing = 0.25f), 3L)
        assertTrue(plain.none { it.hasLaneIntent })
    }

    @Test
    fun theBudgetIsVisibleToTheProgramAndDepletesAsItDraws() {
        val step = """
            hide = 1;
            emit { x: px, y: py, size: 4, lane: -1, draw: 0.05 };
            emit { x: loadAmount(-1) * 100, y: 0, size: 2 };
        """.trimIndent()
        val b = brush(
            """{"painterly":1,"load":{"lanes":8,"depth":2,"fill":[{"pigment":"a","lanes":[0,7],"amount":1}]},"program":{"step":${Json.encodeToString(kotlinx.serialization.serializer<String>(), step)}}}""",
        )
        val reads = LiveStrokeDabs.movementDabs(samples(), 16f, b, 3L).filter { !it.hasLaneIntent }.map { it.x }
        assertTrue(reads.size > 4)
        assertTrue(reads.zipWithNext().all { (a, c) -> c <= a }, "the predicted amount only falls: $reads")
        assertTrue(reads.last() < reads.first())
        assertTrue(reads.all { it >= 0f })
    }

    @Test
    fun theLegacyLedgerIsRefusedOnAPainterlyBrushButStillWorksElsewhere() {
        val legacy = StrokeProgramSpec(step = "drain(px, py, 5, 1);")
        assertTrue(StrokeProgram.compile(legacy) is StrokeProgramCompilation.Ok)
        val painterly = StrokeProgram.compile(legacy.copy(painterly = true))
        val message = (painterly as StrokeProgramCompilation.Error).message
        assertTrue("legacy wet ledger" in message, message)
        // and the load functions need the painterly flag
        val load = StrokeProgramSpec(step = "alphaMul = loadAmount(0);")
        assertTrue(StrokeProgram.compile(load) is StrokeProgramCompilation.Error)
        assertTrue(StrokeProgram.compile(load.copy(painterly = true)) is StrokeProgramCompilation.Ok)
    }

    @Test
    fun liveAndReplayAgreeAndAFreshStrokeStartsFromTheDeclaredFill() {
        val b = brush()
        val s = samples()
        val live = IncrementalDynamicDabGenerator(16f, b, 9L).appendLive(s)
        assertEquals(live, LiveStrokeDabs.movementDabs(s, 16f, b, 9L))
        assertEquals(LiveStrokeDabs.movementDabs(s, 16f, b, 9L), LiveStrokeDabs.movementDabs(s, 16f, b, 9L))
        assertFalse(live.isEmpty())
    }

    @Test
    fun loadMixIsTheSecondPigmentsShareEvenWithThreePigments() {
        val b = BrushLoadBudget(
            BrushLoadConfig(lanes = 3, fill = listOf(BrushLoadFill("a:1+b:1+c:2", 0, 0, 1f), BrushLoadFill("a:1", 1, 1, 1f), BrushLoadFill("a:3+b:1", 2, 2, 1f))),
        )
        assertEquals(0.25f, b.mix(-1f), 1e-6f, "a:1+b:1+c:2 -> b is 1/4, not 3/4")
        assertEquals(0f, b.mix(0f), "a single pigment has no second")
        assertEquals(0.25f, b.mix(1f), 1e-6f)
    }

    private fun program(step: String, load: String = """"load":{"lanes":1,"depth":1,"capacity":1,"fill":[{"pigment":"a","lanes":[0,0],"amount":1}]}""") =
        AzphaltBrush.fromParams(
            "p",
            Json.parseToJsonElement("""{"painterly":1,$load,"program":{"step":${Json.encodeToString(kotlinx.serialization.serializer<String>(), step)}}}""") as JsonObject,
        )

    @Test
    fun aDrawThatExhaustsAColumnIsSeenByLaterStatementsInTheSameStep() {
        // the column holds 1.0; the first emit draws it all; the second branch must see it empty
        val b = program(
            """
            hide = 1;
            emit { x: px, y: py, size: 4, lane: 0, draw: 1 };
            if (loadAmount(0) > 0) { emit { x: px, y: py + 20, size: 4 }; }
            emit { x: px, y: py + 40, size: 4 };
            """.trimIndent(),
        )
        val dabs = LiveStrokeDabs.movementDabs(samples(), 16f, b, 3L)
        assertTrue(dabs.any { it.y == 40f })
        assertTrue(dabs.none { it.y == 60f }, "no branch ran after the column was empty")
        // the first step drew the whole column; from then on every step finds it empty
        val gen = IncrementalDynamicDabGenerator(16f, b, 3L)
        samples().take(5).forEach { gen.append(it) }
        assertEquals(0f, gen.loadRemaining!!, 1e-6f)
    }

    @Test
    fun sameStepDepletionIsIdenticalLiveAndOnReplay() {
        val b = program("hide = 1; emit { x: px, y: py, size: 4, lane: 0, draw: 0.2 }; if (loadAmount(0) > 0.3) { emit { x: px, y: py + 9, size: 3 }; }")
        val s = samples()
        val live = IncrementalDynamicDabGenerator(16f, b, 9L).appendLive(s)
        assertEquals(live, LiveStrokeDabs.movementDabs(s, 16f, b, 9L))
        assertTrue(live.any { it.y == 40f + 9f }, "the branch ran while paint remained")
        assertTrue(live.count { it.y == 40f + 9f } < live.count { it.hasLaneIntent }, "and stopped once it was spent")
    }

    @Test
    fun aStepThatRunsOutOfBudgetRollsTheLoadBackWithTheRestOfItsState() {
        val b = program(
            """
            emit { x: px, y: py, size: 4, lane: 0, draw: 0.5 };
            for i in 0..64 { for j in 0..64 { for k in 0..64 { let t = i * j * k; } } }
            """.trimIndent(),
        )
        val gen = IncrementalDynamicDabGenerator(16f, b, 3L)
        val before = gen.loadRemaining!!
        samples().take(4).forEach { gen.append(it) }
        assertTrue(gen.programRunner!!.overruns > 0, "the step overran its operation budget")
        assertEquals(before, gen.loadRemaining!!, 1e-6f, "and the draw it had queued was not spent")
    }
}
