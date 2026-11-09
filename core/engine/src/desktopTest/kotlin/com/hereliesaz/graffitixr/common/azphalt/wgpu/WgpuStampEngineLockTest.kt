package com.hereliesaz.graffitixr.common.azphalt.wgpu

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every [WgpuStampEngine] call holds the instance lock while the GPU works (a readback waits for it), so a label read
 * for the UI must not take that lock. No native library is needed: the engine is built around a fake handle that no
 * call here passes to native code.
 */
class WgpuStampEngineLockTest {
    @Test
    fun `the adapter description is readable while another call holds the engine's lock`() {
        val engine = WgpuStampEngine(handle = 1L, width = 4, height = 4, adapter = "Vulkan: test adapter (driver 1)")
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(name = "long-readback") {
            synchronized(engine) { // what a readback that is waiting on the GPU looks like to every other caller
                held.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            var read: String? = null
            val reader = thread(name = "ui") { read = engine.adapterDescription }
            reader.join(2_000)
            val finished = !reader.isAlive
            assertTrue(finished, "adapterDescription queued behind a call that holds the engine's lock")
            assertEquals("Vulkan: test adapter (driver 1)", read)
        } finally {
            release.countDown()
            holder.join()
        }
    }
}
