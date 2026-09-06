package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AzphaltPendingBatchQueueTest {
    @Test fun `bursty input drains as one lossless batch`() {
        val queue = AzphaltPendingBatchQueue<Int>()
        repeat(10_000) { queue.append(it) }
        val drained = queue.drain()
        assertEquals(10_000, drained.size)
        assertEquals(0, drained.first())
        assertEquals(9_999, drained.last())
        assertTrue(queue.isEmpty)
    }

    @Test fun `work arriving after a drain remains pending`() {
        val queue = AzphaltPendingBatchQueue<Int>()
        queue.append(listOf(1, 2, 3))
        assertEquals(listOf(1, 2, 3), queue.drain())
        queue.append(listOf(4, 5))
        assertEquals(listOf(4, 5), queue.drain())
    }
}
