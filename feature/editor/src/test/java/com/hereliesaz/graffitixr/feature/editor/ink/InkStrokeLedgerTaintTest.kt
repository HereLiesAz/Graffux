package com.hereliesaz.graffitixr.feature.editor.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkStrokeLedgerTaintTest {
    @Test
    fun `an erase after ink makes the layer export no vector ink`() {
        val ledger = InkStrokeLedger<String>()
        ledger.add("L", Any(), "stroke")
        ledger.taint("L")
        assertTrue(ledger.strokes("L").isEmpty())
    }

    @Test
    fun `tainting a layer with no ink has no effect on later ink`() {
        val ledger = InkStrokeLedger<String>()
        ledger.taint("L")
        ledger.add("L", Any(), "stroke")
        assertEquals(listOf("stroke"), ledger.strokes("L"))
    }
}
