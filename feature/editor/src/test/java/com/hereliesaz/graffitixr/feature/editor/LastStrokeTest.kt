package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.EditorUiState
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastStrokeTest {
    private val state = EditorUiState()

    private fun chain(vararg cmds: StrokeCommand): LastStroke? {
        var last: LastStroke? = null
        cmds.forEach { last = LastStroke.of("L", it, state, last) }
        return last
    }

    private fun length(c: LastStroke?): Int = if (c == null) 0 else 1 + length(c.prior)

    @Test
    fun `the chain never grows past its depth`() {
        val cmds = Array(20) { mockk<StrokeCommand>() }
        assertEquals(LastStroke.CHAIN_DEPTH, length(chain(*cmds)))
    }

    @Test
    fun `undoing a whole chunk retires every stroke in it, by identity, and only those`() {
        val a = mockk<StrokeCommand>()
        val b = mockk<StrokeCommand>()
        val c = mockk<StrokeCommand>()
        val d = mockk<StrokeCommand>()
        val rest = chain(a, b, c, d)!!.without(listOf(c, d))!!
        assertEquals(b, rest.command)
        assertEquals(a, rest.prior!!.command)
        assertNull(rest.prior!!.prior)
        assertNull(chain(a)!!.without(listOf(a)))
    }
}
