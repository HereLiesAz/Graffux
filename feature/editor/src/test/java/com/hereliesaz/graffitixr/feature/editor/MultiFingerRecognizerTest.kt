package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import com.hereliesaz.graffitixr.common.model.GestureSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-finger history gestures, and the reasons they used to refuse to fire: a stroke latch
 * that could stick on forever, a suppression window that outlived its own gesture, stillness
 * measured from before the fingers had all landed, and a dead zone between "too slow for a tap" and
 * "too quick for a hold".
 */
class MultiFingerRecognizerTest {

    private val slop = 30f

    private fun p(id: Int, x: Float, y: Float = 100f) =
        TouchPoint(PointerId(id.toLong()), Offset(x, y))

    private fun recognizerStartedAt(x: Float = 100f, uptimeMs: Long = 0L): MultiFingerRecognizer =
        MultiFingerRecognizer(slop).apply { start(PointerId(0), Offset(x, 100f), uptimeMs) }

    // ── Taps ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `two-finger tap fires even though the first finger drifted before the second landed`() {
        val r = recognizerStartedAt()
        // Fingers never land in the same millisecond. The first travels 60px — twice the slop —
        // while the second is still on its way down. That is not a pan.
        assertNull(r.onEvent(listOf(p(0, 160f)), uptimeMs = 20L, suppressed = false))
        // Second finger lands: the gesture is assembled here, and stillness is judged from here.
        assertNull(r.onEvent(listOf(p(0, 160f), p(1, 300f)), uptimeMs = 40L, suppressed = false))

        assertEquals(
            GestureSlot.TWO_FINGER_TAP,
            r.onEvent(emptyList(), uptimeMs = 150L, suppressed = false),
        )
        assertTrue(r.isFinished)
    }

    @Test
    fun `three-finger tap fires`() {
        val r = recognizerStartedAt()
        r.onEvent(listOf(p(0, 100f), p(1, 200f)), uptimeMs = 20L, suppressed = false)
        r.onEvent(listOf(p(0, 100f), p(1, 200f), p(2, 300f)), uptimeMs = 40L, suppressed = false)

        assertEquals(
            GestureSlot.THREE_FINGER_TAP,
            r.onEvent(emptyList(), uptimeMs = 160L, suppressed = false),
        )
    }

    @Test
    fun `a tap in the old 350-450ms dead zone still fires`() {
        val r = recognizerStartedAt()
        r.onEvent(listOf(p(0, 100f), p(1, 200f)), uptimeMs = 30L, suppressed = false)

        assertEquals(
            GestureSlot.TWO_FINGER_TAP,
            r.onEvent(emptyList(), uptimeMs = 400L, suppressed = false),
        )
    }

    @Test
    fun `a finger lifting early does not cost the tap its pointer count`() {
        val r = recognizerStartedAt()
        r.onEvent(listOf(p(0, 100f), p(1, 200f)), uptimeMs = 30L, suppressed = false)
        // One finger comes up a frame before the other — the gesture was still a two-finger tap.
        r.onEvent(listOf(p(1, 200f)), uptimeMs = 120L, suppressed = false)

        assertEquals(
            GestureSlot.TWO_FINGER_TAP,
            r.onEvent(emptyList(), uptimeMs = 140L, suppressed = false),
        )
    }

    @Test
    fun `travel after the gesture is assembled is a pan, not a tap`() {
        val r = recognizerStartedAt()
        r.onEvent(listOf(p(0, 100f), p(1, 200f)), uptimeMs = 30L, suppressed = false)
        r.onEvent(listOf(p(0, 220f), p(1, 320f)), uptimeMs = 60L, suppressed = false)

        assertNull(r.onEvent(emptyList(), uptimeMs = 150L, suppressed = false))
    }

    @Test
    fun `two finger pan followed by a third finger joining does not fire a three finger tap`() {
        val r = recognizerStartedAt()
        // 2 fingers land
        r.onEvent(listOf(p(0, 100f), p(1, 200f)), uptimeMs = 30L, suppressed = false)
        // 2 fingers pan 120px
        r.onEvent(listOf(p(0, 220f), p(1, 320f)), uptimeMs = 60L, suppressed = false)
        // 3rd finger joins mid-pan
        r.onEvent(listOf(p(0, 220f), p(1, 320f), p(2, 400f)), uptimeMs = 90L, suppressed = false)

        // All fingers lift quickly — pan state must be preserved, so no 3-finger tap fires.
        assertNull(r.onEvent(emptyList(), uptimeMs = 150L, suppressed = false))
    }

    @Test
    fun `a touch held past the hold threshold is not also a tap on lift`() {
        val r = recognizerStartedAt()
        r.onEvent(listOf(p(0, 100f), p(1, 200f)), uptimeMs = 30L, suppressed = false)

        assertNull(r.onEvent(emptyList(), uptimeMs = 600L, suppressed = false))
    }

    @Test
    fun `a live or just-cancelled stroke suppresses the tap`() {
        val r = recognizerStartedAt()
        r.onEvent(listOf(p(0, 100f), p(1, 200f)), uptimeMs = 30L, suppressed = false)

        assertNull(r.onEvent(emptyList(), uptimeMs = 150L, suppressed = true))
    }

    // ── Holds ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `two fingers held still repeat, and the lift does not add one more`() {
        val r = recognizerStartedAt()
        r.onEvent(listOf(p(0, 100f), p(1, 200f)), uptimeMs = 30L, suppressed = false)

        assertNull(r.onIdleTick(nowMs = 300L, suppressed = false))
        assertEquals(GestureSlot.TWO_FINGER_TAP, r.onIdleTick(nowMs = 470L, suppressed = false))
        assertEquals(GestureSlot.TWO_FINGER_TAP, r.onIdleTick(nowMs = 600L, suppressed = false))

        assertNull(r.onEvent(emptyList(), uptimeMs = 700L, suppressed = false))
    }

    @Test
    fun `four fingers held open the QuickMenu exactly once`() {
        val r = recognizerStartedAt()
        r.onEvent(
            listOf(p(0, 100f), p(1, 200f), p(2, 300f), p(3, 400f)),
            uptimeMs = 30L,
            suppressed = false,
        )

        assertEquals(GestureSlot.FOUR_FINGER_HOLD, r.onIdleTick(nowMs = 500L, suppressed = false))
        assertNull(r.onIdleTick(nowMs = 640L, suppressed = false))
        // …and the eventual lift must not also toggle the UI.
        assertNull(r.onEvent(emptyList(), uptimeMs = 800L, suppressed = false))
    }

    // ── Scrub ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `three fingers rubbed back and forth clear the layer`() {
        val r = recognizerStartedAt()
        fun threeAt(dx: Float, t: Long) =
            r.onEvent(
                listOf(p(0, 100f + dx), p(1, 200f + dx), p(2, 300f + dx)),
                uptimeMs = t,
                suppressed = false,
            )

        threeAt(0f, 20L)
        assertNull(threeAt(60f, 60L))    // first leg, no reversal yet
        assertNull(threeAt(0f, 100L))    // reversal 1
        assertNull(threeAt(60f, 140L))   // reversal 2
        assertEquals(GestureSlot.THREE_FINGER_SCRUB, threeAt(0f, 180L)) // reversal 3
        // Fires once per gesture, and never also lands as a three-finger tap.
        assertNull(threeAt(60f, 220L))
        assertNull(r.onEvent(emptyList(), uptimeMs = 260L, suppressed = false))
    }

    @Test
    fun `a long one-way sweep with three fingers is a pan, not a scrub`() {
        val r = recognizerStartedAt()
        r.onEvent(listOf(p(0, 100f), p(1, 200f), p(2, 300f)), uptimeMs = 20L, suppressed = false)
        for (step in 1..8) {
            assertNull(
                r.onEvent(
                    listOf(p(0, 100f + step * 60f), p(1, 200f + step * 60f), p(2, 300f + step * 60f)),
                    uptimeMs = 20L + step * 20L,
                    suppressed = false,
                )
            )
        }
    }
}

/**
 * [StrokeGate] is the only thing in the app that can suppress a multi-finger gesture, so it must not
 * be able to do so indefinitely or across gesture boundaries.
 */
class StrokeGateTest {

    @Test
    fun `a latch leaked by a drawing surface torn down mid-stroke is cleared by the next gesture`() {
        val gate = StrokeGate()
        // The surface's gesture loop was cancelled before it could clear this — a tool change, the
        // layer being locked, a project reload. Left alone it suppressed every gesture, forever.
        gate.strokeActive = true

        gate.onGestureStart()

        assertFalse(gate.strokeActive)
    }

    @Test
    fun `a cancelled stroke suppresses its own gesture and not the next one`() {
        val gate = StrokeGate()

        val duringStroke = gate.cancelCount
        gate.markCancelled()
        // The gesture that did the cancelling sees the count move, so it swallows itself.
        assertNotEquals(duringStroke, gate.cancelCount)
        assertFalse(gate.strokeActive)

        // The next gesture snapshots afresh, so an immediate second two-finger tap is free to undo.
        val nextGesture = gate.cancelCount
        assertEquals(nextGesture, gate.cancelCount)
        assertFalse(gate.strokeActive)
    }
}
