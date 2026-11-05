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
        var segments = 0
        fun feed(map: (Offset) -> Offset?) = PlaneStrokeFeed(
            toLayer = map,
            start = { started += it },
            point = { points += it },
            end = { ended++ },
            cancel = { cancelled++ },
            segmentEnd = { segments++ },
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
        // The gap ends a segment, not the gesture: finger-up ends it once.
        assertEquals(1, r.segments)
        assertEquals(1, r.ended)
        assertEquals(0, r.cancelled)
    }

    @Test
    fun `a finished stroke is cut into the runs that hit the plane, in sample order`() {
        val path = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(2f, 0f), Offset(3f, 0f), Offset(4f, 0f))
        val segments = planeSegments(path) { p -> p.takeIf { it.x != 2f }?.let { Offset(it.x * 10f, 5f) } }
        assertEquals(listOf(listOf(0, 1), listOf(3, 4)), segments.map { s -> s.map { it.first } })
        assertEquals(Offset(30f, 5f), segments[1][0].second)
        assertTrue(planeSegments(path) { null }.isEmpty())
    }

    @Test
    fun `a finished stroke on a tilted layer maps like the live brush does`() {
        val plane = ActivePlaneMapping.of(state(Layer(id = "a", name = "a", rotationX = 40f)))
        val seg = planeSegments(touches, plane::worldToFlat).single()
        assertEquals(touches.map { plane.toFlat(it) }, seg.map { it.second })
    }
}

/**
 * Brush and Eraser strokes on a bent group surface map by footprint (spec/3d-space.md, "Painting by
 * footprint"): no sample is dropped for where on the surface it lands, a stroke over a crease is one
 * continuous segment touching both sides, and a footprint that reaches the surface is not a miss.
 */
class SurfaceFootprintStrokeTest {
    private val w = 1000f
    private val h = 800f
    private val cam = SceneCamera()
    private val identity = listOf(com.hereliesaz.graffitixr.common.scene.PlanePose())

    private fun path(vararg p: Pair<Float, Float>) = p.toList()

    private fun bent(): EditorUiState {
        fun seg(a: Pair<Float, Float>, b: Pair<Float, Float>) = (0..40).map {
            (a.first + (b.first - a.first) * it / 40f) to (a.second + (b.second - a.second) * it / 40f)
        }
        val profile = com.hereliesaz.graffitixr.common.scene.SurfaceMath.capture(
            seg(200f to 200f, 200f to 600f) + seg(200f to 600f, 600f to 600f).drop(1), cam, w, h, identity,
        )!!
        val group = Layer("g", "G", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP, surface = profile)
        val child = Layer("c", "C", parentId = "g")
        return EditorUiState(
            layers = listOf(group, child),
            activeLayerId = "c",
            canvasSize = IntSize(w.toInt(), h.toInt()),
            sceneCamera = cam,
            viewportOffset = Offset.Zero,
            viewportZoom = 1f,
            viewportRotation = 0f,
        )
    }

    private val across = listOf(
        Offset(215f, 500f), Offset(215f, 560f), Offset(215f, 590f),
        Offset(235f, 597f), Offset(280f, 597f), Offset(340f, 597f),
    )

    private fun run(plane: ActivePlaneMapping, touches: List<Offset>, radius: Float): Triple<List<Offset>, Int, Int> {
        val flat = mutableListOf<Offset>()
        var segments = 0
        var cancelled = 0
        val feed = PlaneStrokeFeed<Offset>(
            toLayer = { plane.toFlatByFootprint(it, radius) },
            start = { flat += it },
            point = { flat += it },
            end = {},
            cancel = { cancelled++ },
            segmentEnd = { segments++ },
        )
        touches.forEachIndexed { i, t -> if (i == 0) feed.onStart(t) else feed.onPoint(t) }
        feed.onEnd()
        return Triple(flat, segments, cancelled)
    }

    @Test
    fun `a stroke across the crease is one segment with a point per sample, on both legs`() {
        val plane = ActivePlaneMapping.of(bent())
        assertTrue(plane.onPlane)
        val (flat, segments, cancelled) = run(plane, across, radius = 20f)
        assertEquals(across.size, flat.size)
        assertEquals(0, segments)
        assertEquals(0, cancelled)
        assertTrue("wall side", flat.first().x < 400f)
        assertTrue("floor side", flat.last().x > 400f)
    }

    @Test
    fun `an eraser stroke straddling the crease erases on both sides, whatever the radius`() {
        val plane = ActivePlaneMapping.of(bent())
        for (radius in listOf(1f, 10f, 40f)) {
            val (flat, segments, _) = run(plane, across, radius)
            assertEquals("radius $radius", across.size, flat.size)
            assertEquals(0, segments)
            assertTrue(flat.any { it.x < 400f } && flat.any { it.x > 400f })
        }
    }

    @Test
    fun `samples that graze the surface edge keep the stroke whole instead of breaking it`() {
        val plane = ActivePlaneMapping.of(bent())
        // The middle sample's ray misses (below the floor's near edge); its footprint still reaches.
        val grazing = listOf(Offset(300f, 596f), Offset(340f, 612f), Offset(380f, 596f))
        val (flat, segments, _) = run(plane, grazing, radius = 30f)
        assertEquals(3, flat.size)
        assertEquals(0, segments)
    }

    @Test
    fun `a footprint that reaches nothing is still a miss, and a stroke of misses is cancelled`() {
        val plane = ActivePlaneMapping.of(bent())
        val (flat, _, cancelled) = run(plane, listOf(Offset(500f, 60f), Offset(520f, 70f)), radius = 15f)
        assertTrue(flat.isEmpty())
        assertEquals(1, cancelled)
    }

    @Test
    fun `a flat layer's mapping is untouched by the footprint`() {
        val plane = ActivePlaneMapping.of(
            bent().copy(layers = listOf(Layer("f", "F")), activeLayerId = "f"),
        )
        assertTrue(!plane.onPlane)
        assertEquals(Offset(420f, 700f), plane.toFlatByFootprint(Offset(420f, 700f), 80f))
    }
}
