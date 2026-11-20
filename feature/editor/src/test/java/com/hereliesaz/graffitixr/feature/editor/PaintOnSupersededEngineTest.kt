package com.hereliesaz.graffitixr.feature.editor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The commit's use of a parked live engine, with fakes (the engine is a token, the events a list):
 * adoption, the back-to-back (rebased) case, and that the engine is invalidated and released on
 * every exit of the paint, in that order.
 */
class PaintOnSupersededEngineTest {
    private val events = ArrayList<String>()
    private val engines = SupersededEngines<String> { events += "release-orphan:$it" }
    private val done = CompletableDeferred<Unit>().also { it.complete(Unit) }

    private fun parked(seed: Long = 1L, engine: String = "A") {
        engines.expect(seed)
        assertTrue(engines.offer(seed, engine))
    }

    private suspend fun <T> commit(
        rebased: Boolean = false,
        sizeChanged: Boolean = false,
        refusal: String? = null,
        reseedOk: Boolean = true,
        paint: suspend (String?, () -> Unit) -> T,
    ) = paintOnSupersededEngine(
        engines, done, 1L, rebased, sizeChanged, refusal,
        reseed = { events += "reseed:$it"; reseedOk },
        invalidate = { events += "invalidate" },
        release = { events += "release:$it" },
        log = { events += "log:$it" },
        paint = paint,
    )

    @Test
    fun `the stroke paints on the parked engine, invalidated then released, nothing else built`() = runTest {
        parked()
        val out = commit { engine, _ -> events += "paint:$engine"; "painted" }
        assertEquals("painted", out)
        assertEquals(
            listOf("paint:A", "invalidate", "release:A"),
            events.filter { !it.startsWith("log:") },
        )
        assertTrue(events.any { it == "log:commit: adopted engine used" })
    }

    @Test
    fun `a back-to-back rebased stroke re-seeds the same engine instead of building another`() = runTest {
        parked()
        var seen: String? = null
        commit(rebased = true) { engine, _ -> seen = engine }
        assertSame("A", seen)
        assertEquals(1, events.count { it == "reseed:A" })
        assertTrue("re-seeded before released", events.indexOf("reseed:A") < events.indexOf("release:A"))
        assertTrue(events.contains("invalidate"))
        assertTrue(events.any { it.contains("adopted engine used (rebased") })
    }

    @Test
    fun `a stroke that is not rebased is not re-seeded`() = runTest {
        parked()
        commit { _, _ -> }
        assertTrue(events.none { it.startsWith("reseed") })
    }

    @Test
    fun `a failed re-seed, a selection or a resize discard the engine with the reason logged`() = runTest {
        for ((reason, args) in listOf(
            "reseed failed" to Triple(true, false, null as String?),
            "selection" to Triple(false, false, "selection"),
            "resized" to Triple(false, true, null),
        )) {
            events.clear()
            parked()
            var seen: String? = "unset"
            commit(
                rebased = args.first,
                sizeChanged = args.second,
                refusal = args.third,
                reseedOk = reason != "reseed failed",
            ) { engine, _ -> seen = engine }
            assertNull(reason, seen)
            assertTrue(reason, events.contains("log:commit: adopted engine discarded (reason: $reason)"))
            assertEquals(reason, 1, events.count { it == "release:A" })
            assertTrue(reason, events.contains("invalidate"))
        }
    }

    @Test
    fun `a failing paint still invalidates then releases`() = runTest {
        parked()
        try {
            commit<Unit> { _, _ -> throw StrokeRenderException("boom") }
            fail("expected a throw")
        } catch (_: StrokeRenderException) {
            val tail = events.filter { it == "invalidate" || it.startsWith("release:") }
            assertEquals(listOf("invalidate", "release:A"), tail)
        }
    }

    @Test
    fun `a cancelled commit still invalidates then releases`() = runTest {
        parked()
        try {
            commit<Unit> { _, _ -> throw CancellationException("undo") }
            fail("expected a throw")
        } catch (_: CancellationException) {
            val tail = events.filter { it == "invalidate" || it.startsWith("release:") }
            assertEquals(listOf("invalidate", "release:A"), tail)
        }
    }

    @Test
    fun `with nothing parked the paint gets null and nothing is invalidated or released`() = runTest {
        engines.expect(1L)
        var seen: String? = "unset"
        commit { engine, _ -> seen = engine }
        assertNull(seen)
        assertTrue(events.none { it == "invalidate" || it.startsWith("release") })
        assertTrue(events.any { it.contains("no engine parked") })
    }

    @Test
    fun `a setup that finishes after the commit's wait is refused and destroys its own engine`() = runTest {
        engines.expect(1L)
        val never = CompletableDeferred<Unit>()
        var seen: String? = "unset"
        paintOnSupersededEngine(
            engines, never, 1L, false, false, null, { true }, {}, {}, { events += it },
        ) { engine, _ -> seen = engine }
        assertNull(seen)
        assertEquals(false, engines.offer(1L, "late"))
    }

    @Test
    fun `a reseed that throws is a failed reseed, so it is invalidated and released and no lease leaks`() = runTest {
        parked()
        var seen: String? = "unset"
        paintOnSupersededEngine(
            engines, done, 1L, true, false, null,
            reseed = { throw IllegalArgumentException("bitmap is not ARGB_8888") },
            invalidate = { events += "invalidate" },
            release = { events += "release:$it" },
            log = { events += "log:$it" },
        ) { engine, _ -> seen = engine }
        assertNull(seen)
        assertEquals(
            listOf("invalidate", "release:A"),
            events.filter { it == "invalidate" || it.startsWith("release:") },
        )
        assertTrue(events.contains("log:commit: adopted engine discarded (reason: reseed failed)"))
        assertTrue(
            "the swallowed throw is logged with its cause",
            events.any { it.startsWith("log:commit: re-seeding the adopted engine threw IllegalArgumentException") },
        )
    }

    @Test
    fun `the adopted engine is released before a retry builds a fresh one, and only once`() = runTest {
        parked()
        commit { engine, done ->
            events += "attempt0:$engine"
            // What the renderer does when the adopted attempt fails, before its retry:
            done()
            events += "fresh engine built"
            "painted"
        }
        val order = events.filter { it.startsWith("release:") || it == "fresh engine built" }
        assertEquals(listOf("release:A", "fresh engine built"), order)
        assertEquals("released exactly once despite the exit path", 1, events.count { it.startsWith("release:") })
        assertEquals(1, events.count { it == "invalidate" })
    }

    @Test
    fun `one engine has one owner, the live finish or the parked adoption, never both`() {
        assertEquals(false, expectsSetupEngine(hasLiveEngine = true, hasWetEngine = false, hasSetup = true))
        assertEquals(false, expectsSetupEngine(hasLiveEngine = false, hasWetEngine = true, hasSetup = true))
        assertEquals(false, expectsSetupEngine(hasLiveEngine = false, hasWetEngine = false, hasSetup = false))
        assertEquals(true, expectsSetupEngine(hasLiveEngine = false, hasWetEngine = false, hasSetup = true))
    }

    @Test
    fun `any render failure of the first attempt releases the adopted engine before the retry`() {
        val released = ArrayList<String>()
        val adopted = AdoptedEngine(io.mockk.mockk(relaxed = true)) { released += "released" }
        try {
            releasingAdoptedOnFailure(true, adopted) { throw StrokeRenderException("keepInside: no memory") }
            fail("expected a throw")
        } catch (_: StrokeRenderException) {
            assertEquals(listOf("released"), released)
        }
        try { // the retry's own failure must not release (it holds no adopted engine)
            releasingAdoptedOnFailure(false, adopted) { throw StrokeRenderException("again") }
        } catch (_: StrokeRenderException) {
            assertEquals(listOf("released"), released)
        }
        assertEquals("ok", releasingAdoptedOnFailure(true, adopted) { "ok" })
        assertEquals(listOf("released"), released)
    }
}
