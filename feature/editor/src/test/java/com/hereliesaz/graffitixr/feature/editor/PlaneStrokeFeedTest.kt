package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.common.scene.planeChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stroke on a rotated 3D layer (spec/3d-space.md, "Painting on a tilted plane"): every point that
 * is painted lies on the layer's plane under the finger, and a touch whose ray misses the plane
 * paints nothing (it used to paint the plain screen point, i.e. where the layer sat before it was
 * rotated, so a further rotation showed the new paint flat on the original plane).
 */
class PlaneStrokeFeedTest {
    private val w = 1000f
    private val h = 1600f

    private fun state(layer: Layer, camera: SceneCamera = SceneCamera()) = EditorUiState(
        layers = listOf(layer),
        activeLayerId = layer.id,
        canvasSize = IntSize(w.toInt(), h.toInt()),
        sceneCamera = camera,
        // Screen = world, so a touch can be compared with where the plane shows it.
        viewportOffset = Offset.Zero,
        viewportZoom = 1f,
        viewportRotation = 0f,
    )

    private class Recorder {
        val started = mutableListOf<Offset>()
        val points = mutableListOf<Offset>()
        var ended = 0
        var cancelled = 0
        fun feed(map: (Offset) -> Offset?) = PlaneStrokeFeed(
            toLayer = map,
            start = { started += it },
            point = { points += it },
            end = { ended++ },
            cancel = { cancelled++ },
        )
    }

    private val touches = listOf(Offset(420f, 700f), Offset(460f, 760f), Offset(510f, 820f))

    @Test
    fun `a layer turned 90 degrees is edge-on, so a stroke on it paints nothing and is cancelled`() {
        val layer = Layer(id = "a", name = "a", rotationY = 90f)
        val plane = ActivePlaneMapping.of(state(layer))
        touches.forEach { assertNull("ray $it lies in the edge-on plane", plane.toFlat(it)) }
        val r = Recorder()
        val feed = r.feed(plane::toFlat)
        feed.onStart(touches[0])
        touches.drop(1).forEach(feed::onPoint)
        feed.onEnd()
        assertTrue(r.started.isEmpty() && r.points.isEmpty())
        assertEquals(0, r.ended)
        assertEquals(1, r.cancelled)
    }

    @Test
    fun `under a view orbited 90 degrees the eye lies in the layer plane, which is no hit`() {
        // Before the fix rounding put a hit at the eye, ~(2431, 800) in flat space for every touch.
        val layer = Layer(id = "a", name = "a")
        val plane = ActivePlaneMapping.of(state(layer, SceneCamera(yaw = (Math.PI / 2).toFloat())))
        touches.forEach { assertNull(plane.toFlat(it)) }
    }

    @Test
    fun `a stroke on a rotated layer lands on its plane under the finger and turns with it`() {
        // Rotated on one axis, drawn, then rotated on a second axis.
        val tilted = Layer(id = "a", name = "a", rotationX = 60f)
        val plane = ActivePlaneMapping.of(state(tilted))
        val r = Recorder()
        val feed = r.feed(plane::toFlat)
        feed.onStart(touches[0])
        touches.drop(1).forEach(feed::onPoint)
        feed.onEnd()
        assertEquals(1, r.ended)
        val painted = r.started + r.points
        assertEquals(touches.size, painted.size)
        val chain = planeChain(listOf(tilted), "a")
        painted.zip(touches).forEach { (flat, touch) ->
            // Through the plane back to the screen: exactly under the finger.
            val shown = assertNotNullPair(SceneMath.layerFlatToWorld(flat.x, flat.y, chain, SceneCamera(), w, h))
            assertEquals(touch.x, shown.first, 0.5f)
            assertEquals(touch.y, shown.second, 0.5f)
        }
        // A second rotation moves the stroke with the plane: each point stays at the same place in
        // the layer, so it is still on the plane, now the turned one.
        val turned = tilted.copy(rotationY = 40f)
        val turnedChain = planeChain(listOf(turned), "a")
        val model = SceneMath.chainModel(turnedChain, w, h)
        val flatInv = SceneMath.model(turnedChain.last().copy(positionZ = 0f, rotationX = 0f, rotationY = 0f), w, h)
            .inverse()!!
        val n = cross(model.column(0), model.column(1))
        val origin = model.column(3)
        painted.forEach { flat ->
            val local = flatInv.apply(flat.x, flat.y, 0f)
            assertEquals(0f, local[2], 1e-3f)
            val p = model.apply(local[0], local[1], 0f)
            val off = (0..2).sumOf { (n[it] * (p[it] - origin[it])).toDouble() }
            assertEquals(0.0, off, 1e-2 * kotlin.math.sqrt((0..2).sumOf { (n[it] * n[it]).toDouble() }))
        }
        // The old fallback would have painted the plain screen point, which is not under the finger.
        val old = touches[0]
        val oldShown = assertNotNullPair(SceneMath.layerFlatToWorld(old.x, old.y, chain, SceneCamera(), w, h))
        assertTrue(kotlin.math.abs(oldShown.second - old.y) > 5f)
    }

    private fun assertNotNullPair(p: Pair<Float, Float>?): Pair<Float, Float> {
        assertNotNull(p)
        return p!!
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    @Test
    fun `a stroke that leaves the plane and comes back is broken at the gap, not bridged`() {
        val r = Recorder()
        val hits = listOf(Offset(1f, 1f), null, null, Offset(9f, 9f), Offset(10f, 10f))
        var n = 0
        val feed = r.feed { hits[n++] }
        feed.onStart(Offset.Zero)
        repeat(hits.size - 1) { feed.onPoint(Offset.Zero) }
        feed.onEnd()
        assertEquals(listOf(Offset(1f, 1f), Offset(9f, 9f)), r.started)
        assertEquals(listOf(Offset(10f, 10f)), r.points)
        assertEquals(2, r.ended)
        assertEquals(0, r.cancelled)
    }
}
