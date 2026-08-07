package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering rules behind the shortcuts sheet, which are the whole feature.
 *
 * A "recent" strip that is not in recency order, or a "frequent" one that reshuffles whenever two
 * tools tie, is a strip the user cannot build a habit against — they would have to read it every
 * time instead of reaching for the position they remember.
 */
class ToolUsageTest {

    @Test
    fun `recording counts the tool and stamps the time`() {
        val u = ToolUsage.EMPTY.recording(Tool.BRUSH, 100L).recording(Tool.BRUSH, 200L)
        assertEquals(2, u.counts["BRUSH"])
        assertEquals(200L, u.lastUsedMs["BRUSH"])
    }

    @Test
    fun `Tool NONE is never recorded, because it is the absence of a tool`() {
        val u = ToolUsage.EMPTY.recording(Tool.NONE, 100L)
        assertTrue(u.counts.isEmpty())
        assertTrue(u.lastUsedMs.isEmpty())
    }

    @Test
    fun `most recent is newest first`() {
        val u = ToolUsage.EMPTY
            .recording(Tool.BRUSH, 100L)
            .recording(Tool.ERASER, 300L)
            .recording(Tool.FILL, 200L)
        assertEquals(listOf(Tool.ERASER, Tool.FILL, Tool.BRUSH), ToolRanking.mostRecent(u, 10))
    }

    @Test
    fun `most frequent is commonest first, and a tie breaks on recency not on map order`() {
        var u = ToolUsage.EMPTY
        repeat(3) { u = u.recording(Tool.BRUSH, 10L) }
        repeat(5) { u = u.recording(Tool.SMUDGE, 20L) }
        // FILL ties BRUSH on count but was used later, so it must come first of the two — and must
        // do so on every run, not whenever the map happens to iterate that way.
        repeat(3) { u = u.recording(Tool.FILL, 99L) }

        assertEquals(listOf(Tool.SMUDGE, Tool.FILL, Tool.BRUSH), ToolRanking.mostFrequent(u, 10))
    }

    @Test
    fun `a full tie on count and recency still has one fixed answer`() {
        val u = ToolUsage(
            counts = mapOf("ERASER" to 1, "BRUSH" to 1),
            lastUsedMs = mapOf("ERASER" to 5L, "BRUSH" to 5L),
        )
        // Enum order is the last resort, so this never flips between launches.
        assertEquals(listOf(Tool.BRUSH, Tool.ERASER), ToolRanking.mostFrequent(u, 10))
    }

    @Test
    fun `limits are honoured and a negative limit is empty rather than a crash`() {
        val u = ToolUsage.EMPTY.recording(Tool.BRUSH, 1L).recording(Tool.ERASER, 2L)
        assertEquals(1, ToolRanking.mostRecent(u, 1).size)
        assertEquals(1, ToolRanking.mostFrequent(u, 1).size)
        assertTrue(ToolRanking.mostRecent(u, -3).isEmpty())
    }

    @Test
    fun `favourites keep the order the user arranged them in`() {
        val ids = listOf("FILL", "BRUSH", "SMUDGE")
        assertEquals(listOf(Tool.FILL, Tool.BRUSH, Tool.SMUDGE), ToolRanking.favorites(ids, 10))
    }

    @Test
    fun `a stored name that is no longer a tool is dropped, not fatal`() {
        // CROP was removed from the enum during the audit. A device that had used it still has the
        // name in its preferences, and reading it must cost the ordering, not the launch.
        val ids = listOf("BRUSH", "CROP", "ERASER")
        assertEquals(listOf(Tool.BRUSH, Tool.ERASER), ToolRanking.favorites(ids, 10))

        val u = ToolUsage(counts = mapOf("CROP" to 9), lastUsedMs = mapOf("CROP" to 1L))
        assertTrue(ToolRanking.mostFrequent(u, 10).isEmpty())
        assertTrue(ToolRanking.mostRecent(u, 10).isEmpty())
    }

    @Test
    fun `favourites are de-duplicated`() {
        assertEquals(listOf(Tool.BRUSH), ToolRanking.favorites(listOf("BRUSH", "BRUSH"), 10))
    }

    @Test
    fun `the codec round-trips`() {
        val u = ToolUsage.EMPTY
            .recording(Tool.BRUSH, 100L)
            .recording(Tool.BRUSH, 250L)
            .recording(Tool.LIQUIFY, 300L)
        assertEquals(u, ToolUsageCodec.decode(ToolUsageCodec.encode(u)))
    }

    @Test
    fun `the codec survives everything a corrupt preference can be`() {
        assertEquals(ToolUsage.EMPTY, ToolUsageCodec.decode(null))
        assertEquals(ToolUsage.EMPTY, ToolUsageCodec.decode(""))
        assertEquals(ToolUsage.EMPTY, ToolUsageCodec.decode("   "))
        // Junk records are skipped one by one; the good ones still arrive.
        val mixed = ToolUsageCodec.decode("BRUSH:2:50;;garbage;ERASER:notanumber:1;FILL:1:9")
        assertEquals(mapOf("BRUSH" to 2, "FILL" to 1), mixed.counts)
        assertEquals(mapOf("BRUSH" to 50L, "FILL" to 9L), mixed.lastUsedMs)
    }
}
