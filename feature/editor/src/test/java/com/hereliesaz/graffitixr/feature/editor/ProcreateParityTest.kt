package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The pure logic behind the Procreate-parity features: brush dynamics determinism (live == replay),
 * QuickShape recognition, and the symmetry / alpha-lock / eyedropper reducer transitions.
 */
class ProcreateParityTest {

    // ── BrushDynamics ────────────────────────────────────────────────────────────────────────

    @Test
    fun `incremental dynamics equals batch dynamics for the same points`() {
        val points = List(40) { i -> Offset(i * 7.3f, sin(i * 0.4) .toFloat() * 30f) }
        val base = 24f

        val batch = BrushDynamics.segmentWidths(points, base)

        val state = BrushDynamics.State()
        val incremental = FloatArray(points.size - 1) { i ->
            state.next((points[i + 1] - points[i]).getDistance(), base)
        }

        assertEquals(batch.size, incremental.size)
        for (i in batch.indices) {
            assertEquals("segment $i", batch[i], incremental[i], 0f)
        }
    }

    @Test
    fun `dynamics is scale invariant`() {
        // Same stroke geometry at 3x scale with a 3x brush must give exactly 3x the widths —
        // that's what lets the live path run in screen space and the replay in bitmap space.
        val points = List(25) { i -> Offset(i * 11f, (i * i % 17) * 4f) }
        val scaled = points.map { Offset(it.x * 3f, it.y * 3f) }

        val w1 = BrushDynamics.segmentWidths(points, 20f)
        val w3 = BrushDynamics.segmentWidths(scaled, 60f)

        for (i in w1.indices) {
            assertEquals("segment $i", w1[i] * 3f, w3[i], 1e-3f)
        }
    }

    @Test
    fun `stroke starts tapered and a fast stroke is thinner than a slow one`() {
        val slow = List(30) { i -> Offset(i * 2f, 0f) }       // 2px per segment
        val fast = List(30) { i -> Offset(i * 60f, 0f) }      // 60px per segment
        val slowW = BrushDynamics.segmentWidths(slow, 30f)
        val fastW = BrushDynamics.segmentWidths(fast, 30f)

        // Start taper: first segment is narrower than a later one.
        assertTrue(slowW.first() < slowW[10])
        // Velocity thinning: at full ramp, fast strokes are thinner than slow ones.
        assertTrue(fastW[20] < slowW[20])
    }

    // ── QuickShape ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a wobbly but straight trace snaps to a line`() {
        val points = List(30) { i -> Offset(i * 20f, 300f + (if (i % 2 == 0) 3f else -3f)) }
        val shape = QuickShape.recognize(points)
        assertTrue(shape is QuickShape.Line)
        val line = shape as QuickShape.Line
        assertEquals(points.first(), line.start)
        assertEquals(points.last(), line.end)
    }

    @Test
    fun `a closed round trace snaps to an ellipse`() {
        val points = List(64) { i ->
            val a = i / 63.0 * 2 * Math.PI
            Offset(
                500f + (160 * cos(a)).toFloat() + (i % 3 - 1) * 2f, // small wobble
                400f + (110 * sin(a)).toFloat() + (i % 2) * 2f,
            )
        }
        val shape = QuickShape.recognize(points)
        assertTrue("expected Ellipse, got $shape", shape is QuickShape.Ellipse)
        val e = shape as QuickShape.Ellipse
        assertEquals(500f, e.center.x, 12f)
        assertEquals(400f, e.center.y, 12f)
        assertEquals(160f, e.radiusX, 15f)
        assertEquals(110f, e.radiusY, 15f)
    }

    @Test
    fun `an open scribble is not recognized`() {
        val points = List(40) { i -> Offset(i * 12f, (i * 37 % 90).toFloat()) }
        assertNull(QuickShape.recognize(points))
    }

    // ── Reducer transitions ──────────────────────────────────────────────────────────────────

    private fun layer(id: String) = Layer(id = id, name = id)

    @Test
    fun `ToggleSymmetry flips the guide`() {
        val on = EditorReducer.reduce(EditorUiState(), EditorIntent.ToggleSymmetry)
        assertTrue(on.symmetryEnabled)
        assertFalse(EditorReducer.reduce(on, EditorIntent.ToggleSymmetry).symmetryEnabled)
    }

    @Test
    fun `ToggleAlphaLock flips only the addressed layer`() {
        val s = EditorUiState(layers = listOf(layer("a"), layer("b")))
        val out = EditorReducer.reduce(s, EditorIntent.ToggleAlphaLock("b"))
        assertFalse(out.layers.first { it.id == "a" }.alphaLock)
        assertTrue(out.layers.first { it.id == "b" }.alphaLock)
    }

    @Test
    fun `SetEyedrop end clears the sampled colour`() {
        val sampling = EditorReducer.reduce(
            EditorUiState(),
            EditorIntent.SetEyedrop(active = true, color = androidx.compose.ui.graphics.Color.Red, position = Offset(5f, 6f)),
        )
        assertTrue(sampling.isEyedropping)
        assertNotNull(sampling.eyedropColor)
        val done = EditorReducer.reduce(sampling, EditorIntent.SetEyedrop(active = false))
        assertFalse(done.isEyedropping)
        assertNull(done.eyedropColor)
    }
}
