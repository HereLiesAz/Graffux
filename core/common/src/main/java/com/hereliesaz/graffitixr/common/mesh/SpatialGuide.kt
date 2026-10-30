@file:Suppress("MagicNumber", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "ReturnCount")

package com.hereliesaz.graffitixr.common.mesh

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Drawing guide geometry. VIEW_PLANE preserves the original camera-facing sketch workflow. */
@Serializable
enum class SpatialGuideKind { VIEW_PLANE, PLANE, CYLINDER, SPHERE }

/**
 * Editable guide transform and dimensions, in world units and radians.
 *
 * [center] is absolute for fixed guides and an offset from camera.target for VIEW_PLANE.
 * [yaw] and [pitch] rotate local XYZ for PLANE and CYLINDER. SPHERE has no preferred orientation.
 * [radius] is the sphere/cylinder radius, and the fixed plane's half-width/half-height.
 * [height] bounds the cylinder along its local Y axis; side drawing intentionally excludes caps.
 * [depth] moves the camera-facing guide in the viewing direction, while drawn points never move.
 * All properties have defaults so version-5 and earlier projects open without fabricated geometry.
 */
@Serializable
data class SpatialGuide(
    val kind: SpatialGuideKind = SpatialGuideKind.VIEW_PLANE,
    val center: SpatialPoint = SpatialPoint(0f, 0f, 0f),
    val radius: Float = 1f,
    val height: Float = 2f,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val depth: Float = 0f,
    val visible: Boolean = true,
)

/**
 * Pure guide-to-world mathematics. Both the stylus hit and wireframe use the same transform.
 * No pixel/depth snapshots are written to [SpatialStroke]: every sample becomes an XYZ point.
 */
object SpatialGuideSurface {
    private const val EPS = 1e-6f
    private const val WIRE_STEPS = 48
    private const val GRID_DIVISIONS = 8

    /**
     * Nearest positive intersection with the visible surface. Missing a bounded plane/cylinder
     * returns null; the caller must end the current curve rather than bridging across the hole.
     * A sphere uses its front hit (or inside exit hit), a cylinder its curved side, not end caps.
     */
    fun intersect(
        guide: SpatialGuide,
        camera: OrbitCamera,
        screenX: Float,
        screenY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        pressure: Float = 1f,
    ): SpatialPoint? {
        if (viewportWidth <= 0f || viewportHeight <= 0f || !valid(guide)) return null
        val ray = MeshPicker.unproject(
            screenX, screenY, viewportWidth, viewportHeight,
            camera.viewMatrix(), camera.projectionMatrix(viewportWidth / viewportHeight),
        ) ?: return null
        val basis = basis(guide, camera)
        val localOrigin = FloatArray(3) { dot(basis.axes[it], subtract(ray.origin, basis.origin)) }
        val localDir = FloatArray(3) { dot(basis.axes[it], ray.direction) }
        val hit = when (guide.kind) {
            SpatialGuideKind.VIEW_PLANE -> planeHit(localOrigin, localDir, Float.POSITIVE_INFINITY)
            SpatialGuideKind.PLANE -> planeHit(localOrigin, localDir, guide.radius)
            SpatialGuideKind.SPHERE -> sphereHit(localOrigin, localDir, guide.radius)
            SpatialGuideKind.CYLINDER -> cylinderHit(localOrigin, localDir, guide.radius, guide.height)
        } ?: return null
        val world = FloatArray(3) { i -> ray.origin[i] + ray.direction[i] * hit }
        if (world.any { !it.isFinite() }) return null
        return SpatialPoint(world[0], world[1], world[2], pressure.coerceIn(0.05f, 1f))
    }

    /**
     * World-space wireframe segments for a translucent 3D guide overlay. A fixed guide stays
     * anchored as the camera moves. Only VIEW_PLANE intentionally rotates with the camera.
     */
    fun wireframe(guide: SpatialGuide, camera: OrbitCamera): List<Pair<SpatialPoint, SpatialPoint>> {
        if (!valid(guide) || !guide.visible) return emptyList()
        val frame = basis(guide, camera)
        fun point(x: Float, y: Float, z: Float): SpatialPoint {
            val p = FloatArray(3) { i ->
                frame.origin[i] + frame.axes[0][i] * x + frame.axes[1][i] * y + frame.axes[2][i] * z
            }
            return SpatialPoint(p[0], p[1], p[2])
        }
        val lines = mutableListOf<Pair<SpatialPoint, SpatialPoint>>()
        fun segment(a: SpatialPoint, b: SpatialPoint) { lines.add(a to b) }
        when (guide.kind) {
            SpatialGuideKind.VIEW_PLANE, SpatialGuideKind.PLANE -> {
                val extent = if (guide.kind == SpatialGuideKind.VIEW_PLANE) guide.radius * 1.25f else guide.radius
                for (i in -GRID_DIVISIONS..GRID_DIVISIONS) {
                    val k = extent * i / GRID_DIVISIONS
                    segment(point(k, -extent, 0f), point(k, extent, 0f))
                    segment(point(-extent, k, 0f), point(extent, k, 0f))
                }
            }
            SpatialGuideKind.SPHERE -> {
                for (latitude in -3..3) {
                    val phi = latitude * PI.toFloat() / 8f
                    val y = guide.radius * sin(phi)
                    val circle = guide.radius * cos(phi)
                    for (i in 0 until WIRE_STEPS) {
                        val a = 2f * PI.toFloat() * i / WIRE_STEPS
                        val b = 2f * PI.toFloat() * (i + 1) / WIRE_STEPS
                        segment(point(circle * cos(a), y, circle * sin(a)),
                            point(circle * cos(b), y, circle * sin(b)))
                    }
                }
                for (longitude in 0 until 8) {
                    val theta = 2f * PI.toFloat() * longitude / 8
                    for (i in 0 until WIRE_STEPS) {
                        val a = -PI.toFloat() / 2f + PI.toFloat() * i / WIRE_STEPS
                        val b = -PI.toFloat() / 2f + PI.toFloat() * (i + 1) / WIRE_STEPS
                        segment(point(guide.radius * cos(a) * cos(theta), guide.radius * sin(a),
                            guide.radius * cos(a) * sin(theta)),
                            point(guide.radius * cos(b) * cos(theta), guide.radius * sin(b),
                                guide.radius * cos(b) * sin(theta)))
                    }
                }
            }
            SpatialGuideKind.CYLINDER -> {
                for (level in 0..GRID_DIVISIONS) {
                    val y = (level.toFloat() / GRID_DIVISIONS - 0.5f) * guide.height
                    for (i in 0 until WIRE_STEPS) {
                        val a = 2f * PI.toFloat() * i / WIRE_STEPS
                        val b = 2f * PI.toFloat() * (i + 1) / WIRE_STEPS
                        segment(point(guide.radius * cos(a), y, guide.radius * sin(a)),
                            point(guide.radius * cos(b), y, guide.radius * sin(b)))
                    }
                }
                for (longitude in 0 until 12) {
                    val angle = 2f * PI.toFloat() * longitude / 12
                    val x = guide.radius * cos(angle)
                    val z = guide.radius * sin(angle)
                    segment(point(x, -guide.height / 2f, z), point(x, guide.height / 2f, z))
                }
            }
        }
        return lines
    }

    private data class Basis(val origin: FloatArray, val axes: Array<FloatArray>)

    private fun basis(guide: SpatialGuide, camera: OrbitCamera): Basis {
        if (guide.kind == SpatialGuideKind.VIEW_PLANE) {
            val eye = camera.eye()
            val forward = normalized(subtract(camera.target, eye))
            val right = normalized(cross(forward, floatArrayOf(0f, 1f, 0f)))
            val up = cross(right, forward)
            val center = floatArrayOf(
                camera.target[0] + guide.center.x + forward[0] * guide.depth,
                camera.target[1] + guide.center.y + forward[1] * guide.depth,
                camera.target[2] + guide.center.z + forward[2] * guide.depth,
            )
            return Basis(center, arrayOf(right, up, forward))
        }
        val cy = cos(guide.yaw); val sy = sin(guide.yaw)
        val cp = cos(guide.pitch); val sp = sin(guide.pitch)
        return Basis(
            floatArrayOf(guide.center.x, guide.center.y, guide.center.z),
            arrayOf(
                floatArrayOf(cy, 0f, -sy),
                floatArrayOf(sy * sp, cp, cy * sp),
                floatArrayOf(sy * cp, -sp, cy * cp),
            ),
        )
    }

    private fun planeHit(o: FloatArray, d: FloatArray, extent: Float): Float? {
        if (abs(d[2]) < EPS) return null
        val t = -o[2] / d[2]
        if (t <= EPS) return null
        if (abs(o[0] + t * d[0]) > extent || abs(o[1] + t * d[1]) > extent) return null
        return t
    }

    private fun sphereHit(o: FloatArray, d: FloatArray, radius: Float): Float? {
        val b = dot(o, d)
        val c = dot(o, o) - radius * radius
        val discriminant = b * b - c
        if (discriminant < 0f) return null
        val root = sqrt(discriminant)
        return listOf(-b - root, -b + root).firstOrNull { it > EPS }
    }

    private fun cylinderHit(o: FloatArray, d: FloatArray, radius: Float, height: Float): Float? {
        val a = d[0] * d[0] + d[2] * d[2]
        if (a < EPS) return null
        val b = o[0] * d[0] + o[2] * d[2]
        val c = o[0] * o[0] + o[2] * o[2] - radius * radius
        val discriminant = b * b - a * c
        if (discriminant < 0f) return null
        val root = sqrt(discriminant)
        return listOf((-b - root) / a, (-b + root) / a)
            .firstOrNull { it > EPS && abs(o[1] + it * d[1]) <= height * 0.5f }
    }

    private fun valid(g: SpatialGuide): Boolean = g.radius.isFinite() && g.radius > EPS &&
        g.height.isFinite() && g.height > EPS && g.yaw.isFinite() && g.pitch.isFinite() &&
        g.depth.isFinite() && g.center.x.isFinite() && g.center.y.isFinite() && g.center.z.isFinite()

    private fun subtract(a: FloatArray, b: FloatArray): FloatArray =
        FloatArray(3) { a[it] - b[it] }

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )

    private fun normalized(a: FloatArray): FloatArray {
        val length = sqrt(dot(a, a))
        return if (length < EPS) floatArrayOf(0f, 0f, -1f) else FloatArray(3) { a[it] / length }
    }
}
