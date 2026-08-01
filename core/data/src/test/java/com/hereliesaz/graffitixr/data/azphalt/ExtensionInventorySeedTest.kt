package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.ExtensionState
import com.hereliesaz.graffitixr.common.azphalt.ExtensionStateEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The inventory a browse request carries has to describe **every** extension this host holds, not
 * only the ones installed since state reporting existed.
 *
 * This is the property the feature is for. Without it, a user upgrading with three extensions already
 * unpacked opens a store and is offered all three as new purchases — which is precisely the "a store
 * that cannot tell 'you have this' from 'you could have this'" failure the spec opens by describing,
 * and it would have persisted for exactly the users who had most to report.
 *
 * `ExtensionRepository` needs a `Context`, so the merge itself is exercised here against the same two
 * inputs it composes: what the state file recorded, and what is on disk.
 */
class ExtensionInventorySeedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * The merge `ExtensionRepository.stateInventory()` performs: recorded states win, and anything
     * installed but unrecorded is reported `active`.
     */
    private fun merge(
        recorded: List<ExtensionStateEntry>,
        installedIds: List<Pair<String, String>>,
    ): List<ExtensionStateEntry> {
        val known = recorded.map { it.id }.toHashSet()
        return recorded + installedIds
            .filterNot { it.first in known }
            .map {
                ExtensionStateEntry(
                    id = it.first,
                    version = it.second,
                    state = ExtensionState.ACTIVE.wire,
                    at = null,
                )
            }
    }

    @Test
    fun `extensions installed before state reporting existed are still reported`() {
        val store = ExtensionStateStore(File(tmp.root, ExtensionStateStore.FILE_NAME))
        // Nothing recorded: the upgrade case.
        assertTrue(store.all().isEmpty())

        val onDisk = listOf(
            "com.example.grade" to "1.0.0",
            "com.example.brush" to "2.1.0",
            "com.example.filter" to "0.3.0",
        )
        val inventory = merge(store.all(), onDisk)

        assertEquals(3, inventory.size)
        assertTrue("all three must be reported as present", inventory.all { it.state == "active" })
        assertEquals(
            setOf("com.example.grade", "com.example.brush", "com.example.filter"),
            inventory.map { it.id }.toSet(),
        )
        assertEquals("the version on disk is reported", "2.1.0", inventory.first { it.id == "com.example.brush" }.version)
    }

    @Test
    fun `a recorded state wins over what is on disk`() {
        val store = ExtensionStateStore(File(tmp.root, ExtensionStateStore.FILE_NAME))
        store.record("com.example.grade", "1.1.0", ExtensionState.ACTIVE, atMs = 1_700_000_000_000L)

        val inventory = merge(store.all(), listOf("com.example.grade" to "1.0.0"))

        assertEquals("one entry per id, never two", 1, inventory.size)
        assertEquals("the recorded version wins", "1.1.0", inventory.single().version)
        assertEquals(
            "and it keeps its timestamp, which a derived entry has no way to know",
            "2023-11-14T22:13:20Z",
            inventory.single().at,
        )
    }

    @Test
    fun `an uninstalled package stays reported as removed, not dropped`() {
        val store = ExtensionStateStore(File(tmp.root, ExtensionStateStore.FILE_NAME))
        store.record("com.example.gone", "1.0.0", ExtensionState.REMOVED, atMs = 1L)

        // Nothing on disk for it — that is what `removed` means.
        val inventory = merge(store.all(), emptyList())

        assertEquals(1, inventory.size)
        assertEquals("removed", inventory.single().state)
    }
}
