package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.ExtensionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The persisted half of state reporting (spec/state-reporting.md).
 *
 * The property worth pinning is why this record exists at all: the unpacked tree under `extensions/`
 * answers "is it installed", and cannot answer "was it ever" — which is exactly what a store needs to
 * offer a reinstall rather than a first purchase.
 */
class ExtensionStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = ExtensionStateStore(File(tmp.root, ExtensionStateStore.FILE_NAME))

    @Test
    fun `a recorded state round-trips through a fresh instance`() {
        store().record("com.example.grade", "1.2.0", ExtensionState.ACTIVE, atMs = 1_700_000_000_000L)

        // A new instance over the same file: this is what survives process death.
        val reopened = store().stateOf("com.example.grade")
        assertEquals("com.example.grade", reopened?.id)
        assertEquals("1.2.0", reopened?.version)
        assertEquals("active", reopened?.state)
    }

    @Test
    fun `at is an ISO-8601 UTC instant, not millis`() {
        store().record("com.example.grade", "1", ExtensionState.ACTIVE, atMs = 1_700_000_000_000L)
        // The provider column and the wire field are specified to be the same thing, so this format
        // is load-bearing rather than cosmetic.
        assertEquals("2023-11-14T22:13:20Z", store().stateOf("com.example.grade")?.at)
    }

    @Test
    fun `states are transitions — one entry per id, last write wins`() {
        val s = store()
        s.record("com.example.grade", "1.0.0", ExtensionState.DOWNLOADED, atMs = 1L)
        s.record("com.example.grade", "1.0.0", ExtensionState.ACTIVE, atMs = 2L)
        s.record("com.example.grade", "1.0.0", ExtensionState.REMOVED, atMs = 3L)

        assertEquals("history is not kept — one entry per id", 1, s.all().size)
        assertEquals("removed", s.stateOf("com.example.grade")?.state)
    }

    @Test
    fun `a reason is kept for failed and dropped for everything else`() {
        val s = store()
        s.record("com.example.a", "1", ExtensionState.FAILED, atMs = 1L, reason = "integrity-mismatch")
        assertEquals("integrity-mismatch", s.stateOf("com.example.a")?.reason)

        // A stale reason must not outlive the failure that produced it.
        s.record("com.example.a", "1", ExtensionState.ACTIVE, atMs = 2L, reason = "integrity-mismatch")
        assertNull(s.stateOf("com.example.a")?.reason)
    }

    @Test
    fun `removed is retained, so a store can offer a reinstall rather than a first purchase`() {
        val s = store()
        s.record("com.example.grade", "1.0.0", ExtensionState.REMOVED, atMs = 1L)

        val entry = s.stateOf("com.example.grade")
        assertEquals("removed", entry?.state)
        assertEquals("the last version held is reported for a removed package", "1.0.0", entry?.version)

        // Forgetting is the separate, deliberate operation — it means "never had it".
        s.forget("com.example.grade")
        assertNull(s.stateOf("com.example.grade"))
    }

    @Test
    fun `a corrupt state file reads as empty rather than throwing`() {
        File(tmp.root, ExtensionStateStore.FILE_NAME).writeText("{ this is not json")
        assertTrue(store().all().isEmpty())

        // …and recording over it recovers, rather than staying broken for the life of the install.
        store().record("com.example.a", "1", ExtensionState.ACTIVE, atMs = 1L)
        assertEquals(1, store().all().size)
    }

    @Test
    fun `nothing recorded reads as no record at all`() {
        assertTrue(store().all().isEmpty())
        assertNull(store().stateOf("com.example.never-seen"))
    }
}
