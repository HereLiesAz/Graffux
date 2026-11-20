package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstStrokeTraceTest {
    private var now = 0L
    private val lines = mutableListOf<String>()
    private val trace = FirstStrokeTrace(clockNs = { now }, log = { lines += it })

    private fun ms(n: Long) {
        now += n * 1_000_000L
    }

    @Test
    fun `counts the touches swallowed before the layer landed and times the milestones`() {
        trace.toolPicked("BRUSH")
        ms(300)
        trace.touchSwallowed()
        ms(200)
        trace.touchSwallowed()
        ms(1500)
        trace.layerLanded()
        ms(40)
        trace.touchSwallowed() // after the layer landed: an ordinary gesture, not counted
        trace.firstDabPresented()
        trace.firstDabPresented() // once only

        assertTrue(lines.toString(), lines.any { "layer landed 2000 ms" in it && "2 touches swallowed" in it })
        assertTrue(lines.toString(), lines.any { "first dab presented 40 ms after the layer landed" in it })
        assertEquals(1, lines.count { "first dab" in it })
        assertEquals("one line per milestone, none per touch", 3, lines.size)
    }

    @Test
    fun `is silent when no tool pick is awaiting a layer`() {
        trace.touchSwallowed()
        trace.layerLanded()
        trace.firstDabPresented()
        assertTrue(lines.isEmpty())
    }
}
