package com.hereliesaz.graffitixr.data.azphalt.purchase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EntitlementStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(id: String) = StoredEntitlement(id, "tok-$id", EntitlementSource.STRIPE)

    @Test fun `a corrupt file is never overwritten when there is no good copy`() {
        val file = File(tmp.root, EntitlementStore.FILE_NAME)
        file.writeText("""{"entitlements":[{"packageId":"a","tok""") // truncated
        val store = EntitlementStore(file)
        try {
            store.put(entry("b"))
            fail("wrote over an unreadable store")
        } catch (_: EntitlementStoreUnreadable) {
        }
        assertTrue(file.readText().startsWith("""{"entitlements":[{"packageId":"a""""))
        assertTrue(File(tmp.root, file.name + ".corrupt").exists())
    }

    @Test fun `a corrupt file falls back to the last good backup instead of reading as empty`() {
        val file = File(tmp.root, EntitlementStore.FILE_NAME)
        val store = EntitlementStore(file)
        store.put(entry("a"))
        store.buyerCookie = "azphalt_buyer_session=abc" // the second write leaves a .bak with "a"
        file.writeText("garbage")
        assertEquals(listOf("a"), store.all().map { it.packageId })
        store.put(entry("b"))
        assertEquals(listOf("a", "b"), store.all().map { it.packageId })
        assertTrue(File(tmp.root, file.name + ".corrupt").readText() == "garbage")
    }

    @Test fun `a missing file is an empty store`() {
        val store = EntitlementStore(File(tmp.root, "none.json"))
        assertTrue(store.all().isEmpty())
        store.put(entry("a"))
        assertEquals(1, store.all().size)
    }
}
