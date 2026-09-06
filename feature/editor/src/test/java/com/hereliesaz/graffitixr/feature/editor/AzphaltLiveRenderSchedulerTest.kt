package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class AzphaltLiveRenderSchedulerTest {
    @Test fun `bursty input coalesces to latest frame`() {
        val workers = ArrayDeque<() -> Unit>()
        val rendered = mutableListOf<Int>()
        val scheduler = AzphaltLiveRenderScheduler<Int>({ workers.addLast(it) }) { rendered += it }
        repeat(1_000) { scheduler.submit(it) }
        assertEquals(1, workers.size)
        workers.removeFirst().invoke()
        assertEquals(listOf(999), rendered)
    }
    @Test fun `input during render replaces pending frame`() {
        val workers = ArrayDeque<() -> Unit>()
        val rendered = mutableListOf<Int>()
        lateinit var scheduler: AzphaltLiveRenderScheduler<Int>
        scheduler = AzphaltLiveRenderScheduler({ workers.addLast(it) }) {
            rendered += it
            if (it == 1) repeat(100) { n -> scheduler.submit(n + 2) }
        }
        scheduler.submit(1)
        workers.removeFirst().invoke()
        assertEquals(listOf(1, 101), rendered)
        assertTrue(workers.isEmpty())
    }
}
