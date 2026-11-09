package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** spec/painterly.md P7b: stroke capture, shareable recording, deterministic replay. */
class StrokeRecordingTest {
    private val brush = AzphaltBrush.fromParams(
        "Petal",
        Json.parseToJsonElement(
            """{"painterly":1,"load":{"lanes":8,"fill":[{"pigment":"cadmium-red","lanes":[0,7],"amount":1}]},
                "program":{"step":"hide = 1; emit { x: px, y: py + noise(index * 0.3) * 6, size: 5, lane: 0, draw: 0.01, h: 1.5 };\nif (phase == 3) { emit { x: px + 12, y: py, size: 3 }; }"}}""",
        ) as JsonObject,
    )

    private fun samples(): List<BrushSample> {
        val b = BrushSampleBuilder()
        val out = (0..40).map { i -> b.add(10f + 4f * i, 30f + 0.3f * i, i * 8L, 0.3f + 0.6f * kotlin.math.sin(i / 40f * 3.14159f)) }.toMutableList()
        val last = out.removeAt(out.lastIndex)
        out.add(last.copy(telemetry = last.telemetry.copy(contactPhase = BrushContactPhase.LIFT_OFF)))
        return out
    }

    private fun record(seed: Long = 11L) = StampStrokeRecord(
        brush = brush, brushRef = "painterly.acrylic/Petal", seed = seed, diameterPx = 30f,
        colorArgb = 0xFFCC2222.toInt(), samples = samples(),
    )

    private fun recording(vararg r: StampStrokeRecord) = StrokeRecording(name = "demo", canvasWidth = 256, canvasHeight = 256, strokes = r.toList())

    @Test
    fun aRecordingSurvivesJsonAndReplaysToTheSameDabs() {
        val original = recording(record(1), record(2))
        val back = assertNotNull(StrokeRecording.fromJson(original.toJson()))
        assertEquals(original, back)
        val a = StrokeRecordingPlayer.play(original)
        val b = StrokeRecordingPlayer.play(back)
        assertEquals(a, b, "replay after a save/share round trip paints the same dabs")
        assertTrue(a.all { it.isNotEmpty() })
    }

    @Test
    fun replayEqualsTheLiveGeneratorAndTheSeedMatters() {
        val r = record(5)
        val live = IncrementalDynamicDabGenerator(30f, brush, 5L).appendLive(samples())
        assertEquals(live, StrokeRecordingPlayer.dabsOf(r))
        assertTrue(StrokeRecordingPlayer.dabsOf(record(5)) == StrokeRecordingPlayer.dabsOf(record(5)))
        // a different seed is a different stroke for a seeded program
        val seeded = brush.copy(program = brush.program!!.copy(step = "hide = 1; emit { x: px + rands() * 5, y: py, size: 4 };"))
        val a = StrokeRecordingPlayer.dabsOf(r.copy(brush = seeded, seed = 1))
        val b = StrokeRecordingPlayer.dabsOf(r.copy(brush = seeded, seed = 2))
        assertTrue(a != b)
    }

    @Test
    fun theEmbeddedBrushIsWhatReplays_NotALookup() {
        val json = recording(record()).toJson()
        // the brush travels inside the recording, so it replays with no pack installed
        assertTrue("\"painterly\"" in json && "lane" in json)
        assertEquals(StrokeRecordingPlayer.play(recording(record())), StrokeRecordingPlayer.play(StrokeRecording.fromJson(json)!!))
    }

    @Test
    fun theWetFilmIsThreadedThroughTheStrokesAsTheEditorDoes() {
        val ledgerBrush = AzphaltBrush(
            name = "wet", spacing = 0.25f,
            program = StrokeProgramSpec(step = "spray(px, py, 6, 5); hide = 1; emit { x: px, y: py + wetAt(px, py, 6), size: 3 };"),
        )
        fun r(seed: Long) = StampStrokeRecord(brush = ledgerBrush, seed = seed, diameterPx = 20f, colorArgb = 0, samples = samples())
        val together = StrokeRecordingPlayer.play(recording(r(1), r(2)))
        val aloneSecond = StrokeRecordingPlayer.dabsOf(r(2))
        assertTrue(together[1] != aloneSecond, "the second stroke feels the first one's wet paint")
        val film = RecordedFilm.of(assertNotNull(StrokeRecordingPlayer.filmOf(r(1))))
        val recorded = StrokeRecordingPlayer.dabsOf(r(2).copy(priorFilm = film))
        assertEquals(together[1], recorded, "a recorded prior film reproduces it")
    }

    @Test
    fun badRecordingsAreRefusedWhole() {
        assertNull(StrokeRecording.fromJson("not json"))
        assertNull(StrokeRecording.fromJson(recording(record()).copy(format = "azphalt-strokes/9").toJson()))
        assertNull(StrokeRecording.fromJson(StrokeRecording(canvasWidth = 0, canvasHeight = 10, strokes = listOf(record())).toJson()))
        assertNull(StrokeRecording.fromJson(StrokeRecording(canvasWidth = 10, canvasHeight = 10, strokes = listOf(record().copy(samples = emptyList()))).toJson()))
        assertNull(StrokeRecording.fromJson(StrokeRecording(canvasWidth = 10, canvasHeight = 10, strokes = listOf(record().copy(diameterPx = 0f))).toJson()))
        val backwards = samples().reversed()
        assertNull(StrokeRecording.fromJson(StrokeRecording(canvasWidth = 10, canvasHeight = 10, strokes = listOf(record().copy(samples = backwards))).toJson()))
    }

    @Test
    fun theProjectsOwnReaderDropsOnlyTheBadStrokes() {
        val json = recording(record(1), record(2), record(3)).toJson()
        // damage the middle stroke only: a zero brush size
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        val strokes = (root["strokes"] as kotlinx.serialization.json.JsonArray).toMutableList()
        val bad = (strokes[1] as kotlinx.serialization.json.JsonObject).toMutableMap().also { it["diameterPx"] = kotlinx.serialization.json.JsonPrimitive(0) }
        strokes[1] = kotlinx.serialization.json.JsonObject(bad)
        val damaged = kotlinx.serialization.json.JsonObject(root + ("strokes" to kotlinx.serialization.json.JsonArray(strokes))).toString()
        assertNull(StrokeRecording.fromJson(damaged), "a shared recording is still read strictly")
        val repaired = assertNotNull(StrokeRecording.fromJsonRepairing(damaged))
        assertEquals(1, repaired.dropped)
        assertEquals(listOf(1L, 3L), repaired.recording.strokes.map { it.seed })
    }

    @Test
    fun aStrokeThatIsNotEvenAStrokeIsDroppedAndAnUnsoundHeaderRefusesTheFile() {
        val ok = recording(record(1)).toJson()
        val junk = ok.replace("\"strokes\":[", "\"strokes\":[42,")
        assertEquals(1, assertNotNull(StrokeRecording.fromJsonRepairing(junk)).dropped)
        // AzphaltJson omits the default format, so state an unknown one explicitly
        assertNull(StrokeRecording.fromJsonRepairing(ok.replaceFirst("{", "{\"format\":\"other/9\",")))
        assertNull(StrokeRecording.fromJsonRepairing("not json"))
        assertEquals(0, assertNotNull(StrokeRecording.fromJsonRepairing(ok)).dropped)
    }
}
