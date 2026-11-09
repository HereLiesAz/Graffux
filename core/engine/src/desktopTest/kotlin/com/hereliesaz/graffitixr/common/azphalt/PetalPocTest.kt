package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the spec/painterly.md phase-P1 proof of concept (`tools/painterly-poc`): a loaded flat built
 * from today's features swells with pressure, closes to a point on lift, stays inside the spec's op
 * budget, and replays identically live and from scratch.
 */
class PetalPocTest {
    private fun brush(): AzphaltBrush {
        val manifest = File("../../tools/painterly-poc/com.hereliesaz.azphalt.painterly.poc/manifest.json")
        assertTrue(manifest.isFile, "missing ${manifest.absolutePath}")
        val root = AzphaltJson.parseToJsonElement(manifest.readText()) as JsonObject
        val asset = (root["assets"] as JsonArray).first() as JsonObject
        return AzphaltBrush.fromParams("Petal", asset["params"] as JsonObject)
    }

    /** One pull: pressure rises to 1 at the middle and falls to ~0, pen moving, lifts on the last sample. */
    private fun pull(): List<BrushSample> {
        val builder = BrushSampleBuilder()
        val n = 90
        val out = (0..n).map { i ->
            val t = i / n.toFloat()
            builder.add(20f + 200f * t, 80f - 30f * t, uptimeMillis = i * 8L, pressure = maxOf(0.05f, sin(t * PI.toFloat())))
        }.toMutableList()
        val last = out.removeAt(out.lastIndex)
        out.add(last.copy(telemetry = last.telemetry.copy(contactPhase = BrushContactPhase.LIFT_OFF)))
        return out
    }

    private fun spread(dabs: List<Dab>, from: Float, to: Float): Float {
        val ys = dabs.filter { it.x in from..to }
        if (ys.isEmpty()) return 0f
        // perpendicular extent across the stroke (heading is nearly +x with a gentle rise)
        val lo = ys.minOf { it.y - 30f * (it.x - 20f) / 200f }
        val hi = ys.maxOf { it.y - 30f * (it.x - 20f) / 200f }
        return hi - lo
    }

    @Test
    fun theBrushCompilesAndStaysInBudget() {
        val b = brush()
        assertNotNull(b.program)
        val gen = IncrementalDynamicDabGenerator(34f, b, 7L)
        pull().forEach { gen.append(it) }
        val runner = assertNotNull(gen.programRunner)
        assertEquals(0, runner.overruns)
        assertTrue(runner.peakOps <= 8_000, "peakOps=${runner.peakOps}")
    }

    @Test
    fun widthSwellsWithPressureAndClosesToAPointOnLift() {
        val samples = pull()
        val dabs = LiveStrokeDabs.movementDabs(samples, 34f, brush(), 7L)
        val early = spread(dabs, 20f, 50f)
        val middle = spread(dabs, 100f, 140f)
        assertTrue(middle > early * 1.5f, "swell: early=$early middle=$middle")
        val lastX = samples.last().x
        val tail = dabs.filter { it.x > lastX + 1f }
        assertTrue(tail.isNotEmpty(), "a tail lies ahead of the lift-off point")
        val tip = tail.maxByOrNull { it.x }!!
        val tipHalfWidth = tip.radius * tip.tipRatio
        assertTrue(tipHalfWidth <= 3f, "the tail ends in a point, half width=$tipHalfWidth")
        assertTrue(hypot(tip.x - lastX, 0f) > 2f)
    }

    @Test
    fun liveAndReplayAgree() {
        val b = brush()
        val samples = pull()
        assertEquals(
            IncrementalDynamicDabGenerator(34f, b, 7L).appendLive(samples),
            LiveStrokeDabs.movementDabs(samples, 34f, b, 7L),
        )
    }
}
