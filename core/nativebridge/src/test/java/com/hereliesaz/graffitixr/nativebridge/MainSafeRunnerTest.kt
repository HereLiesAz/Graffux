package com.hereliesaz.graffitixr.nativebridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy behind [GpuStampEngine.destroy]: a "main thread" caller must return promptly while a stroke worker holds
 * the engine's monitor in a (blocked) readback, and the teardown must still run once the worker lets go. The real
 * engine needs the native library, so this exercises the runner with a monitor held exactly as `readback` holds it.
 */
class MainSafeRunnerTest {
    private val monitor = Any()

    private fun readbackInFlight(release: CountDownLatch, entered: CountDownLatch) = Thread {
        synchronized(monitor) {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
    }.apply { start() }

    @Test
    fun `a main-thread teardown returns promptly while a readback holds the monitor, then still runs`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val worker = readbackInFlight(release, entered)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val ran = CountDownLatch(1)
        val exec = Executors.newSingleThreadExecutor()
        val runner = MainSafeRunner(isMain = { true }, executor = exec)

        val t0 = System.nanoTime()
        val handedOff = runner.run { synchronized(monitor) { ran.countDown() } }
        val ms = (System.nanoTime() - t0) / 1_000_000

        assertTrue("handed off the main thread", handedOff)
        assertTrue("returned in $ms ms while the readback was still in flight", ms < 200)
        assertEquals("not run yet: the readback still holds the monitor", 1L, ran.count)
        release.countDown()
        assertTrue("runs once the readback is done", ran.await(5, TimeUnit.SECONDS))
        worker.join()
        exec.shutdown()
    }

    @Test
    fun `off the main thread the teardown runs inline`() {
        var ran = false
        val handedOff = MainSafeRunner(isMain = { false }).run { ran = true }
        assertFalse(handedOff)
        assertTrue(ran)
    }
}
