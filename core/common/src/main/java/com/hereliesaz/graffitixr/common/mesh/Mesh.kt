package com.hereliesaz.graffitixr.common.mesh

/**
 * A triangle mesh in the layout OpenGL wants: one interleaved vertex buffer plus an index buffer.
 *
 * [vertices] is `[px,py,pz, nx,ny,nz, u,v]` per vertex — [FLOATS_PER_VERTEX] floats, so a single
 * `glVertexAttribPointer` stride covers all three attributes with no separate buffers to keep in
 * step. [indices] holds triangle corners, three per face.
 *
 * Pure data: no Android, no GL handles. The renderer uploads it; this module can be unit-tested.
 */
data class Mesh(
    val vertices: FloatArray,
    val indices: IntArray,
) {
    val vertexCount: Int get() = vertices.size / FLOATS_PER_VERTEX
    val triangleCount: Int get() = indices.size / 3

    /** The axis-aligned bounds as `[minX,minY,minZ, maxX,maxY,maxZ]`, or null for an empty mesh. */
    fun bounds(): FloatArray? {
        if (vertexCount == 0) return null
        val b = floatArrayOf(
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
            -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
        )
        for (i in 0 until vertexCount) {
            val o = i * FLOATS_PER_VERTEX
            for (axis in 0..2) {
                val v = vertices[o + axis]
                if (v < b[axis]) b[axis] = v
                if (v > b[axis + 3]) b[axis + 3] = v
            }
        }
        return b
    }

    /** Centre of the bounding box, or the origin for an empty mesh. */
    fun center(): FloatArray {
        val b = bounds() ?: return floatArrayOf(0f, 0f, 0f)
        return floatArrayOf((b[0] + b[3]) / 2f, (b[1] + b[4]) / 2f, (b[2] + b[5]) / 2f)
    }

    /**
     * Half the bounding box's longest edge — the radius the camera frames the model against, so a
     * model of any authored scale opens filling roughly the same amount of screen.
     */
    fun radius(): Float {
        val b = bounds() ?: return 1f
        val dx = b[3] - b[0]
        val dy = b[4] - b[1]
        val dz = b[5] - b[2]
        return (maxOf(dx, dy, dz) / 2f).coerceAtLeast(1e-4f)
    }

    // Array fields need these written out; the generated ones compare references.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Mesh) return false
        return vertices.contentEquals(other.vertices) && indices.contentEquals(other.indices)
    }

    override fun hashCode(): Int = 31 * vertices.contentHashCode() + indices.contentHashCode()

    companion object {
        const val FLOATS_PER_VERTEX = 8
        const val POSITION_OFFSET = 0
        const val NORMAL_OFFSET = 3
        const val UV_OFFSET = 6
    }
}
