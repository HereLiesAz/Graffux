package com.hereliesaz.graffitixr.common.mesh

import kotlin.math.abs

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
     * Both faces are tested, not just front-facing ones: a model with inconsistent winding (common
     * in scanned meshes) would otherwise be unpaintable in patches, which reads as the brush
     * randomly not working.
     */
    fun raycast(mesh: Mesh, ray: Ray): Hit? {
        val stride = Mesh.FLOATS_PER_VERTEX
        var best: Hit? = null

        var i = 0
        while (i < mesh.indices.size) {
            val a = mesh.indices[i] * stride
            val b = mesh.indices[i + 1] * stride
            val c = mesh.indices[i + 2] * stride

            val e1 = floatArrayOf(
                mesh.vertices[b] - mesh.vertices[a],
                mesh.vertices[b + 1] - mesh.vertices[a + 1],
                mesh.vertices[b + 2] - mesh.vertices[a + 2],
            )
            val e2 = floatArrayOf(
                mesh.vertices[c] - mesh.vertices[a],
                mesh.vertices[c + 1] - mesh.vertices[a + 1],
                mesh.vertices[c + 2] - mesh.vertices[a + 2],
            )

            val h = cross(ray.direction, e2)
            val det = dot(e1, h)
            // Near-zero determinant means the ray is parallel to the triangle's plane.
            if (abs(det) < EPSILON) { i += 3; continue }

            val invDet = 1f / det
            val s = floatArrayOf(
                ray.origin[0] - mesh.vertices[a],
                ray.origin[1] - mesh.vertices[a + 1],
                ray.origin[2] - mesh.vertices[a + 2],
            )
            val bu = invDet * dot(s, h)
            if (bu < 0f || bu > 1f) { i += 3; continue }

            val q = cross(s, e1)
            val bv = invDet * dot(ray.direction, q)
            if (bv < 0f || bu + bv > 1f) { i += 3; continue }

            val t = invDet * dot(e2, q)
            // Behind the eye, or the near-zero self-hit case.
            if (t <= EPSILON) { i += 3; continue }

            if (best == null || t < best.distance) {
                // Barycentric weights map straight onto the corner UVs: the hit is
                // (1-u-v)·A + u·B + v·C in every attribute, texture coordinates included.
                val w = 1f - bu - bv
                val uvU = w * mesh.vertices[a + Mesh.UV_OFFSET] +
                    bu * mesh.vertices[b + Mesh.UV_OFFSET] +
                    bv * mesh.vertices[c + Mesh.UV_OFFSET]
                val uvV = w * mesh.vertices[a + Mesh.UV_OFFSET + 1] +
                    bu * mesh.vertices[b + Mesh.UV_OFFSET + 1] +
                    bv * mesh.vertices[c + Mesh.UV_OFFSET + 1]
                best = Hit(triangleIndex = i / 3, distance = t, u = uvU, v = uvV)
            }
            i += 3
        }
        return best
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

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun normalize(v: FloatArray): FloatArray? {
        val len = kotlin.math.sqrt(dot(v, v))
        if (len < 1e-9f) return null
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    private const val EPSILON = 1e-7f
}
