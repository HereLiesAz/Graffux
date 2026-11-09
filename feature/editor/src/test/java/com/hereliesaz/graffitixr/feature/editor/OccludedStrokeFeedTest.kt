@file:Suppress("MagicNumber", "MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.common.scene.SurfaceStrokeGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stroke on a surface paints only what the camera sees (spec/3d-space.md, "Paint reaches only what the
 * camera sees"): a sample behind another surface is dropped like a miss, and a stroke that goes behind a
 * fold and comes back resumes as a new segment, without counting a join cut.
 */
class OccludedStrokeFeedTest {
    private val w = 1000f
    private val h = 1600f

    private fun sheet(id: String, z: Float, x1: Float) = Layer(
        id = id, name = id, type = LayerType.GROUP, surface = SurfaceProfile(listOf(0f, 0f, z, x1, 0f, z), 0f, 1f, 0f),
    )

    private fun plane(camera: SceneCamera = SceneCamera(), near: Layer = sheet("near", 0f, 600f)): ActivePlaneMapping {
        val layers = listOf(near, sheet("far", 200f, 1200f), Layer(id = "leaf", name = "leaf", parentId = "far"))
        return ActivePlaneMapping.of(
            EditorUiState(
                layers = layers, activeLayerId = "leaf", canvasSize = IntSize(w.toInt(), h.toInt()), sceneCamera = camera,
                viewportOffset = Offset.Zero, viewportZoom = 1f, viewportRotation = 0f,
            ),
        )
    }

    /** Where the far sheet's point (x, 100) shows on the canvas. */
    private fun touch(x: Float, camera: SceneCamera = SceneCamera()): Offset =
        SceneMath.frame(camera, w, h).project(floatArrayOf(x, 100f, 200f))!!.let { Offset(it.first, it.second) }

    private class Run(plane: ActivePlaneMapping, radius: Float = 4f) {
        val segments = mutableListOf<MutableList<Offset>>()
        val feed = PlaneStrokeFeed<Offset>(
            toLayer = { plane.toFlatByFootprint(it, radius) },
            start = { segments += mutableListOf(it) },
            point = { segments.last() += it },
            end = {},
            cancel = {},
            segmentEnd = {},
            sampleOk = { raw, mapped -> plane.flatOnFinger(raw, mapped, radius) },
            joinOk = { pr, pm, r, m -> plane.flatJoinOnFinger(pr, pm, r, m, radius) },
        )
        fun stroke(touches: List<Offset>) {
            touches.forEachIndexed { i, t -> if (i == 0) feed.onStart(t) else feed.onPoint(t) }
            feed.onEnd()
        }
    }

    private fun xs(from: Float, to: Float, step: Float) = generateSequence(from) { it + step }.takeWhile { if (step > 0) it <= to else it >= to }.toList()

    @Test fun `a stroke wholly in view is one segment`() {
        val run = Run(plane())
        run.stroke(xs(900f, 700f, -20f).map { touch(it) })
        assertEquals(1, run.segments.size)
        assertEquals(11, run.segments[0].size)
    }

    @Test fun `a stroke behind a fold and back out splits, dropping the hidden samples`() {
        val cuts = SurfaceStrokeGuard.cuts
        val run = Run(plane())
        // In view (x 900..700), behind the near sheet (x < ~610), then in view again.
        run.stroke((xs(900f, 100f, -20f) + xs(120f, 900f, 20f)).map { touch(it) })
        assertEquals("one segment out, one on re-entry", 2, run.segments.size)
        assertTrue(run.segments.all { it.size > 3 })
        assertEquals("a hidden sample is a miss, not a join cut", cuts, SurfaceStrokeGuard.cuts)
    }

    @Test fun `a single step over a hidden stretch is cut at the join, not bridged`() {
        // A narrow occluder shadows far x ~ 335..665 only; both ends of the step are in view, the middle is not.
        val strip = Layer(
            id = "near", name = "near", type = LayerType.GROUP,
            surface = SurfaceProfile(listOf(350f, 0f, 0f, 650f, 0f, 0f), 0f, 1f, 0f),
        )
        val cuts = SurfaceStrokeGuard.cuts
        val run = Run(plane(near = strip))
        run.stroke(listOf(touch(300f), touch(700f)))
        assertEquals("each end alone", 2, run.segments.size)
        assertEquals("a hidden middle is not a gap in the surface", cuts, SurfaceStrokeGuard.cuts)
        val open = Run(plane(near = strip.copy(isVisible = false)))
        open.stroke(listOf(touch(300f), touch(700f)))
        assertEquals("with the occluder hidden it is one stroke", 1, open.segments.size)
    }

    @Test fun `moving the camera changes what is painted`() {
        val hidden = touch(300f)
        val behind = Run(plane())
        behind.stroke(listOf(hidden, hidden + Offset(2f, 0f)))
        assertEquals(0, behind.segments.size)
        val cam = SceneCamera(yaw = 1.2f)
        val moved = Run(plane(cam))
        moved.stroke(listOf(touch(300f, cam), touch(300f, cam) + Offset(2f, 0f)))
        assertEquals(1, moved.segments.size)
    }

    @Test fun `a hidden near group does not hide the far one`() {
        val near = sheet("near", 0f, 600f).copy(isVisible = false)
        val run = Run(plane(near = near))
        run.stroke(xs(500f, 300f, -20f).map { touch(it) })
        assertEquals(1, run.segments.size)
    }
}
