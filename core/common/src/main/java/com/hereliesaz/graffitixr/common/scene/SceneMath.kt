@file:Suppress("MagicNumber", "TooManyFunctions", "LongParameterList", "ReturnCount", "MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A layer's plane placement: its 2D transform plus depth and X/Y tilt. Angles in degrees, as the
 * layer stores them; [offsetX]/[offsetY]/[positionZ] in scene pixels.
 */
data class PlanePose(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val positionZ: Float = 0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val scale: Float = 1f,
) {
    /** Moved in 3D: anything beyond the flat 2D transform every layer always had. */
    val isPlacedIn3D: Boolean get() = positionZ != 0f || rotationX != 0f || rotationY != 0f
}

/**
 * An affine map of 3D points, row-major 3x4: `p' = M p + t`, where `m[r*4+c]`, column 3 is t.
 */
class Affine3(val m: FloatArray) {
    init { require(m.size == 12) }

    fun apply(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        m[0] * x + m[1] * y + m[2] * z + m[3],
        m[4] * x + m[5] * y + m[6] * z + m[7],
        m[8] * x + m[9] * y + m[10] * z + m[11],
    )

    fun applyVector(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        m[0] * x + m[1] * y + m[2] * z,
        m[4] * x + m[5] * y + m[6] * z,
        m[8] * x + m[9] * y + m[10] * z,
    )

    fun column(c: Int): FloatArray = floatArrayOf(m[c], m[4 + c], m[8 + c])

    /** `this * other`: [other] applied first. */
    operator fun times(other: Affine3): Affine3 {
        val a = m
        val b = other.m
        val r = FloatArray(12)
        for (row in 0..2) {
            for (col in 0..3) {
                var v = a[row * 4] * b[col] + a[row * 4 + 1] * b[4 + col] + a[row * 4 + 2] * b[8 + col]
                if (col == 3) v += a[row * 4 + 3]
                r[row * 4 + col] = v
            }
        }
        return Affine3(r)
    }

    /** The inverse map, or null when the linear part is singular (a zero scale). */
    fun inverse(): Affine3? {
        val a = m
        val det = a[0] * (a[5] * a[10] - a[6] * a[9]) - a[1] * (a[4] * a[10] - a[6] * a[8]) +
            a[2] * (a[4] * a[9] - a[5] * a[8])
        if (abs(det) < 1e-12f || !det.isFinite()) return null
        val i = FloatArray(12)
        i[0] = (a[5] * a[10] - a[6] * a[9]) / det
        i[1] = (a[2] * a[9] - a[1] * a[10]) / det
        i[2] = (a[1] * a[6] - a[2] * a[5]) / det
        i[4] = (a[6] * a[8] - a[4] * a[10]) / det
        i[5] = (a[0] * a[10] - a[2] * a[8]) / det
        i[6] = (a[2] * a[4] - a[0] * a[6]) / det
        i[8] = (a[4] * a[9] - a[5] * a[8]) / det
        i[9] = (a[1] * a[8] - a[0] * a[9]) / det
        i[10] = (a[0] * a[5] - a[1] * a[4]) / det
        for (r in 0..2) {
            i[r * 4 + 3] = -(i[r * 4] * a[3] + i[r * 4 + 1] * a[7] + i[r * 4 + 2] * a[11])
        }
        return Affine3(i)
    }

    companion object {
        val IDENTITY = Affine3(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f))
    }
}

/** The scene camera resolved for a canvas of [width] x [height]: eye, axes and focal length. */
class CameraFrame(
    val width: Float,
    val height: Float,
    val focal: Float,
    val eye: FloatArray,
    val target: FloatArray,
    val right: FloatArray,
    val down: FloatArray,
    val forward: FloatArray,
) {
    /** Camera-space coordinates (x right, y down, z depth) of scene point [p]. */
    fun toCamera(p: FloatArray): FloatArray {
        val q = floatArrayOf(p[0] - eye[0], p[1] - eye[1], p[2] - eye[2])
        return floatArrayOf(SceneMath.dot(right, q), SceneMath.dot(down, q), SceneMath.dot(forward, q))
    }

    /** Canvas pixel of scene point [p], or null when it is not in front of the near plane. */
    fun project(p: FloatArray): Pair<Float, Float>? {
        val c = toCamera(p)
        if (c[2] <= SceneMath.nearPlane(focal)) return null
        return (width / 2f + focal * c[0] / c[2]) to (height / 2f + focal * c[1] / c[2])
    }

    /** Unit direction from the eye through canvas pixel ([x], [y]). */
    fun rayDirection(x: Float, y: Float): FloatArray {
        val u = (x - width / 2f) / focal
        val v = (y - height / 2f) / focal
        return SceneMath.normalize(FloatArray(3) { right[it] * u + down[it] * v + forward[it] })
    }
}

/**
 * The 3D space's maths: layer planes, the camera, plane-to-canvas homographies and the ray-plane
 * mapping that lets every 2D tool paint on a tilted layer. Pure Kotlin; see spec/3d-space.md.
 */
object SceneMath {
    /** Spatial-ink units per scene pixel inverse: one stroke unit is `focal / 3` pixels. */
    const val STROKE_UNITS_AT_DEFAULT_DISTANCE = 3f

    fun focalLength(height: Float): Float =
        (height / 2f) / tan(SceneCamera.FOV_Y_DEGREES * PI.toFloat() / 360f)

    fun nearPlane(focal: Float): Float = focal * 0.02f

    fun farPlane(focal: Float): Float = focal * 200f

    fun frame(camera: SceneCamera, width: Float, height: Float): CameraFrame {
        val focal = focalLength(height)
        val cp = cos(camera.pitch)
        val forward = floatArrayOf(cp * sin(camera.yaw), sin(camera.pitch), cp * cos(camera.yaw))
        val right = normalize(floatArrayOf(forward[2], 0f, -forward[0]))
        val down = cross(forward, right)
        val target = floatArrayOf(width / 2f + camera.panX, height / 2f + camera.panY, camera.panZ)
        val distance = focal * camera.distanceScale
        val eye = FloatArray(3) { target[it] - forward[it] * distance }
        return CameraFrame(width, height, focal, eye, target, right, down, forward)
    }

    /** Rz * Ry * Rx, degrees, in scene axes (x right, y down, z away). */
    fun rotation(rxDeg: Float, ryDeg: Float, rzDeg: Float): FloatArray {
        val x = rxDeg * PI.toFloat() / 180f
        val y = ryDeg * PI.toFloat() / 180f
        val z = rzDeg * PI.toFloat() / 180f
        val rx = floatArrayOf(1f, 0f, 0f, 0f, cos(x), -sin(x), 0f, sin(x), cos(x))
        val ry = floatArrayOf(cos(y), 0f, sin(y), 0f, 1f, 0f, -sin(y), 0f, cos(y))
        val rz = floatArrayOf(cos(z), -sin(z), 0f, sin(z), cos(z), 0f, 0f, 0f, 1f)
        return mul3(rz, mul3(ry, rx))
    }

    /**
     * Box-local to scene: `P = C + O + R * scale * (L - C)`, C the canvas centre. A pose that is not
     * placed in 3D gives exactly the layer's 2D transform.
     */
    fun model(pose: PlanePose, width: Float, height: Float): Affine3 {
        val r = rotation(pose.rotationX, pose.rotationY, pose.rotationZ)
        val s = pose.scale
        val cx = width / 2f
        val cy = height / 2f
        val m = FloatArray(12)
        for (row in 0..2) {
            for (col in 0..2) m[row * 4 + col] = r[row * 3 + col] * s
        }
        val centre = floatArrayOf(cx, cy, 0f)
        val offset = floatArrayOf(pose.offsetX, pose.offsetY, pose.positionZ)
        for (row in 0..2) {
            m[row * 4 + 3] = centre[row] + offset[row] - (m[row * 4] * cx + m[row * 4 + 1] * cy)
        }
        return Affine3(m)
    }

    /** A chain of poses, root (outermost group) first: the leaf's box-local to scene. */
    fun chainModel(chain: List<PlanePose>, width: Float, height: Float): Affine3 =
        chain.fold(Affine3.IDENTITY) { acc, pose -> acc * model(pose, width, height) }

    /**
     * Stroke units into the layer's box-local 3D coordinates: centred on the canvas, one unit =
     * focal / 3 pixels, y flipped to point down and z to point away.
     */
    fun strokeToLocal(width: Float, height: Float): Affine3 {
        val k = focalLength(height) / STROKE_UNITS_AT_DEFAULT_DISTANCE
        return Affine3(floatArrayOf(k, 0f, 0f, width / 2f, 0f, -k, 0f, height / 2f, 0f, 0f, -k, 0f))
    }

    /**
     * Row-major 3x3 homography from the plane z = 0 of [model]'s local space to canvas pixels:
     * `(u, v, 1) -> (X, Y, W)`, pixel = (X/W, Y/W). Row 2 is the camera depth.
     */
    fun homography(model: Affine3, cam: CameraFrame): FloatArray {
        val a = cam.toCameraVector(model.column(0))
        val b = cam.toCameraVector(model.column(1))
        val t = cam.toCamera(model.column(3))
        val f = cam.focal
        val cx = cam.width / 2f
        val cy = cam.height / 2f
        return floatArrayOf(
            f * a[0] + cx * a[2], f * b[0] + cx * b[2], f * t[0] + cx * t[2],
            f * a[1] + cy * a[2], f * b[1] + cy * b[2], f * t[1] + cy * t[2],
            a[2], b[2], t[2],
        )
    }

    private fun CameraFrame.toCameraVector(v: FloatArray): FloatArray =
        floatArrayOf(dot(right, v), dot(down, v), dot(forward, v))

    fun applyHomography(h: FloatArray, u: Float, v: Float): Pair<Float, Float>? {
        val w = h[6] * u + h[7] * v + h[8]
        if (abs(w) < 1e-9f) return null
        return (h[0] * u + h[1] * v + h[2]) / w to (h[3] * u + h[4] * v + h[5]) / w
    }

    fun invert3(h: FloatArray): FloatArray? {
        val det = h[0] * (h[4] * h[8] - h[5] * h[7]) - h[1] * (h[3] * h[8] - h[5] * h[6]) +
            h[2] * (h[3] * h[7] - h[4] * h[6])
        if (abs(det) < 1e-18f || !det.isFinite()) return null
        return floatArrayOf(
            (h[4] * h[8] - h[5] * h[7]) / det, (h[2] * h[7] - h[1] * h[8]) / det, (h[1] * h[5] - h[2] * h[4]) / det,
            (h[5] * h[6] - h[3] * h[8]) / det, (h[0] * h[8] - h[2] * h[6]) / det, (h[2] * h[3] - h[0] * h[5]) / det,
            (h[3] * h[7] - h[4] * h[6]) / det, (h[1] * h[6] - h[0] * h[7]) / det, (h[0] * h[4] - h[1] * h[3]) / det,
        )
    }

    fun mul3(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    /**
     * The homography a node draws with inside its parent's: `parent^-1 * self`. Composing them
     * down the tree gives each node exactly its own plane-to-canvas map.
     */
    fun relativeHomography(parent: FloatArray?, self: FloatArray): FloatArray? =
        if (parent == null) self else invert3(parent)?.let { mul3(it, self) }

    /**
     * The polygon (box-local, z = 0) of the plane in front of the near plane, intersected with a
     * square of half-size [extent] about the canvas centre. Null when the whole square is visible
     * (no clip needed); empty when none of it is.
     */
    fun visiblePolygon(h: FloatArray, cam: CameraFrame, extent: Float): List<Pair<Float, Float>>? {
        val near = nearPlane(cam.focal)
        val cx = cam.width / 2f
        val cy = cam.height / 2f
        val square = listOf(cx - extent to cy - extent, cx + extent to cy - extent, cx + extent to cy + extent, cx - extent to cy + extent)
        fun depth(p: Pair<Float, Float>) = h[6] * p.first + h[7] * p.second + h[8] - near
        if (square.all { depth(it) > 0f }) return null
        val out = mutableListOf<Pair<Float, Float>>()
        for (i in square.indices) {
            val a = square[i]
            val b = square[(i + 1) % square.size]
            val da = depth(a)
            val db = depth(b)
            if (da > 0f) out += a
            if ((da > 0f) != (db > 0f)) {
                val t = da / (da - db)
                out += (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
            }
        }
        return out
    }

    /**
     * Ray from the eye through canvas point ([x], [y]) against the plane z = 0 of [model]'s local
     * space: the hit in local (u, v), or null when the ray is parallel to the plane, the plane is
     * behind the eye, or the hit is nearer than the near plane (the eye in an edge-on plane).
     */
    fun canvasToLocal(x: Float, y: Float, model: Affine3, cam: CameraFrame): Pair<Float, Float>? {
        val dir = cam.rayDirection(x, y)
        val a = model.column(0)
        val b = model.column(1)
        val t = model.column(3)
        val n = cross(a, b)
        val denom = dot(n, dir)
        if (abs(denom) < 1e-9f * (sqrt(dot(n, n)).coerceAtLeast(1e-9f))) return null
        val s = dot(n, FloatArray(3) { t[it] - cam.eye[it] }) / denom
        // A hit nearer than the near plane is no hit: it is the eye itself lying in the plane (an
        // edge-on plane, where rounding leaves a tiny s), and nothing that close is ever drawn.
        if (s <= nearPlane(cam.focal) || !s.isFinite()) return null
        val q = FloatArray(3) { cam.eye[it] + dir[it] * s - t[it] }
        // a and b are orthogonal (a rotation times a uniform scale), so the coordinates separate.
        val aa = dot(a, a)
        val bb = dot(b, b)
        if (aa < 1e-12f || bb < 1e-12f) return null
        return dot(a, q) / aa to dot(b, q) / bb
    }

    /**
     * The point the existing 2D pipeline expects for a touch at canvas point ([x], [y]) on the leaf
     * of [chain]: the ray's hit on the leaf's plane, pushed back through the leaf's own flat 2D
     * transform (offset, Z rotation, scale) — the input `mapScreenToBitmap` inverts. Null on a miss.
     */
    fun worldToLayerFlat(
        x: Float,
        y: Float,
        chain: List<PlanePose>,
        camera: SceneCamera,
        width: Float,
        height: Float,
    ): Pair<Float, Float>? {
        if (chain.isEmpty() || width <= 0f || height <= 0f) return null
        val cam = frame(camera, width, height)
        val local = canvasToLocal(x, y, chainModel(chain, width, height), cam) ?: return null
        val leaf = chain.last()
        val flat = model(leaf.copy(positionZ = 0f, rotationX = 0f, rotationY = 0f), width, height)
        val p = flat.apply(local.first, local.second, 0f)
        return p[0] to p[1]
    }

    /**
     * The inverse of [worldToLayerFlat]: where a flat-space point of [chain]'s leaf (a selection
     * vertex, say) shows on the canvas. Null when it is behind the eye.
     */
    fun layerFlatToWorld(
        x: Float,
        y: Float,
        chain: List<PlanePose>,
        camera: SceneCamera,
        width: Float,
        height: Float,
    ): Pair<Float, Float>? {
        if (chain.isEmpty() || width <= 0f || height <= 0f) return null
        val leaf = chain.last()
        val flat = model(leaf.copy(positionZ = 0f, rotationX = 0f, rotationY = 0f), width, height).inverse() ?: return null
        val local = flat.apply(x, y, 0f)
        val cam = frame(camera, width, height)
        val h = homography(chainModel(chain, width, height), cam)
        if (h[6] * local[0] + h[7] * local[1] + h[8] <= nearPlane(cam.focal)) return null
        return applyHomography(h, local[0], local[1])
    }

    /**
     * View-plane adoption (spec/3d-space.md, "Adopting the view plane"): the pose that puts a
     * layer's plane facing the camera through its orbit target. The layer's box centre lands on the
     * target, its x and y run along the view's right and down, and its scale is the camera's
     * distance factor, so its pixels show at the same size on screen as a flat layer's at the
     * default view. [parentChain] is the layer's enclosing groups, outermost first; the pose is in
     * their space. Null when that space is degenerate.
     */
    fun viewPlanePose(
        camera: SceneCamera,
        width: Float,
        height: Float,
        parentChain: List<PlanePose> = emptyList(),
        pivot: FloatArray? = null,
    ): PlanePose? {
        val cam = frame(camera, width, height)
        // Through the camera's target by default; through [pivot] (the orbit point,
        // OrbitPivot) when given, scaled to that point's depth so pixels still show 1:1 there.
        val through = pivot ?: cam.target
        val s = if (pivot == null) camera.distanceScale else cam.toCamera(pivot)[2] / cam.focal
        if (!s.isFinite() || s <= 0f) return null
        val cx = width / 2f
        val cy = height / 2f
        val m = FloatArray(12)
        val axes = arrayOf(cam.right, cam.down, cam.forward)
        for (row in 0..2) for (col in 0..2) m[row * 4 + col] = axes[col][row] * s
        for (row in 0..2) m[row * 4 + 3] = through[row] - (m[row * 4] * cx + m[row * 4 + 1] * cy)
        val world = Affine3(m)
        val local = (chainModel(parentChain, width, height).inverse() ?: return null) * world
        return poseOf(local, width, height)
    }

    /**
     * The camera after turning about the scene point [pivot] by [deltaYaw] and [deltaPitch]
     * radians (spec/3d-space.md, "The orbit point"). [SceneCamera.orbit] turns about the camera's
     * own target; this keeps [pivot] at the same place on screen and the same distance from the eye,
     * moving the target (the pan) to suit, so the scene revolves about the pivot and not about
     * whatever the target happens to be.
     */
    fun orbitAbout(
        camera: SceneCamera,
        pivot: FloatArray,
        deltaYaw: Float,
        deltaPitch: Float,
        width: Float,
        height: Float,
    ): SceneCamera {
        val before = frame(camera, width, height)
        val c = before.toCamera(pivot)
        val turned = camera.orbit(deltaYaw, deltaPitch)
        val after = frame(turned, width, height)
        val distance = after.focal * turned.distanceScale
        // eye' puts the pivot at camera coordinates c again; the target is distance ahead of it.
        val eye = FloatArray(3) {
            pivot[it] - (after.right[it] * c[0] + after.down[it] * c[1] + after.forward[it] * c[2])
        }
        val target = FloatArray(3) { eye[it] + after.forward[it] * distance }
        return turned.copy(panX = target[0] - width / 2f, panY = target[1] - height / 2f, panZ = target[2])
    }

    /**
     * The plane [pose] turned by [deltaDeg] about [axis] (as the slider's Euler angle on that axis
     * moves) so that its box centre revolves about [pivot], a point in the same space as the pose
     * (the layer's parent space). The pose's rotation is exactly what the slider reads; only its
     * offset and depth change with it, so the layer's distance from [pivot] never changes.
     */
    fun rotatePoseAbout(
        pose: PlanePose,
        axis: com.hereliesaz.graffitixr.common.model.RotationAxis,
        deltaDeg: Float,
        pivot: FloatArray,
        width: Float,
        height: Float,
    ): PlanePose {
        val turned = when (axis) {
            com.hereliesaz.graffitixr.common.model.RotationAxis.X -> pose.copy(rotationX = pose.rotationX + deltaDeg)
            com.hereliesaz.graffitixr.common.model.RotationAxis.Y -> pose.copy(rotationY = pose.rotationY + deltaDeg)
            com.hereliesaz.graffitixr.common.model.RotationAxis.Z -> pose.copy(rotationZ = pose.rotationZ + deltaDeg)
        }
        val old = rotation(pose.rotationX, pose.rotationY, pose.rotationZ)
        val new = rotation(turned.rotationX, turned.rotationY, turned.rotationZ)
        // Relative turn R = new * old^T (old is orthonormal), applied to the centre about the pivot.
        val oldT = floatArrayOf(old[0], old[3], old[6], old[1], old[4], old[7], old[2], old[5], old[8])
        val r = mul3(new, oldT)
        val d = floatArrayOf(
            width / 2f + pose.offsetX - pivot[0], height / 2f + pose.offsetY - pivot[1], pose.positionZ - pivot[2],
        )
        val nx = pivot[0] + r[0] * d[0] + r[1] * d[1] + r[2] * d[2]
        val ny = pivot[1] + r[3] * d[0] + r[4] * d[1] + r[5] * d[2]
        val nz = pivot[2] + r[6] * d[0] + r[7] * d[1] + r[8] * d[2]
        return turned.copy(offsetX = nx - width / 2f, offsetY = ny - height / 2f, positionZ = nz)
    }

    /**
     * The pose whose [model] is [affine] (a rotation times a uniform scale, plus a translation):
     * the inverse of [model]. Angles in degrees, Rz * Ry * Rx.
     */
    fun poseOf(affine: Affine3, width: Float, height: Float): PlanePose? {
        val c0 = affine.column(0)
        val scale = sqrt(dot(c0, c0))
        if (scale < 1e-9f || !scale.isFinite()) return null
        val r = FloatArray(9) { i -> affine.m[(i / 3) * 4 + i % 3] / scale }
        val sy = (-r[6]).coerceIn(-1f, 1f)
        val ry = kotlin.math.asin(sy)
        val rx: Float
        val rz: Float
        if (abs(kotlin.math.cos(ry)) > 1e-5f) {
            rx = kotlin.math.atan2(r[7], r[8])
            rz = kotlin.math.atan2(r[3], r[0])
        } else {
            // Gimbal: Y at +-90 degrees; fold the Z turn into X.
            rx = kotlin.math.atan2(-r[5], r[4])
            rz = 0f
        }
        val cx = width / 2f
        val cy = height / 2f
        val t = affine.column(3)
        val mc = affine.applyVector(cx, cy, 0f)
        val deg = 180f / PI.toFloat()
        return PlanePose(
            offsetX = t[0] + mc[0] - cx,
            offsetY = t[1] + mc[1] - cy,
            positionZ = t[2] + mc[2],
            rotationX = rx * deg,
            rotationY = ry * deg,
            rotationZ = rz * deg,
            scale = scale,
        )
    }

    /** Camera depth of the ray-plane hit for ([x], [y]) on [chain]'s leaf plane; null on a miss. */
    fun hitDepth(
        x: Float,
        y: Float,
        chain: List<PlanePose>,
        camera: SceneCamera,
        width: Float,
        height: Float,
    ): Float? {
        if (chain.isEmpty() || width <= 0f || height <= 0f) return null
        val cam = frame(camera, width, height)
        val model = chainModel(chain, width, height)
        val local = canvasToLocal(x, y, model, cam) ?: return null
        return cam.toCamera(model.apply(local.first, local.second, 0f))[2]
    }

    /**
     * Column-major 4x4 clip-space matrix for GL: [model] (box-local 3D, e.g. [strokeToLocal] then a
     * layer's chain) through the camera into clip space for a [cam]-sized viewport.
     */
    fun clipMatrix(model: Affine3, cam: CameraFrame): FloatArray {
        val near = nearPlane(cam.focal)
        val far = farPlane(cam.focal)
        val e = cam.eye
        val view = Affine3(floatArrayOf(
            cam.right[0], cam.right[1], cam.right[2], -dot(cam.right, e),
            cam.down[0], cam.down[1], cam.down[2], -dot(cam.down, e),
            cam.forward[0], cam.forward[1], cam.forward[2], -dot(cam.forward, e),
        )) * model
        val v = view.m
        val sx = 2f * cam.focal / cam.width
        val sy = -2f * cam.focal / cam.height
        val za = (far + near) / (far - near)
        val zb = -2f * far * near / (far - near)
        // Row-major rows of the 4x4, then written column-major.
        val rows = arrayOf(
            FloatArray(4) { sx * v[it] },
            FloatArray(4) { sy * v[4 + it] },
            FloatArray(4) { za * v[8 + it] + if (it == 3) zb else 0f },
            FloatArray(4) { v[8 + it] },
        )
        return FloatArray(16) { i -> rows[i % 4][i / 4] }
    }

    fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )

    fun normalize(v: FloatArray): FloatArray {
        val l = sqrt(dot(v, v))
        return if (l < 1e-12f) floatArrayOf(0f, 0f, 1f) else FloatArray(3) { v[it] / l }
    }
}
