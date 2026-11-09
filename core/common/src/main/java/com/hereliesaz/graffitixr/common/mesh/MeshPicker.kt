package com.hereliesaz.graffitixr.common.mesh

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turning a touch into a point on a model's texture — the geometry that makes painting on a 3D
 * surface possible.
 *
 * The chain is: screen point → world ray ([unproject]) → the nearest triangle it hits
 * ([raycast], Möller–Trumbore) → barycentric weights → the UV coordinate at that exact spot. Paint
 * is then applied to the texture at that UV, so it stays on the surface however the model is
 * afterwards rotated, because the texture is what carries it.
 *
 * All pure maths, so the part of surface painting most likely to be subtly wrong is the part that
 * can be tested without a device.
 */
object MeshPicker {

    /** A ray in world space. [direction] is normalised. */
    data class Ray(val origin: FloatArray, val direction: FloatArray) {
        override fun equals(other: Any?): Boolean = other is Ray &&
            origin.contentEquals(other.origin) && direction.contentEquals(other.direction)

        override fun hashCode(): Int = 31 * origin.contentHashCode() + direction.contentHashCode()
    }

    /**
     * Where a ray met the mesh: which [triangleIndex], how far along the ray ([distance]), and the
     * interpolated texture coordinate ([u], [v]) at the hit — which is the thing painting needs.
     */
    data class Hit(
        val triangleIndex: Int,
        val distance: Float,
        val u: Float,
        val v: Float,
        /** Whether the ray met the triangle's outward side ([facesRay]); false = the camera sees its back. */
        val frontFacing: Boolean = true,
    )

    /**
     * Builds the world-space ray under a screen point.
     *
     * Screen Y grows downward and NDC Y grows upward, so the vertical axis is flipped here. Getting
     * that backwards puts paint at the mirrored point vertically, which is the kind of bug that
     * looks almost right and is maddening to chase.
     */
    fun unproject(
        screenX: Float,
        screenY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        view: FloatArray,
        projection: FloatArray,
    ): Ray? {
        if (viewportWidth <= 0f || viewportHeight <= 0f) return null
        val inverse = invert(multiply(projection, view)) ?: return null

        val ndcX = 2f * screenX / viewportWidth - 1f
        val ndcY = 1f - 2f * screenY / viewportHeight

        val near = transformPerspective(inverse, ndcX, ndcY, -1f) ?: return null
        val far = transformPerspective(inverse, ndcX, ndcY, 1f) ?: return null

        val dir = normalize(
            floatArrayOf(far[0] - near[0], far[1] - near[1], far[2] - near[2]),
        ) ?: return null
        return Ray(near, dir)
    }

    /**
     * The nearest triangle [ray] hits, or null if it misses.
     *
     * Möller–Trumbore, which computes the barycentric weights as a by-product of the intersection
     * test — so the UV comes out of the same arithmetic rather than needing a second pass.
     *
     * Every threshold here is **relative**, never an absolute length. [ObjParser] does not normalise
     * scale, and [com.hereliesaz.graffitixr.common.scene.ModelSpace.ray] casts in mesh units, so a model
     * authored in metres, millimetres or micrometres reaches this with triangle edges anywhere from
     * 1e-4 to 1e3. The parallel test compares the ray against the triangle's *unit* normal (a sine, so
     * scale-free), the barycentric slack is a fraction of the triangle, and the arithmetic runs in
     * double so a 1e3 model does not lose its precision to float cancellation.
     *
     * Both faces are tested, not just front-facing ones: a model with inconsistent winding (common
     * in scanned meshes) would otherwise be unpaintable in patches, which reads as the brush
     * randomly not working. [Hit.frontFacing] says which side the ray met, so the paint path can
     * decide for itself whether a back face counts.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun raycast(mesh: Mesh, ray: Ray): Hit? {
        val stride = Mesh.FLOATS_PER_VERTEX
        val v = mesh.vertices
        val ox = ray.origin[0].toDouble()
        val oy = ray.origin[1].toDouble()
        val oz = ray.origin[2].toDouble()
        val dx = ray.direction[0].toDouble()
        val dy = ray.direction[1].toDouble()
        val dz = ray.direction[2].toDouble()
        var bestT = Double.POSITIVE_INFINITY
        var bestTri = -1
        var bestU = 0.0
        var bestV = 0.0

        var i = 0
        while (i + 2 < mesh.indices.size) {
            val a = mesh.indices[i] * stride
            val b = mesh.indices[i + 1] * stride
            val c = mesh.indices[i + 2] * stride

            val ax = v[a].toDouble()
            val ay = v[a + 1].toDouble()
            val az = v[a + 2].toDouble()
            val e1x = v[b] - ax
            val e1y = v[b + 1] - ay
            val e1z = v[b + 2] - az
            val e2x = v[c] - ax
            val e2y = v[c + 1] - ay
            val e2z = v[c + 2] - az

            // n = e1 × e2; |n| is twice the triangle's area.
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            val nLen = sqrt(nx * nx + ny * ny + nz * nz)
            val edgeScale = sqrt(e1x * e1x + e1y * e1y + e1z * e1z) * sqrt(e2x * e2x + e2y * e2y + e2z * e2z)
            // A sliver or a point: sin(angle between the edges) is scale-free, so this is too.
            if (nLen <= edgeScale * DEGENERATE_SINE || nLen == 0.0) { i += 3; continue }

            // det = e1 · (d × e2) = -(d · n). Dividing by |n| gives sin(angle between ray and plane).
            val det = -(dx * nx + dy * ny + dz * nz)
            if (abs(det) <= nLen * PARALLEL_SINE) { i += 3; continue }

            val invDet = 1.0 / det
            val sx = ox - ax
            val sy = oy - ay
            val sz = oz - az
            // h = d × e2
            val hx = dy * e2z - dz * e2y
            val hy = dz * e2x - dx * e2z
            val hz = dx * e2y - dy * e2x
            val bu = invDet * (sx * hx + sy * hy + sz * hz)
            if (bu < -BARY_SLACK || bu > 1.0 + BARY_SLACK) { i += 3; continue }

            // q = s × e1
            val qx = sy * e1z - sz * e1y
            val qy = sz * e1x - sx * e1z
            val qz = sx * e1y - sy * e1x
            val bv = invDet * (dx * qx + dy * qy + dz * qz)
            if (bv < -BARY_SLACK || bu + bv > 1.0 + BARY_SLACK) { i += 3; continue }

            // Behind the eye. t is in ray units (the ray is unit length), so "in front" is simply > 0;
            // there is no length to compare a self-hit epsilon against.
            val t = invDet * (e2x * qx + e2y * qy + e2z * qz)
            if (t <= 0.0 || t >= bestT) { i += 3; continue }

            bestT = t
            bestTri = i / 3
            bestU = bu
            bestV = bv
            i += 3
        }
        if (bestTri < 0) return null

        // Barycentric weights map straight onto the corner UVs: the hit is
        // (1-u-v)·A + u·B + v·C in every attribute, texture coordinates included.
        val a = mesh.indices[bestTri * 3] * stride
        val b = mesh.indices[bestTri * 3 + 1] * stride
        val c = mesh.indices[bestTri * 3 + 2] * stride
        val bu = bestU.coerceIn(0.0, 1.0)
        val bv = bestV.coerceIn(0.0, 1.0 - bu)
        val w = 1.0 - bu - bv
        val uvU = w * v[a + Mesh.UV_OFFSET] + bu * v[b + Mesh.UV_OFFSET] + bv * v[c + Mesh.UV_OFFSET]
        val uvV = w * v[a + Mesh.UV_OFFSET + 1] + bu * v[b + Mesh.UV_OFFSET + 1] + bv * v[c + Mesh.UV_OFFSET + 1]
        return Hit(
            triangleIndex = bestTri,
            distance = bestT.toFloat(),
            u = uvU.toFloat(),
            v = uvV.toFloat(),
            frontFacing = facesRay(mesh, bestTri, ray.direction),
        )
    }

    /**
     * Whether triangle [triangle]'s outward side is the one a ray travelling along [direction] meets,
     * i.e. the camera sees its front. "Outward" is the winding normal, flipped to agree with the
     * vertices' own normals when the mesh has them, so a scan with inconsistent winding still reports
     * the side a viewer would call the outside. With no usable vertex normals it is the winding alone.
     */
    fun facesRay(mesh: Mesh, triangle: Int, direction: FloatArray): Boolean {
        val n = outwardNormal(mesh, triangle) ?: return true
        return n[0] * direction[0] + n[1] * direction[1] + n[2] * direction[2] < 0.0
    }

    /** Triangle [triangle]'s outward (unnormalised) normal, or null for a degenerate or missing triangle. */
    internal fun outwardNormal(mesh: Mesh, triangle: Int): DoubleArray? {
        val stride = Mesh.FLOATS_PER_VERTEX
        val v = mesh.vertices
        if (triangle < 0 || triangle * 3 + 2 >= mesh.indices.size) return null
        val a = mesh.indices[triangle * 3] * stride
        val b = mesh.indices[triangle * 3 + 1] * stride
        val c = mesh.indices[triangle * 3 + 2] * stride
        val e1x = (v[b] - v[a]).toDouble()
        val e1y = (v[b + 1] - v[a + 1]).toDouble()
        val e1z = (v[b + 2] - v[a + 2]).toDouble()
        val e2x = (v[c] - v[a]).toDouble()
        val e2y = (v[c + 1] - v[a + 1]).toDouble()
        val e2z = (v[c + 2] - v[a + 2]).toDouble()
        var nx = e1y * e2z - e1z * e2y
        var ny = e1z * e2x - e1x * e2z
        var nz = e1x * e2y - e1y * e2x
        if (nx == 0.0 && ny == 0.0 && nz == 0.0) return null
        val o = Mesh.NORMAL_OFFSET
        val sx = (v[a + o] + v[b + o] + v[c + o]).toDouble()
        val sy = (v[a + o + 1] + v[b + o + 1] + v[c + o + 1]).toDouble()
        val sz = (v[a + o + 2] + v[b + o + 2] + v[c + o + 2]).toDouble()
        if (nx * sx + ny * sy + nz * sz < 0.0) {
            nx = -nx; ny = -ny; nz = -nz
        }
        return doubleArrayOf(nx, ny, nz)
    }

    /** Convenience: screen point straight to a hit, for the paint path. */
    fun pick(
        mesh: Mesh,
        screenX: Float,
        screenY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        view: FloatArray,
        projection: FloatArray,
    ): Hit? {
        val ray = unproject(screenX, screenY, viewportWidth, viewportHeight, view, projection) ?: return null
        return raycast(mesh, ray)
    }

    // ── Matrix helpers (column-major, matching OpenGL) ───────────────────────────────────────

    /** `a * b`, both column-major 4x4. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
        return out
    }

    /** Full 4x4 inverse via cofactors. Null when the matrix is singular. */
    fun invert(m: FloatArray): FloatArray? {
        val inv = FloatArray(16)

        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] +
            m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] -
            m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] +
            m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] -
            m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] -
            m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] +
            m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] -
            m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] +
            m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] +
            m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] -
            m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] +
            m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] -
            m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] -
            m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] +
            m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] -
            m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] +
            m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]

        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (abs(det) < 1e-12f) return null
        val invDet = 1f / det
        for (i in 0..15) inv[i] *= invDet
        return inv
    }

    /** Transforms a point by [m] and applies the perspective divide. Null if w collapses. */
    private fun transformPerspective(m: FloatArray, x: Float, y: Float, z: Float): FloatArray? {
        val ox = m[0] * x + m[4] * y + m[8] * z + m[12]
        val oy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val oz = m[2] * x + m[6] * y + m[10] * z + m[14]
        val ow = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (abs(ow) < 1e-9f) return null
        return floatArrayOf(ox / ow, oy / ow, oz / ow)
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun normalize(v: FloatArray): FloatArray? {
        val len = sqrt(dot(v, v))
        if (len < 1e-9f) return null
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    /** sin(angle) below which a ray counts as parallel to a triangle's plane (about 6e-6 degrees). */
    private const val PARALLEL_SINE = 1e-7

    /** sin(angle between a triangle's edges) below which it is a sliver with no usable plane. */
    private const val DEGENERATE_SINE = 1e-9

    /**
     * Barycentric slack, as a fraction of the triangle, so a ray through a shared edge or vertex hits
     * one of the neighbours instead of slipping through the crack between them.
     */
    private const val BARY_SLACK = 1e-7
}
