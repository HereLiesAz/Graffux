@file:Suppress(
    "LongParameterList", "TooManyFunctions", "ComplexCondition", "ReturnCount", "MagicNumber",
)
// Coordinate transforms use fixed NDC and vector dimensions; early exits guard invalid rays.

package com.hereliesaz.graffitixr.common.mesh

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Canonical, editable spatial ink. Coordinates and widths are in world units; they are never
 * projected screen points or a flattened paint texture. A curve survives every camera rotation.
 */
@Serializable
data class SpatialPoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val pressure: Float = 1f,
)

@Serializable
data class SpatialStroke(
    val id: String,
    val points: List<SpatialPoint>,
    val colorArgb: Int,
    val width: Float,
)

/**
 * Screen/scene correspondence for a camera-facing drawing guide. Every stroke is unprojected onto
 * the guide as it is drawn, after which its coordinates are immutable and independent of the view.
 * [depth] moves the guide along the viewing direction, not the camera or existing artwork.
 */
object SpatialSketchGeometry {
    fun onViewPlane(
        camera: OrbitCamera,
        screenX: Float,
        screenY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        depth: Float = 0f,
        pressure: Float = 1f,
    ): SpatialPoint? {
        if (!depth.isFinite()) return null
        val ray = MeshPicker.unproject(
            screenX, screenY, viewportWidth, viewportHeight,
            camera.viewMatrix(), camera.projectionMatrix(viewportWidth / viewportHeight.coerceAtLeast(1f)),
        ) ?: return null
        val forward = direction(camera)
        val center = FloatArray(3) { camera.target[it] + forward[it] * depth }
        val denom = dot(forward, ray.direction)
        if (abs(denom) < 1e-6f) return null
        val t = dot(forward, FloatArray(3) { center[it] - ray.origin[it] }) / denom
        if (t <= 0f || !t.isFinite()) return null
        return SpatialPoint(
            ray.origin[0] + ray.direction[0] * t,
            ray.origin[1] + ray.direction[1] * t,
            ray.origin[2] + ray.direction[2] * t,
            pressure.coerceIn(0.05f, 1f),
        )
    }

    /** Returns screen pixels, or null for a point behind the eye or outside the near plane. */
    fun project(camera: OrbitCamera, point: SpatialPoint, width: Float, height: Float): Pair<Float, Float>? {
        if (width <= 0f || height <= 0f) return null
        val m = MeshPicker.multiply(camera.projectionMatrix(width / height), camera.viewMatrix())
        val x = point.x; val y = point.y; val z = point.z
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (w <= 1e-6f) return null
        val ndcX = (m[0] * x + m[4] * y + m[8] * z + m[12]) / w
        val ndcY = (m[1] * x + m[5] * y + m[9] * z + m[13]) / w
        val ndcZ = (m[2] * x + m[6] * y + m[10] * z + m[14]) / w
        if (!ndcX.isFinite() || !ndcY.isFinite() || ndcZ < -1f || ndcZ > 1f) return null
        return (ndcX + 1f) * width * 0.5f to (1f - ndcY) * height * 0.5f
    }

    /** A stroke's world-space width represented in pixels at the point's current depth. */
    fun widthPixels(camera: OrbitCamera, point: SpatialPoint, worldWidth: Float, viewportHeight: Float): Float {
        val forward = direction(camera)
        val eye = camera.eye()
        val distance = dot(forward, FloatArray(3) { point.coordinate(it) - eye[it] })
        if (distance <= camera.near) return 0f
        val factor = viewportHeight / (2f * tan(camera.fovYDegrees * PI.toFloat() / 360f))
        return (worldWidth * factor / distance).coerceAtLeast(0f)
    }

    /** The initial 3D width matching the current 2D brush's pixel diameter on the guide. */
    fun widthForBrush(camera: OrbitCamera, brushRadiusPx: Float, viewportHeight: Float): Float {
        if (viewportHeight <= 0f) return 0f
        return 4f * camera.distance * tan(camera.fovYDegrees * PI.toFloat() / 360f) *
            brushRadiusPx.coerceAtLeast(0.5f) / viewportHeight
    }

    /** Lines forming the visible 3D guide, facing the view at the requested depth. */
    fun guide(camera: OrbitCamera, depth: Float, extent: Int = 5): List<Pair<SpatialPoint, SpatialPoint>> {
        val forward = direction(camera)
        val right = normalized(cross(forward, floatArrayOf(0f, 1f, 0f)))
        val up = cross(right, forward)
        val center = FloatArray(3) { camera.target[it] + forward[it] * depth }
        fun point(x: Float, y: Float) = SpatialPoint(
            center[0] + right[0] * x + up[0] * y,
            center[1] + right[1] * x + up[1] * y,
            center[2] + right[2] * x + up[2] * y,
        )
        val grid = mutableListOf<Pair<SpatialPoint, SpatialPoint>>()
        for (step in -extent..extent) {
            val k = step * 0.25f
            val edge = extent * 0.25f
            grid += point(k, -edge) to point(k, edge)
            grid += point(-edge, k) to point(edge, k)
        }
        return grid
    }

    /** Hit-tests the visible curves, not their 3D bounding boxes, for a finger-size eraser. */
    fun nearestStrokeId(
        strokes: List<SpatialStroke>,
        camera: OrbitCamera,
        screenX: Float,
        screenY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        tolerancePx: Float = 24f,
    ): String? {
        var chosen: String? = null
        var best = tolerancePx * tolerancePx
        for (stroke in strokes.asReversed()) {
            val points = stroke.points.map { project(camera, it, viewportWidth, viewportHeight) }
            for (i in points.indices) {
                val a = points[i] ?: continue
                val b = points.getOrNull(i + 1) ?: a
                val dx = b.first - a.first
                val dy = b.second - a.second
                val lenSq = dx * dx + dy * dy
                val fraction = if (lenSq < 1e-8f) 0f else
                    (((screenX - a.first) * dx + (screenY - a.second) * dy) / lenSq).coerceIn(0f, 1f)
                val ex = screenX - (a.first + dx * fraction)
                val ey = screenY - (a.second + dy * fraction)
                val sq = ex * ex + ey * ey
                if (sq < best) { best = sq; chosen = stroke.id }
            }
        }
        return chosen
    }

    private fun SpatialPoint.coordinate(axis: Int): Float = when (axis) {
        0 -> x
        1 -> y
        else -> z
    }

    private fun direction(camera: OrbitCamera): FloatArray {
        val eye = camera.eye()
        return normalized(FloatArray(3) { camera.target[it] - eye[it] })
    }

    private fun normalized(v: FloatArray): FloatArray {
        val length = sqrt(dot(v, v))
        return if (length <= 1e-8f) floatArrayOf(0f, 0f, -1f) else FloatArray(3) { v[it] / length }
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
