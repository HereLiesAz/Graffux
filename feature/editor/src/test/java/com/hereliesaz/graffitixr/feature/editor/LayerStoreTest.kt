package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Tool
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerStoreTest {

    private val store = LayerStore()

    private fun bmp(): Bitmap = mockk(relaxed = true)
    private fun stroke() = StrokeCommand(
        path = emptyList(),
        canvasSize = IntSize(1, 1),
        tool = Tool.NONE,
        brushSize = 1f,
        brushColor = 0,
        intensity = 0.5f,
    )

    @Test
    fun `base returns null for an unknown layer`() {
        assertNull(store.base("ghost"))
    }

    @Test
    fun `putBase then base round-trips the same instance`() {
        val b = bmp()
        store.putBase("a", b)
        assertSame(b, store.base("a"))
    }

    @Test
    fun `strokes returns empty for an unknown layer`() {
        assertTrue(store.strokes("ghost").isEmpty())
    }

    @Test
    fun `addStroke creates the list and appends in order`() {
        val s1 = stroke()
        val s2 = stroke()
        store.addStroke("a", s1)
        store.addStroke("a", s2)
        assertEquals(listOf(s1, s2), store.strokes("a"))
    }

    @Test
    fun `initStrokes resets the stroke list to empty`() {
        store.addStroke("a", stroke())
        store.initStrokes("a")
        assertTrue(store.strokes("a").isEmpty())
    }

    @Test
    fun `removeLastStroke returns false for an unknown layer`() {
        assertFalse(store.removeLastStroke("ghost"))
    }

    @Test
    fun `removeLastStroke drops the most recent stroke and returns true`() {
        val s1 = stroke()
        val s2 = stroke()
        store.addStroke("a", s1)
        store.addStroke("a", s2)
        assertTrue(store.removeLastStroke("a"))
        assertEquals(listOf(s1), store.strokes("a"))
    }

    @Test
    fun `removeLastStroke on an empty but present list returns true and is a no-op`() {
        store.initStrokes("a") // present, empty
        assertTrue(store.removeLastStroke("a"))
        assertTrue(store.strokes("a").isEmpty())
    }

    @Test
    fun `remove drops both base and strokes for the layer`() {
        store.putBase("a", bmp())
        store.addStroke("a", stroke())
        store.remove("a")
        assertNull(store.base("a"))
        assertTrue(store.strokes("a").isEmpty())
    }

    @Test
    fun `clear empties every layer's caches`() {
        store.putBase("a", bmp())
        store.addStroke("b", stroke())
        store.clear()
        assertNull(store.base("a"))
        assertTrue(store.strokes("b").isEmpty())
    }

    @Test
    fun `concurrent adds and iteration never throw ConcurrentModificationException`() {
        // Regression: strokes() used to return the live MutableList, so compositing on
        // Dispatchers.Default iterated it while stroke handlers appended on other threads.
        val iterations = 5_000
        val writerError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val readerError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val start = java.util.concurrent.CountDownLatch(1)

        val writer = Thread {
            try {
                start.await()
                repeat(iterations) {
                    store.addStroke("hot", stroke())
                    if (it % 100 == 0) store.removeLastStroke("hot")
                }
            } catch (t: Throwable) {
                writerError.set(t)
            }
        }
        val reader = Thread {
            try {
                start.await()
                repeat(iterations) {
                    // Full iteration of the snapshot, like DrawingEngine.composite does.
                    var count = 0
                    for (s in store.strokes("hot")) count++
                    check(count >= 0)
                }
            } catch (t: Throwable) {
                readerError.set(t)
            }
        }

        writer.start(); reader.start()
        start.countDown()
        writer.join(30_000); reader.join(30_000)

        assertNull("writer thread failed: ${writerError.get()}", writerError.get())
        assertNull("reader thread failed: ${readerError.get()}", readerError.get())
        assertTrue(store.strokes("hot").isNotEmpty())
    }

    @Test
    fun `racing first strokes on a fresh layer never lose a stroke`() {
        // Regression: getOrPut on ConcurrentHashMap is check-then-act; two racing first-strokes
        // could each install their own list, dropping one stroke.
        repeat(200) { round ->
            val layer = "fresh-$round"
            val start = java.util.concurrent.CountDownLatch(1)
            val threads = (0 until 2).map {
                Thread {
                    start.await()
                    store.addStroke(layer, stroke())
                }
            }
            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join(10_000) }
            assertEquals("round $round lost a stroke", 2, store.strokes(layer).size)
        }
    }

    // ── Stroke baking: bounding replay cost and retained memory ──────────────────────────────

    @Test
    fun `takeOldestStrokes removes and returns the oldest, keeping the newest queued`() {
        val store = LayerStore()
        val strokes = (0 until 5).map { stroke(it.toFloat()) }
        strokes.forEach { store.addStroke("a", it) }

        val taken = store.takeOldestStrokes("a", 2)

        assertEquals(listOf(strokes[0], strokes[1]), taken)
        assertEquals(listOf(strokes[2], strokes[3], strokes[4]), store.strokes("a"))
    }

    @Test
    fun `takeOldestStrokes is a no-op for a non-positive count`() {
        val store = LayerStore()
        store.addStroke("a", stroke(1f))
        assertTrue(store.takeOldestStrokes("a", 0).isEmpty())
        assertTrue(store.takeOldestStrokes("a", -3).isEmpty())
        assertEquals(1, store.strokeCount("a"))
    }

    @Test
    fun `takeOldestStrokes caps at what is there rather than overrunning`() {
        val store = LayerStore()
        store.addStroke("a", stroke(1f))
        assertEquals(1, store.takeOldestStrokes("a", 99).size)
        assertEquals(0, store.strokeCount("a"))
    }

    @Test
    fun `takeOldestStrokes on an unknown layer returns empty`() {
        assertTrue(LayerStore().takeOldestStrokes("nope", 5).isEmpty())
    }

    @Test
    fun `strokeCount reports zero for an unknown layer`() {
        assertEquals(0, LayerStore().strokeCount("nope"))
    }

    private fun stroke(x: Float) = StrokeCommand(
        path = listOf(androidx.compose.ui.geometry.Offset(x, 0f)),
        canvasSize = androidx.compose.ui.unit.IntSize(10, 10),
        tool = com.hereliesaz.graffitixr.common.model.Tool.BRUSH,
        brushSize = 1f,
        brushColor = 0,
        intensity = 0f,
    )
}
