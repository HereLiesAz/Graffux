package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The store alone: ordering and load-first, on a dispatcher the test steps by hand. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentColorStoreTest {
    private val a = Color(0xFFAA0000)
    private val b = Color(0xFF00BB00)
    private val c = Color(0xFF0000CC)
    private val d = Color(0xFFDDDD00)

    private val writes = ArrayList<String>()
    private val store = RecentColorStore { writes += it }
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun startWriter() {
        scope.launch(dispatcher) { store.runWriter() }
        scope.runCurrent()
    }

    @Test
    fun `nothing is written before the stored colours are in`() {
        startWriter()
        store.commit(d)
        store.delete(a)
        scope.advanceUntilIdle()
        assertTrue("a write before the load would replace the stored colours", writes.isEmpty())
        store.onLoaded(encodeRecentColors(listOf(a, b)))
        scope.advanceUntilIdle()
        assertEquals(listOf(encodeRecentColors(listOf(d, b))), writes)
    }

    @Test
    fun `a delete before the load is not undone by it, a commit before it sits in front`() {
        store.delete(a)
        store.commit(d)
        // The screen already shows what was done, ahead of the load.
        assertEquals(listOf(d), store.colors.value)
        store.onLoaded(encodeRecentColors(listOf(a, b, c)))
        assertEquals(listOf(d, b, c), store.colors.value)
    }

    @Test
    fun `a second load is ignored`() {
        store.onLoaded(encodeRecentColors(listOf(a)))
        store.commit(b)
        store.onLoaded(encodeRecentColors(listOf(c)))
        assertEquals(listOf(b, a), store.colors.value)
    }

    @Test
    fun `committing what is already first changes nothing and asks for no save`() {
        store.onLoaded(encodeRecentColors(listOf(a, b)))
        startWriter()
        scope.advanceUntilIdle()
        val before = writes.size
        assertFalse(store.commit(a))
        scope.advanceUntilIdle()
        assertEquals(before, writes.size)
    }

    @Test
    fun `a burst of changes while a write is in flight ends with the latest list written last`() {
        val gate = CompletableDeferred<Unit>()
        val slow = ArrayList<String>()
        val gated = RecentColorStore { encoded ->
            if (slow.isEmpty()) gate.await()
            slow += encoded
        }
        gated.onLoaded(encodeRecentColors(listOf(a, b, c)))
        scope.launch(dispatcher) { gated.runWriter() }
        scope.runCurrent()
        gated.delete(a)
        scope.runCurrent() // the first write starts and waits on the gate
        gated.delete(b)
        gated.commit(d)
        scope.runCurrent()
        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertEquals(encodeRecentColors(listOf(d, c)), slow.last())
    }
}
