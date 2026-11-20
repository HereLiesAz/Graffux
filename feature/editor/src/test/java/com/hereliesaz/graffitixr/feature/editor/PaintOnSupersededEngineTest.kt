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
        paint: suspend (String?) -> T,
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
        val out = commit { engine -> events += "paint:$engine"; "painted" }
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
        commit(rebased = true) { seen = it }
        assertSame("A", seen)
        assertEquals(1, events.count { it == "reseed:A" })
        assertTrue("re-seeded before released", events.indexOf("reseed:A") < events.indexOf("release:A"))
        assertTrue(events.contains("invalidate"))
        assertTrue(events.any { it.contains("adopted engine used (rebased") })
    }

    @Test
    fun `a stroke that is not rebased is not re-seeded`() = runTest {
        parked()
        commit { }
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
            ) { seen = it }
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
            commit<Unit> { throw StrokeRenderException("boom") }
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
            commit<Unit> { throw CancellationException("undo") }
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
        commit { seen = it }
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
        ) { seen = it }
        assertNull(seen)
        assertEquals(false, engines.offer(1L, "late"))
    }
}
