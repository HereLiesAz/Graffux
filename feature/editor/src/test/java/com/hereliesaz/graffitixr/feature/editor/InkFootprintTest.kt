package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.PlanePose
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SurfaceMath
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.common.scene.SurfaceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An Ink stroke maps through the same footprint as the Brush and the Eraser (spec/3d-space.md,
 * "Painting by footprint"): the touch ray first, else the nearest point of the surface the brush
 * reaches; and on a multi-view surface it is cut per view image at the crease.
 */
@Suppress("MaxLineLength")
class InkFootprintTest {
    private val w = 1000f
    private val h = 800f

    private fun bent(): EditorUiState {
        fun seg(a: Pair<Float, Float>, b: Pair<Float, Float>) = (0..40).map {
            (a.first + (b.first - a.first) * it / 40f) to (a.second + (b.second - a.second) * it / 40f)
        }
        val profile = SurfaceMath.capture(
            seg(200f to 200f, 200f to 600f) + seg(200f to 600f, 600f to 600f).drop(1), SceneCamera(), w, h, listOf(PlanePose()),
        )!!
        return surfaceState(profile, SceneCamera())
    }

    private fun surfaceState(profile: SurfaceProfile, cam: SceneCamera) = EditorUiState(
        layers = listOf(Layer("g", "G", type = LayerType.GROUP, surface = profile), Layer("c", "C", parentId = "g")),
        activeLayerId = "c",
        canvasSize = IntSize(w.toInt(), h.toInt()),
        sceneCamera = cam,
        viewportOffset = Offset.Zero,
        viewportZoom = 1f,
        viewportRotation = 0f,
    )

    @Test
    fun `an Ink sample whose ray misses the surface but whose footprint reaches it is kept`() {
        val plane = ActivePlaneMapping.of(bent())
        val grazing = listOf(Offset(300f, 596f), Offset(340f, 612f), Offset(380f, 596f))
        // By the ray alone the middle sample is dropped and the stroke breaks (the old behaviour).
        assertEquals(2, planeSegments(grazing, toFlat = plane::worldToFlat).size)
        val segments = plane.inkSegments(grazing, footprint = 30f)
        assertEquals(1, segments.size)
        assertEquals(listOf(0, 1, 2), segments.single().samples.map { it.first })
        assertTrue(segments.single().view == null)
    }

    @Test
    fun `an Ink footprint that reaches nothing is a miss and the stroke has no segments`() {
        val plane = ActivePlaneMapping.of(bent())
        assertTrue(plane.inkSegments(listOf(Offset(500f, 60f), Offset(520f, 70f)), footprint = 15f).isEmpty())
    }

    // ── A multi-view hull ──────────────────────────────────────────────────────────────────────

    private fun outline(sweep: FloatArray, place: (Float, Float) -> FloatArray) = SurfaceProfile(
        listOf(300f to 200f, 700f to 200f, 700f to 600f, 300f to 600f).flatMap { place(it.first, it.second).toList() },
        sweep[0], sweep[1], sweep[2],
    )

    private val cube = outline(floatArrayOf(0f, 0f, 1f)) { x, y -> floatArrayOf(x, y, 0f) }.copy(
        side = outline(floatArrayOf(1f, 0f, 0f)) { z, y -> floatArrayOf(0f, y, z - 300f) },
        top = outline(floatArrayOf(0f, 1f, 0f)) { x, z -> floatArrayOf(x, 0f, z - 300f) },
    )

    @Test
    fun `an Ink stroke across a crease of a hull is cut per view image, bridged at the crease`() {
        val plane = ActivePlaneMapping.of(surfaceState(cube, SceneCamera(yaw = 0.7f, pitch = 0.2f)))
        assertTrue(plane.onHull)
        // Walk a row of touches until it has left one view for another.
        val row = (0..200).map { Offset(300f + it * 3f, 400f) }
        val views = row.map { plane.toViewFlatByFootprint(it, 0f)?.view }
        val at = (1 until views.size).first { views[it] != null && views[it - 1] != null && views[it] != views[it - 1] }
        val path = listOf(row[at - 1], row[at], row[at + 1])
        val segments = plane.inkSegments(path, footprint = 0f)
        assertEquals(2, segments.size)
        assertEquals(views[at - 1], segments[0].view)
        assertEquals(views[at], segments[1].view)
        assertNotNull(segments[0].view)
        // The first image runs on to the crease (one sample more than its touches) and the next starts at it.
        assertTrue(segments[0].samples.size >= 2 && segments[1].samples.size >= 2)
        assertEquals(0, segments[0].samples.last().first)
        assertEquals(1, segments[1].samples.first().first)
    }

    @Test
    fun `an Ink sample that only just misses a hull is kept by its footprint`() {
        val plane = ActivePlaneMapping.of(surfaceState(cube, SceneCamera(yaw = 0.7f, pitch = 0.2f)))
        val inside = Offset(500f, 400f)
        assertNotNull(plane.toViewFlatByFootprint(inside, 0f))
        // Walk left from the inside until the ray misses, then stand just off the edge.
        var x = 500f
        while (plane.toViewFlatByFootprint(Offset(x, 400f), 0f) != null) x -= 2f
        val miss = Offset(x - 4f, 400f)
        fun kept(footprint: Float) = plane.inkSegments(listOf(inside, miss), footprint).sumOf { it.samples.size }
        assertEquals(1, kept(0f))
        assertTrue(kept(40f) >= 2)
    }
}
