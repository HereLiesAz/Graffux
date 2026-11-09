package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.ExtensionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    /**
     * A write that cannot land must say so. It used to be wrapped in a bare `runCatching {}` with no
     * failure branch, so an install reported success while nothing was persisted and the store went
     * on showing *Get* with nothing anywhere to explain why.
     */
    @Test
    fun `a write that cannot land reports failure instead of pretending`() {
        // A path whose parent is a *file*, so no directory can be created under it.
        val blocker = File(tmp.root, "blocked").apply { writeText("not a directory") }
        val store = ExtensionStateStore(File(blocker, ExtensionStateStore.FILE_NAME))

        assertFalse(
            "record() must report that the write did not land",
            store.record("com.example.a", "1", ExtensionState.ACTIVE, atMs = 1L),
        )
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a successful write reports success`() {
        assertTrue(store().record("com.example.a", "1", ExtensionState.ACTIVE, atMs = 1L))
    }

    /**
     * Two instances over one file are the real deployment — the repository builds one and the
     * ContentProvider another — so they must not each hold their own lock.
     */
    @Test
    fun `two instances over one file share a lock`() {
        val path = File(tmp.root, ExtensionStateStore.FILE_NAME)
        val a = ExtensionStateStore(path)
        val b = ExtensionStateStore(path)
        // Same monitor, reached reflectively: the property is structural, not observable by racing.
        val lockOf = ExtensionStateStore::class.java.getDeclaredField("lock")
            .apply { isAccessible = true }
        assertSame("both instances must serialise on the same monitor", lockOf.get(a), lockOf.get(b))
    }

    @Test
    fun `nothing recorded reads as no record at all`() {
        assertTrue(store().all().isEmpty())
        assertNull(store().stateOf("com.example.never-seen"))
    }

    private fun scrubbed(raw: String): String {
        val s = store()
        s.record("com.example.a", "1", ExtensionState.FAILED, atMs = 1L, reason = raw)
        return s.stateOf("com.example.a")?.reason.orEmpty()
    }

    @Test
    fun `paths, file names and addresses are replaced, token by token, and the rest is kept`() {
        val expected = mapOf(
            "azphalt-state.json: Permission denied" to "[redacted] Permission denied",
            "open failed: extensions/com.example.a/manifest.json" to "open failed: [redacted]",
            "cannot read C:\\Users\\me\\wall.png" to "cannot read [redacted]",
            "no such file: layers/a.png" to "no such file: [redacted]",
            "unlink failed for model_x.obj" to "unlink failed for [redacted]",
            "content://media/external/images/1 revoked" to "[redacted] revoked",
            "(content://x/y) gone" to "[redacted] gone",
            "see ../x/y.obj now" to "see [redacted] now",
            "/data/user/0/app/files/x failed" to "[redacted] failed",
            "bad ~/notes/a.png here" to "bad [redacted] here",
            "a/b/c is not here" to "[redacted] is not here",
        )
        for ((raw, want) in expected) assertEquals(raw, want, scrubbed(raw))
    }

    @Test
    fun `ordinary diagnostics are not mistaken for paths`() {
        for (raw in listOf(
            "and/or", "retry 3/5", "1/2 done", "HTTP 404 / not found", "version 1.2.3", "2026-11-09",
            "network/timeout", "io.error.log", "a.so", "http/2.0", "integrity-mismatch (digest)", "Mural: v2",
        )) {
            assertEquals(raw, raw, scrubbed(raw))
        }
    }

    @Test(timeout = 5_000)
    fun `a very long unspaced reason is cut before it is scanned, and stays quick`() {
        // A backtracking pattern over a single long dotted or slashed token took seconds to minutes here.
        val long = listOf(
            "a.".repeat(50_000), "a.b.".repeat(25_000), "x".repeat(100_000), "/".repeat(100_000), "a/".repeat(50_000),
        )
        for (raw in long) assertTrue(scrubbed(raw).length <= 200)
    }
}
