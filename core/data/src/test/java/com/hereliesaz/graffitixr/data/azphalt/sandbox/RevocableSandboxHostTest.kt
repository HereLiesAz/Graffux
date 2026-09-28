package com.hereliesaz.graffitixr.data.azphalt.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class RevocableSandboxHostTest {

    private class CountingHost : AzphaltSandboxHost {
        val calls = AtomicInteger()
        override fun requestRedraw() { calls.incrementAndGet() }
        override fun canvasWidth(): Int = 100.also { calls.incrementAndGet() }
        override fun canvasHeight(): Int = 100.also { calls.incrementAndGet() }
        override fun canvasDpi(): Int = 160.also { calls.incrementAndGet() }
        override fun paramNumber(key: String): Double? = 1.0.also { calls.incrementAndGet() }
        override fun paramBool(key: String): Boolean? = true.also { calls.incrementAndGet() }
        override fun paramString(key: String): String? = "v".also { calls.incrementAndGet() }
        override fun colorActive(): Int = 7.also { calls.incrementAndGet() }
        override fun colorSetActive(rgba: Int) { calls.incrementAndGet() }
        override fun assetRead(path: String): ByteArray? = byteArrayOf(1).also { calls.incrementAndGet() }
        override fun selectionSize(): Int = 1.also { calls.incrementAndGet() }
        override fun selectionRead(): ByteArray = byteArrayOf(1).also { calls.incrementAndGet() }
        override fun layerCount(): Int = 3.also { calls.incrementAndGet() }
    }

    @Test
    fun `revoked host denies every call without reaching the delegate`() {
        val real = CountingHost()
        val host = RevocableSandboxHost(real)
        assertEquals(3, host.layerCount())
        host.revoke()

        host.requestRedraw()
        host.colorSetActive(0xFF)
        assertEquals(0, host.canvasWidth())
        assertNull(host.paramNumber("k"))
        assertNull(host.paramBool("k"))
        assertNull(host.paramString("k"))
        assertNull(host.assetRead("a"))
        assertEquals(0, host.selectionRead().size)
        assertEquals(1, real.calls.get())
    }

    @Test
    fun `timed-out worker loses host access even if it ignores the interrupt`() {
        val real = CountingHost()
        val host = RevocableSandboxHost(real)
        val stop = CountDownLatch(1)
        try {
            runSandboxBounded(timeoutMs = 50L, onTimeout = host::revoke) {
                // A guest stuck in host code that never checks for interruption.
                while (stop.count > 0) {
                    host.colorSetActive(1)
                    try {
                        stop.await(1, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Swallowed on purpose: the worst-behaved guest.
                    }
                }
            }
            fail("expected a timeout")
        } catch (_: TimeoutException) {
            // expected
        }
        assertTrue(host.isRevoked)
        val afterTimeout = real.calls.get()
        Thread.sleep(50L)
        stop.countDown()
        assertEquals(afterTimeout, real.calls.get())
    }
}
