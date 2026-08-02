package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.hereliesaz.graffitixr.common.model.Tool
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.math.abs

/**
 * **Two fingers move the canvas. One finger never does.**
 *
 * This had no coverage of any kind until this file. The gesture is not a function anyone can call —
 * it is arbitration between stacked `pointerInput` layers, decided by z-order, by which layer
 * consumes, and by how each one reacts to a second finger landing mid-gesture. None of that is
 * visible to a unit test of a view-model, and reading it proves nothing: the comments in
 * `EditorScreen` explaining why the layering is correct were written by the same hand that wrote
 * the layering, and agree with it for that reason and no other.
 *
 * So these tests drive real synthesised multi-touch through a real Compose hierarchy and assert on
 * what the camera callback receives. What they cover is the *arbitration*, which is where the bugs
 * live; the arithmetic that turns a pinch into a viewport is `EditorViewModel.onViewportPanZoom`
 * and is covered separately below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class CanvasNavigationTest {

    @get:Rule
    val rule = createComposeRule()

    /** What the camera was told to do during a gesture. */
    private class Camera {
        var events = 0
        var pan = Offset.Zero
        var zoom = 1f
        var rotation = 0f
        fun record(p: Offset, z: Float, r: Float) {
            events++
            pan += p
            zoom *= z
            rotation += r
        }
    }

    /**
     * The real layering: [canvasNavigation] on an ancestor, with a painting surface as a child that
     * claims single-finger drags. Reproduced rather than hosting `EditorScreen` itself, which needs
     * a Hilt view-model, a layer stack and a bitmap store — none of which this is about. What
     * matters is the shape: a greedy consumer below the camera in the tree, and the second finger
     * deciding between them.
     */
    private fun setContent(camera: Camera, painting: MutableList<Offset>) {
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    // The camera, on the ANCESTOR — the arrangement EditorScreen uses. Mounted as a
                    // sibling underneath the surface below, it receives nothing at all.
                    .canvasNavigation { pan, zoom, _, rotation -> camera.record(pan, zoom, rotation) }
            ) {
                // The real brush surface, as a child: it sees each event first and consumes while
                // painting. Everything it needs beyond the gesture is stubbed; only the pointer
                // handling is under test.
                DrawingCanvas(
                    activeTool = Tool.BRUSH,
                    brushSize = 20f,
                    activeColor = Color.Red,
                    layerBitmapKey = null,
                    gate = StrokeGate(),
                    modifier = Modifier.fillMaxSize(),
                    onStrokeStart = { at, _ -> painting += at },
                    onStrokePoint = { painting += it },
                    onStrokeEnd = {},
                    onStrokeCancel = {},
                    onFillTap = { _, _ -> },
                    onEyedropStart = {},
                    onEyedropSample = {},
                    onEyedropEnd = {},
                )
            }
        }
    }

    @Test
    fun `two fingers pinching move the camera`() {
        val camera = Camera()
        val painting = mutableListOf<Offset>()
        setContent(camera, painting)

        rule.onRoot().performTouchInput {
            // Two fingers, moving apart along x: a pinch-to-zoom.
            down(0, Offset(150f, 400f))
            down(1, Offset(250f, 400f))
            moveTo(0, Offset(100f, 400f))
            moveTo(1, Offset(300f, 400f))
            moveTo(0, Offset(50f, 400f))
            moveTo(1, Offset(350f, 400f))
            up(0)
            up(1)
        }

        assertTrue("the camera was never told anything", camera.events > 0)
        assertTrue("fingers moving apart must zoom in, got ${camera.zoom}", camera.zoom > 1.05f)
    }

    @Test
    fun `two fingers dragging together pan the camera`() {
        val camera = Camera()
        val painting = mutableListOf<Offset>()
        setContent(camera, painting)

        rule.onRoot().performTouchInput {
            down(0, Offset(150f, 400f))
            down(1, Offset(250f, 400f))
            moveTo(0, Offset(150f, 500f))
            moveTo(1, Offset(250f, 500f))
            up(0)
            up(1)
        }

        assertTrue("two fingers moving together must pan, got ${camera.pan}", camera.pan.y > 50f)
        assertTrue("a straight drag must not zoom, got ${camera.zoom}", abs(camera.zoom - 1f) < 0.05f)
    }

    /**
     * The rule that makes the whole scheme work: a single finger belongs to the tool, always. If the
     * camera ever claimed one, painting a stroke would drag the canvas out from under the stroke.
     */
    @Test
    fun `one finger never moves the camera, and paints instead`() {
        val camera = Camera()
        val painting = mutableListOf<Offset>()
        setContent(camera, painting)

        rule.onRoot().performTouchInput {
            down(0, Offset(150f, 400f))
            moveTo(0, Offset(200f, 450f))
            moveTo(0, Offset(250f, 500f))
            up(0)
        }

        assertEquals("one finger must never reach the camera", 0, camera.events)
        assertTrue("one finger must reach the painting surface, got ${painting.size} points", painting.size >= 2)
    }

    /**
     * The handover, and the case that actually breaks in practice: a stroke is already in flight
     * when the second finger lands. The painting surface has consumed every event so far, so if it
     * keeps consuming — or if the camera layer gave up when it saw the first finger claimed — the
     * canvas stays frozen and the gesture does nothing at all.
     */
    @Test
    fun `a second finger landing mid-stroke hands the gesture to the camera`() {
        val camera = Camera()
        val painting = mutableListOf<Offset>()
        setContent(camera, painting)

        rule.onRoot().performTouchInput {
            down(0, Offset(150f, 400f))
            moveTo(0, Offset(200f, 400f))     // painting; this finger is consumed
            moveTo(0, Offset(220f, 400f))
            down(1, Offset(320f, 400f))       // second finger arrives
            moveTo(0, Offset(200f, 400f))
            moveTo(1, Offset(340f, 400f))
            moveTo(0, Offset(180f, 400f))
            moveTo(1, Offset(360f, 400f))
            up(0)
            up(1)
        }

        assertTrue("painting should have started before the second finger", painting.isNotEmpty())
        assertTrue(
            "once two fingers are down the camera must move, even though the first was consumed",
            camera.events > 0,
        )
    }
    /**
     * The regression that actually shipped, pinned end to end.
     *
     * The tests above mount [canvasNavigation] themselves, so they prove the gesture layer works —
     * but they would all still pass if `EditorScreen` went back to attaching it as a sibling layer,
     * which is precisely the bug. This one drives the real screen and asserts on the real viewport
     * state, so it fails if the modifier is ever moved back off the ancestor.
     */
    @Test
    fun `EditorScreen itself pinch-zooms the viewport`() {
        RenderTestBase.stubNativeLibs()
        val vm = EditorViewModelFixture.build(UnconfinedTestDispatcher())
        rule.setContent { EditorScreen(vm = vm, modifier = Modifier.fillMaxSize()) }
        rule.waitForIdle()

        val before = vm.uiState.value.viewportZoom
        rule.onRoot().performTouchInput {
            down(0, Offset(150f, 400f))
            down(1, Offset(250f, 400f))
            moveTo(0, Offset(100f, 400f))
            moveTo(1, Offset(300f, 400f))
            moveTo(0, Offset(50f, 400f))
            moveTo(1, Offset(350f, 400f))
            up(0)
            up(1)
        }
        rule.waitForIdle()

        assertTrue(
            "a two-finger pinch on the real screen must change the viewport zoom (was $before, " +
                "now ${vm.uiState.value.viewportZoom})",
            vm.uiState.value.viewportZoom > before * 1.05f,
        )
    }
}