@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import com.hereliesaz.graffitixr.common.DispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [PaintScans]' generation rules: a stale answer is never served, never overwrites a newer one and never lands. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class PaintScansGenerationTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private var landed = 0
    private var scans = 0
    private val scanner = PaintScans(
        scope = scope,
        dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val io = dispatcher
            override val default = dispatcher
            override val unconfined = dispatcher
        },
        isCurrent = { _, _ -> true },
        onLanded = { _, _ -> landed++ },
    ).also { it.scan = { b, check -> scans++; !ArtScan.isEmpty(b, check) } }

    private fun bitmap(color: Int): Bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun run() = dispatcher.scheduler.advanceUntilIdle()

    @Test
    fun `a landed answer is served only for the generation it was read at`() {
        val b = bitmap(Color.RED)
        scanner.warm("L", b)
        run()
        assertEquals(true, scanner.cached("L", b))
        b.eraseColor(Color.TRANSPARENT) // a new generation
        assertNull("the old generation's answer is not served", scanner.cached("L", b))
    }

    @Test
    fun `a scan whose generation has moved on lands nothing and fires nothing`() {
        val b = bitmap(Color.RED)
        scanner.warm("L", b) // queued at generation 1
        b.eraseColor(Color.TRANSPARENT) // generation 2 before it runs
        run()
        assertEquals("the stale answer is dropped", 0, landed)
        assertNull(scanner.cached("L", b))
        scanner.warm("L", b)
        run()
        assertEquals("the next warm-up scans the current generation", false, scanner.cached("L", b))
        assertEquals(1, landed)
    }

    @Test
    fun `an older scan landing after a newer answer was recorded does not overwrite it`() {
        val b = bitmap(Color.RED)
        scanner.warm("L", b) // generation 1 in flight
        b.eraseColor(Color.TRANSPARENT) // generation 2
        scanner.record("L", b, painted = false) // known synchronously: this cancels the older scan
        assertEquals(0, scanner.inFlight)
        run()
        assertEquals("the recorded answer stands", false, scanner.cached("L", b))
        assertEquals("and nothing stale was announced", 0, landed)
    }

    @Test
    fun `a cancelled scan stops at its next checkpoint instead of finishing`() {
        val big = Bitmap.createBitmap(8, 4096, Bitmap.Config.ARGB_8888)
        var checks = 0
        var cancelled = false
        try {
            ArtScan.isEmpty(big) { if (++checks == 3) throw kotlinx.coroutines.CancellationException("stop") }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertEquals(true, cancelled)
        assertEquals("stopped at the third checkpoint, not after all 256", 3, checks)
    }
}
