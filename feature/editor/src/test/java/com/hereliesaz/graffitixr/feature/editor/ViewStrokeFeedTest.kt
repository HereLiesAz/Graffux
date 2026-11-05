package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.SurfaceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A stroke over a multi-view surface is cut per paint image (spec/3d-space.md, "Paint per view"). */
class ViewStrokeFeedTest {
    private class Log {
        val events = ArrayList<String>()
    }

    /** Touch x < 10 is the FRONT image at (x, 0); x >= 10 is the SIDE image at (x - 10, 0); x < 0 misses. */
    private fun feed(log: Log, bridge: Boolean = true) = ViewStrokeFeed<Float>(
        toView = { x ->
            when {
                x < 0f -> null
                x < 10f -> ViewSample(SurfaceView.FRONT, x, x)
                else -> ViewSample(SurfaceView.SIDE, x, x - 10f)
            }
        },
        bridge = { _, _ -> if (bridge) 10f to 0f else null },
        start = { v, s -> log.events += "start $v $s" },
        point = { log.events += "point $it" },
        end = { log.events += "end" },
        cancel = { log.events += "cancel" },
        segmentEnd = { log.events += "segmentEnd" },
    )

    @Test
    fun `a stroke from a view's sheet onto its cap is cut like a change of view`() {
        // Same image, two faces of it (region 0 its sheet, 1 a cap): painting across would draw a line over the gap.
        val log = Log()
        val f = ViewStrokeFeed<Float>(
            toView = { x -> ViewSample(SurfaceView.FRONT, x, x, region = if (x < 10f) 0 else 1) },
            bridge = { _, _ -> 10f to 50f },
            start = { v, s -> log.events += "start $v $s" },
            point = { log.events += "point $it" },
            end = { log.events += "end" },
            cancel = { log.events += "cancel" },
            segmentEnd = { log.events += "segmentEnd" },
        )
        f.onStart(8f); f.onPoint(12f); f.onEnd()
        assertEquals(
            listOf("start FRONT 8.0", "point 10.0", "segmentEnd", "start FRONT 50.0", "point 12.0", "end"),
            log.events,
        )
    }

    @Test
    fun `a stroke inside one view is one segment`() {
        val log = Log()
        val f = feed(log)
        f.onStart(1f); f.onPoint(2f); f.onEnd()
        assertEquals(listOf("start FRONT 1.0", "point 2.0", "end"), log.events)
    }

    @Test
    fun `a stroke crossing a view cuts at the crease and leaves no gap`() {
        val log = Log()
        val f = feed(log)
        f.onStart(8f); f.onPoint(12f); f.onPoint(14f); f.onEnd()
        assertEquals(
            listOf(
                "start FRONT 8.0",
                "point 10.0", // the first image runs to the crease
                "segmentEnd",
                "start SIDE 0.0", // the next begins at the crease's other side
                "point 2.0",
                "point 4.0",
                "end",
            ),
            log.events,
        )
    }

    @Test
    fun `without a trustworthy crease the cut is between the touches`() {
        val log = Log()
        val f = feed(log, bridge = false)
        f.onStart(8f); f.onPoint(12f); f.onEnd()
        assertEquals(listOf("start FRONT 8.0", "segmentEnd", "start SIDE 2.0", "end"), log.events)
    }

    @Test
    fun `leaving the surface and coming back breaks the stroke, and one that never lands is cancelled`() {
        val log = Log()
        val f = feed(log)
        f.onStart(1f); f.onPoint(-1f); f.onPoint(2f); f.onEnd()
        assertEquals(listOf("start FRONT 1.0", "segmentEnd", "start FRONT 2.0", "end"), log.events)
        val missed = Log()
        val g = feed(missed)
        g.onStart(-1f); g.onPoint(-2f); g.onEnd()
        assertEquals(listOf("cancel"), missed.events)
    }

    @Test
    fun `a stroke back and forth across the crease is cut every time`() {
        val log = Log()
        val f = feed(log)
        f.onStart(8f); f.onPoint(12f); f.onPoint(9f); f.onEnd()
        assertEquals(2, log.events.count { it == "segmentEnd" })
        assertTrue(log.events.last() == "end")
    }

    @Test
    fun `a layer that is not on a multi-view surface is not on the hull`() {
        val group = Layer("g", "G", type = LayerType.GROUP)
        val leaf = Layer("a", "A", parentId = "g")
        val state = EditorUiState(layers = listOf(group, leaf), activeLayerId = "a")
        assertFalse(ActivePlaneMapping.of(state).onHull)
    }
}
