package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire half of azphalt state reporting (spec/state-reporting.md § 1, § 3.1) — the entry shape a
 * store consumes and the size rules the Binder transaction imposes on it.
 */
class ExtensionStateTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun entry(id: String, state: ExtensionState = ExtensionState.ACTIVE) =
        ExtensionStateEntry(id = id, version = "1.0.0", state = state.wire, at = "2026-07-30T02:14:00Z")

    @Test
    fun `the five states use their normative wire names`() {
        assertEquals(
            listOf("downloaded", "installed", "active", "failed", "removed"),
            ExtensionState.entries.map { it.wire },
        )
    }

    @Test
    fun `an entry serializes to the shape the spec documents`() {
        val doc = ExtensionInventory.document(
            listOf(
                ExtensionStateEntry(
                    id = "com.hereliesaz.azphalt.halftone",
                    version = "1.2.0",
                    state = ExtensionState.ACTIVE.wire,
                    at = "2026-07-30T02:14:00Z",
                )
            )
        )
        val obj = json.parseToJsonElement(doc).jsonObject
        val first = obj["entries"]!!.jsonArray.single().jsonObject
        assertEquals("com.hereliesaz.azphalt.halftone", first["id"]!!.toString().trim('"'))
        assertEquals("1.2.0", first["version"]!!.toString().trim('"'))
        assertEquals("active", first["state"]!!.toString().trim('"'))
        assertEquals("2026-07-30T02:14:00Z", first["at"]!!.toString().trim('"'))
        // `reason` is optional and absent here — it is only meaningful for `failed`.
        assertTrue("an absent reason must not be emitted as null", !doc.contains("reason"))
    }

    /**
     * "A consumer MUST ignore an unrecognised state rather than reject the whole report" — so the
     * vocabulary can grow without every host needing a rebuild. The unknown entry still parses and
     * keeps its raw string; only its interpretation is absent.
     */
    @Test
    fun `an unknown state does not sink the document`() {
        val entries = ExtensionInventory.parse(
            """{"entries":[
                 {"id":"a","version":"1","state":"active"},
                 {"id":"b","version":"1","state":"quarantined","futureField":42},
                 {"id":"c","version":"1","state":"removed"}
               ]}"""
        )
        assertEquals(3, entries.size)
        assertEquals(ExtensionState.ACTIVE, entries[0].parsedState)
        assertNull("an unknown state has no interpretation", entries[1].parsedState)
        assertEquals("quarantined", entries[1].state)
        assertEquals(ExtensionState.REMOVED, entries[2].parsedState)
    }

    @Test
    fun `malformed input yields no entries rather than throwing`() {
        assertEquals(emptyList<ExtensionStateEntry>(), ExtensionInventory.parse("not json"))
        assertEquals(emptyList<ExtensionStateEntry>(), ExtensionInventory.parse(""))
        assertEquals(emptyList<ExtensionStateEntry>(), ExtensionInventory.parse(null))
    }

    /**
     * The entry cap. An Intent extra crosses Binder, whose transaction buffer is a small fixed budget
     * — an oversized inventory throws `TransactionTooLargeException` in the *caller*, so a host that
     * ignores the cap crashes itself trying to open a store.
     */
    @Test
    fun `an oversized inventory is trimmed to the entry cap`() {
        val many = (1..600).map { entry("com.example.pkg$it") }
        val parsed = ExtensionInventory.parse(ExtensionInventory.document(many))
        assertEquals(ExtensionInventory.MAX_ENTRIES, parsed.size)
    }

    @Test
    fun `the document stays under the byte ceiling even with long ids`() {
        // 500 entries is within the count cap but each id is ~1 KiB, so only the byte rule can save it.
        val fat = (1..500).map { entry("com.example." + "x".repeat(1000) + it) }
        val doc = ExtensionInventory.document(fat)
        assertTrue(
            "document is ${doc.toByteArray().size} bytes, over the ${ExtensionInventory.MAX_BYTES} cap",
            doc.toByteArray(Charsets.UTF_8).size <= ExtensionInventory.MAX_BYTES,
        )
        // Trimmed, not emptied — a partial inventory makes some cards correct; none makes all wrong.
        assertTrue("trimming must keep what fits", ExtensionInventory.parse(doc).isNotEmpty())
    }

    /**
     * The failure this guards against is worse than sending too much: the trim used to drop from the
     * end unconditionally, so one entry too big to fit ate every good entry before finally being
     * dropped itself, leaving `{"entries":[]}`. A store reads that as "this host has nothing" — a
     * confident wrong answer where sending nothing would merely have been no answer.
     */
    @Test
    fun `one oversized entry does not empty the whole inventory`() {
        val huge = ExtensionStateEntry(
            id = "com.example.exploded",
            version = "1.0.0",
            state = ExtensionState.FAILED.wire,
            at = "2026-07-30T02:14:00Z",
            reason = "x".repeat(300_000),
        )
        val good = (1..5).map { entry("com.example.fine$it") }

        val parsed = ExtensionInventory.parse(ExtensionInventory.document(listOf(huge) + good))
        assertTrue("the good entries must survive an oversized neighbour", parsed.size >= good.size)
        assertTrue(
            "the oversized entry is bounded, not dropped",
            parsed.any { it.id == "com.example.exploded" },
        )
        assertTrue(
            "its reason must be clipped",
            (parsed.first { it.id == "com.example.exploded" }.reason?.length ?: 0) < 1_000,
        )
    }

    @Test
    fun `a multi-byte id is measured in bytes, not characters`() {
        // Each id is 400 chars but 1200 bytes in UTF-8; counting characters would pass the cap while
        // the Binder transaction sees three times as much.
        val wide = (1..400).map { entry("com.example." + "字".repeat(400) + it) }
        val doc = ExtensionInventory.document(wide)
        assertTrue(
            "document is ${doc.toByteArray(Charsets.UTF_8).size} bytes, over the cap",
            doc.toByteArray(Charsets.UTF_8).size <= ExtensionInventory.MAX_BYTES,
        )
    }
}
