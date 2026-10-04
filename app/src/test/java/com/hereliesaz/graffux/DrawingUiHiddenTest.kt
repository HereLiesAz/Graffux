package com.hereliesaz.graffux

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The post-stroke hold, on the test dispatcher's virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class DrawingUiHiddenTest {

    private val stroke = MutableStateFlow(false)
    private var hidden = false

    private fun TestScope.start() = backgroundScope.launch {
        trackDrawingUiHidden(stroke) { hidden = it }
    }.also { runCurrent() }

    @Test
    fun `the default hold is 1500ms`() {
        assertEquals(1500L, DRAWING_UI_RETURN_DELAY_MS)
    }

    @Test
    fun `hidden during the stroke and until the hold runs out`() = runTest {
        start()
        assertFalse(hidden)

        stroke.value = true
        runCurrent()
        assertTrue("hidden the moment the stroke starts", hidden)
        advanceTimeBy(10_000)
        assertTrue("hidden for as long as the stroke lasts", hidden)

        stroke.value = false
        runCurrent()
        assertTrue("still hidden as the stroke ends", hidden)
        advanceTimeBy(1499)
        runCurrent()
        assertTrue("still hidden at 1499ms", hidden)
        advanceTimeBy(1)
        runCurrent()
        assertFalse("shown at 1500ms", hidden)
    }

    @Test
    fun `a second stroke inside the hold cancels the return`() = runTest {
        start()
        stroke.value = true
        runCurrent()
        stroke.value = false
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()

        stroke.value = true
        runCurrent()
        assertTrue(hidden)
        // Past where the first stroke's return would have landed: nothing came back.
        advanceTimeBy(1000)
        runCurrent()
        assertTrue("the first stroke's return was cancelled", hidden)

        stroke.value = false
        runCurrent()
        advanceTimeBy(1499)
        runCurrent()
        assertTrue("the hold restarts from the second stroke's end", hidden)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(hidden)
    }

    @Test
    fun `the hold length is a parameter`() = runTest {
        backgroundScope.launch { trackDrawingUiHidden(stroke, holdMs = 200) { hidden = it } }
        runCurrent()
        stroke.value = true
        runCurrent()
        stroke.value = false
        runCurrent()
        advanceTimeBy(199)
        runCurrent()
        assertTrue(hidden)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(hidden)
    }
}
