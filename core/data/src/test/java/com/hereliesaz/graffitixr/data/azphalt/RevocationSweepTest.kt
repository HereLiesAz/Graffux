package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.RevocationEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class RevocationSweepTest {
    private fun rev(id: String, v: String) = RevocationEntry(id = id, version = v, revokedAt = "2026-10-01T00:00:00Z")

    @Test
    fun `only the exact installed version is removed`() {
        val installed = listOf("a" to "1.0.0", "b" to "2.0.0")
        val feed = listOf(rev("a", "1.0.0"), rev("b", "1.9.0"), rev("c", "1.0.0"))
        assertEquals(listOf("a"), RevocationSweep.matching(installed, feed).map { it.id })
    }

    @Test
    fun `duplicate feed entries remove once`() {
        val feed = listOf(rev("a", "1.0.0"), rev("a", "1.0.0"))
        assertEquals(1, RevocationSweep.matching(listOf("a" to "1.0.0"), feed).size)
    }
}
