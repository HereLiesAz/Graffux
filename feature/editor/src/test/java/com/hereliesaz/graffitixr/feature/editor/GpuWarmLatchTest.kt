package com.hereliesaz.graffitixr.feature.editor

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GpuWarmLatchTest {
    @Test
    fun only_the_first_claim_per_key_wins() {
        val latch = GpuWarmLatch()
        val key = GpuWarmLatch.Key(1080, 1920, 0)
        assertTrue(latch.claim(key))
        assertFalse(latch.claim(key))
        assertTrue(latch.claim(GpuWarmLatch.Key(1080, 1920, 1)))
        assertTrue(latch.claim(GpuWarmLatch.Key(720, 1280, 0)))
    }

    @Test
    fun release_allows_a_retry_and_reset_clears_all() {
        val latch = GpuWarmLatch()
        val key = GpuWarmLatch.Key(10, 10, 0)
        assertTrue(latch.claim(key))
        latch.release(key)
        assertTrue(latch.claim(key))
        latch.reset()
        assertTrue(latch.claim(key))
    }

    @Test
    fun a_failed_render_is_retried_once_on_a_fresh_attempt() = runTest {
        val attempts = ArrayList<Int>()
        var retried = 0
        val out = retryRenderOnce(onRetry = { retried++ }) { attempt ->
            attempts += attempt
            if (attempt == 0) throw StrokeRenderException("cold first engine")
            "ok"
        }
        assertEquals("ok", out)
        assertEquals(listOf(0, 1), attempts)
        assertEquals(1, retried)
    }

    @Test
    fun a_second_failure_propagates_and_non_retriable_work_is_not_retried() = runTest {
        var calls = 0
        try {
            retryRenderOnce<Unit> { calls++; throw StrokeRenderException("again") }
            fail("expected a throw")
        } catch (_: StrokeRenderException) {
            assertEquals(2, calls)
        }
        calls = 0
        try {
            retryRenderOnce<Unit>(retriable = false) { calls++; throw StrokeRenderException("no") }
            fail("expected a throw")
        } catch (_: StrokeRenderException) {
            assertEquals(1, calls)
        }
    }
}
