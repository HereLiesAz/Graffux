package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Review items 6 and 7 of the unreviewed #771 diff, as tests only: the multi-lane load bookkeeping and the replay of
 * a stroke that started from the palette's load. A failure here is a finding, not something to patch around.
 */
class PaletteStrokeLoadReviewTest {
    private val set = PlaceholderPigments.set
    private val tubes = listOf("titanium-white", "cadmium-yellow", "phthalo-blue").map { id -> set.pigments.first { it.id == id } }

    private fun program(laneExpr: String) = AzphaltBrush.fromParams(
        "p",
        Json.parseToJsonElement(
            """{"painterly":1,"load":{"lanes":8},"program":{"step":"hide = 1; emit { x: px, y: py, size: 4, lane: $laneExpr, draw: 0.05 };"}}""",
        ) as JsonObject,
    )

    private fun samples() = BrushSampleBuilder().let { b -> (0..160 step 2).mapIndexed { i, x -> b.add(x.toFloat(), 40f, i * 8L, 0.8f) } }

    private fun loaded(): PaletteBoard = PaletteBoard(48, tubes).also {
        it.squeeze(1, 12f, 12f, 8f, 1f)
        repeat(5) { _ -> it.dip(12f, 12f) }
    }

    /** The editor's bookkeeping, as EditorViewModel does it: start fill, run, share left, absorb. */
    private fun strokeAndAbsorb(b: PaletteBoard, brush: AzphaltBrush): Pair<Float, Float> {
        val start = b.brush.amount[0]
        val load = assertNotNull(b.brushLoad())
        val startFill = load.fill.first().amount
        val gen = IncrementalDynamicDabGenerator(16f, brush.copy(load = load), 5L)
        samples().forEach { gen.append(it) }
        val remaining = assertNotNull(gen.loadRemaining)
        b.absorbStrokeLoad(remaining / startFill, start)
        return start to b.brush.amount[0]
    }

    @Test
    fun aMultiLaneStrokeThatSpendsEveryLaneDepletesTheBoardBrushByTheMeanShare() {
        val b = loaded()
        // lane cycles over all eight columns
        val (start, after) = strokeAndAbsorb(b, program("index % 8"))
        assertTrue(after < start, "the board brush holds less after a stroke that used every lane: $start -> $after")
        assertTrue(after >= 0f)
    }

    @Test
    fun aStrokeThatEmptiesOneLaneOfEightIsReportedAsTheMeanNotAsEmpty() {
        val b = loaded()
        val (start, after) = strokeAndAbsorb(b, program("0"))
        // lane 0 is long spent, but seven columns are still full: the mean says the brush is mostly full
        assertTrue(after < start, "some paint was spent: $start -> $after")
        assertTrue(after > start * 0.5f, "the brush is not reported empty while seven lanes are full: $start -> $after")
    }

    // FINDING (reported, not fixed here): PaletteBoard.brushLoad() builds a fresh BrushLoadConfig(lanes, fill) and the
    // editor replaces the brush's own `load` block with it, so a brush's own capacity (and any other fills) are lost
    // when the stroke starts from the palette. Remove @Ignore when brushLoad() carries the brush's config over.
    @Ignore("finding: palette load drops the brush's own capacity, see #780")
    @Test
    fun theLoadTheBoardBuildsKeepsTheBrushsOwnCapacityAndDepth() {
        val own = AzphaltBrush.fromParams(
            "p",
            Json.parseToJsonElement(
                """{"painterly":1,"load":{"lanes":8,"depth":3,"capacity":2},"program":{"step":"hide = 1; emit { x: px, y: py, size: 4, lane: 0, draw: 0.05 };"}}""",
            ) as JsonObject,
        )
        val built = assertNotNull(loaded().brushLoad(own.load!!.lanes))
        assertEquals(own.load!!.depth, built.depth, "the brush's own load depth survives the board's load")
        assertEquals(own.load!!.capacity, built.capacity, "and its capacity")
    }

    @Test
    fun replayOfAPaletteLoadedStrokeDoesNotDependOnTheBoardNow() {
        val b = loaded()
        val brush = program("0").copy(load = assertNotNull(b.brushLoad()))
        val record = StampStrokeRecord(brush = brush, seed = 5L, diameterPx = 16f, colorArgb = 0xFF000000.toInt(), samples = samples())
        val live = IncrementalDynamicDabGenerator(16f, brush, 5L).appendLive(samples())
        // the board changes afterwards (the brush is rinsed, paint added): the recorded brush carries its own load
        b.clean(PaletteBoard.Clean.RINSE)
        b.squeeze(2, 30f, 30f, 8f, 1f)
        assertEquals(live, StrokeRecordingPlayer.dabsOf(record), "undo/redo replays the stroke it painted")
        assertEquals(StrokeRecordingPlayer.dabsOf(record), StrokeRecordingPlayer.dabsOf(record))
    }
}
