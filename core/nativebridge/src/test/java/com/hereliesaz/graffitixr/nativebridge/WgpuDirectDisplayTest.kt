package com.hereliesaz.graffitixr.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WgpuDirectDisplayTest {
    private val log = ArrayList<String>()
    private var attachOk = true
    private var beginOk = true
    private var presentOk = true

    private val display = WgpuDirectDisplay(object : WgpuDirectDisplay.Natives<String> {
        override fun attach(handle: Long, surface: String, width: Int, height: Int): Boolean {
            log += "attach $handle $surface ${width}x$height"
            return attachOk
        }

        override fun detach(handle: Long) {
            log += "detach $handle"
        }

        override fun begin(handle: Long): Boolean {
            log += "begin $handle"
            return beginOk
        }

        override fun present(handle: Long, matrix: FloatArray?, newBatch: Boolean): Boolean {
            log += "present $handle ${if (matrix == null) "last" else "m"} $newBatch"
            return presentOk
        }

        override fun end(handle: Long): Boolean {
            log += "end $handle"
            return true
        }
    })

    private val m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)

    @Test
    fun noSurfaceMeansNoDirectDisplay() {
        assertFalse(display.begin(1L))
        assertFalse(display.present(1L, m))
        assertTrue(log.isEmpty())
    }

    @Test
    fun strokeLifecycleOnOneHandle() {
        display.setSurface("s", 100, 50)
        assertTrue(display.begin(1L))
        assertTrue(display.present(1L, m))
        assertTrue(display.represent(1L))
        display.end()
        assertFalse(display.showing)
        assertEquals(1L, display.owner)
        assertEquals(
            listOf("attach 1 s 100x50", "begin 1", "present 1 m true", "present 1 last false", "end 1"),
            log,
        )
        // After the stroke, re-presents (a late refinement tick) do nothing.
        assertFalse(display.represent(1L))
    }

    @Test
    fun anotherHandleTakesTheWindowOnlyAfterTheOwnerDetaches() {
        display.setSurface("s", 10, 10)
        assertTrue(display.begin(1L))
        display.end()
        log.clear()
        assertTrue(display.begin(2L))
        assertEquals(listOf("detach 1", "attach 2 s 10x10", "begin 2"), log)
        // The old handle can no longer draw.
        assertFalse(display.present(1L, m))
        assertEquals(2L, display.owner)
    }

    @Test
    fun failuresFallBackForTheRestOfTheStroke() {
        display.setSurface("s", 10, 10)
        attachOk = false
        assertFalse(display.begin(1L))
        assertEquals(0L, display.owner)
        attachOk = true
        beginOk = false
        assertFalse(display.begin(1L))
        assertFalse(display.present(1L, m))
        beginOk = true
        assertTrue(display.begin(1L))
        presentOk = false
        assertFalse(display.present(1L, m))
        assertFalse(display.showing)
        assertTrue(log.last() == "end 1")
        presentOk = true
        assertFalse("off for the rest of the stroke", display.present(1L, m))
    }

    @Test
    fun surfaceLossDetachesSynchronouslyAndDestroyForgetsTheOwner() {
        display.setSurface("s", 10, 10)
        assertTrue(display.begin(1L))
        display.setSurface(null, 0, 0)
        assertEquals("detach 1", log.last())
        assertEquals(0L, display.owner)
        assertFalse(display.hasSurface)
        assertFalse(display.begin(1L))

        display.setSurface("t", 20, 20)
        assertTrue(display.begin(3L))
        display.handleDestroyed(3L)
        assertEquals(0L, display.owner)
        assertFalse(display.present(3L, m))
        // Destroying some other handle leaves the owner alone.
        assertTrue(display.begin(4L))
        display.handleDestroyed(5L)
        assertEquals(4L, display.owner)
    }

    @Test
    fun resizeKeepsTheOwnerAndReattachesAtTheNextStroke() {
        display.setSurface("s", 10, 10)
        assertTrue(display.begin(1L))
        display.end()
        display.setSurface("s", 30, 40)
        assertEquals(1L, display.owner)
        assertTrue(display.begin(1L))
        assertEquals("attach 1 s 30x40", log[log.size - 2])
    }
}
