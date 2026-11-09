package com.hereliesaz.graffitixr.common.mesh

/**
 * A mesh's UV islands: the maximal sets of triangles whose texture coordinates are continuous, i.e.
 * that join each other through corners sharing both a position and a UV.
 *
 * Painting needs this because a UV map cuts the surface into islands laid out side by side in the
 * texture. Two points that are neighbours on the model can be far apart in the texture, and two
 * points that are close in the texture can be unrelated places on the model; only inside one island
 * does a straight line in UV space follow the surface. So a stroke may be joined from one touch to
 * the next only within an island ([sameIsland]), and a dab is clipped to its island so it cannot
 * bleed onto the neighbouring island's texels ([trianglesNear]).
 *
 * Corners are matched by position *and* UV rather than by index, so a triangle-soup mesh (every
 * triangle with its own vertices, as scans and some exporters write them) still forms islands
 * wherever it is geometrically continuous. Normals are ignored: a hard edge is not a UV cut.
 *
 * Pure and immutable; build once per mesh.
 */
class UvIslands private constructor(
    private val islandOfTriangle: IntArray,
    private val trianglesOfIsland: Array<IntArray>,
    private val uvBounds: Array<UvBox>,
    private val mesh: Mesh,
) {
    /** How many islands the mesh has. */
    val count: Int get() = trianglesOfIsland.size

    /** The island triangle [triangle] belongs to, or -1 for an index out of range. */
    fun islandOf(triangle: Int): Int = islandOfTriangle.getOrElse(triangle) { -1 }

    /** Whether triangles [a] and [b] are in the same island. Out-of-range triangles are in none. */
    fun sameIsland(a: Int, b: Int): Boolean {
        val ia = islandOf(a)
        return ia >= 0 && ia == islandOf(b)
    }

    /**
     * Whether every UV of [island] lies in the unit square (with a hair of slack). An island that
     * tiles the texture (UVs outside it) wraps, which a clip in texture pixels cannot express, so
     * the caller leaves such an island unclipped.
     */
    fun inUnitSquare(island: Int): Boolean {
        val b = uvBounds.getOrNull(island) ?: return false
        return b.minU >= -UNIT_SLACK && b.minV >= -UNIT_SLACK && b.maxU <= 1f + UNIT_SLACK && b.maxV <= 1f + UNIT_SLACK
    }

    /**
     * The UV corners (`u0,v0, u1,v1, u2,v2` per triangle) of [island]'s triangles whose UV bounding
     * box overlaps [area]. Only the triangles a dab could reach, so clipping a stroke to its island
     * costs the size of the brush, not of the island.
     */
    fun trianglesNear(island: Int, area: UvBox): FloatArray {
        val tris = trianglesOfIsland.getOrNull(island) ?: return FloatArray(0)
        val out = ArrayList<Float>()
        for (t in tris) {
            val box = UvBox.of(mesh, t)
            if (box.overlaps(area)) {
                for (c in 0 until CORNERS) {
                    val o = uvOffset(mesh, t, c)
                    out += mesh.vertices[o]
                    out += mesh.vertices[o + 1]
                }
            }
        }
        return out.toFloatArray()
    }

    /** An axis-aligned rectangle in UV space. */
    class UvBox(val minU: Float, val minV: Float, val maxU: Float, val maxV: Float) {
        /** Whether this and [other] share any area (touching counts). */
        fun overlaps(other: UvBox): Boolean =
            maxU >= other.minU && minU <= other.maxU && maxV >= other.minV && minV <= other.maxV

        companion object {
            /** The bounds of triangle [t]'s UVs. */
            fun of(mesh: Mesh, t: Int): UvBox {
                var minU = Float.MAX_VALUE
                var minV = Float.MAX_VALUE
                var maxU = -Float.MAX_VALUE
                var maxV = -Float.MAX_VALUE
                for (c in 0 until CORNERS) {
                    val o = uvOffset(mesh, t, c)
                    minU = minOf(minU, mesh.vertices[o])
                    maxU = maxOf(maxU, mesh.vertices[o])
                    minV = minOf(minV, mesh.vertices[o + 1])
                    maxV = maxOf(maxV, mesh.vertices[o + 1])
                }
                return UvBox(minU, minV, maxU, maxV)
            }
        }
    }

    private data class Corner(val x: Int, val y: Int, val z: Int, val u: Int, val v: Int)

    companion object {
        private const val UNIT_SLACK = 1e-4f
        private const val CORNERS = 3

        /** The float offset of the UV of corner [c] of triangle [t]. */
        private fun uvOffset(mesh: Mesh, t: Int, c: Int): Int =
            mesh.indices[t * CORNERS + c] * Mesh.FLOATS_PER_VERTEX + Mesh.UV_OFFSET

        private fun cornerKey(mesh: Mesh, t: Int, c: Int): Corner {
            val v = mesh.vertices
            val o = mesh.indices[t * CORNERS + c] * Mesh.FLOATS_PER_VERTEX
            return Corner(
                v[o].toBits(), v[o + 1].toBits(), v[o + 2].toBits(),
                v[o + Mesh.UV_OFFSET].toBits(), v[o + Mesh.UV_OFFSET + 1].toBits(),
            )
        }

        /** The islands of [mesh]. */
        fun of(mesh: Mesh): UvIslands {
            val triangles = mesh.indices.size / CORNERS
            val parent = IntArray(triangles) { it }
            fun find(start: Int): Int {
                var x = start
                while (parent[x] != x) {
                    parent[x] = parent[parent[x]]
                    x = parent[x]
                }
                return x
            }
            val seen = HashMap<Corner, Int>()
            for (t in 0 until triangles) {
                for (c in 0 until CORNERS) {
                    val other = seen.putIfAbsent(cornerKey(mesh, t, c), t)
                    if (other != null) parent[find(t)] = find(other)
                }
            }
            val ids = HashMap<Int, Int>()
            val islandOf = IntArray(triangles) { t -> ids.getOrPut(find(t)) { ids.size } }
            val buckets = Array(ids.size) { ArrayList<Int>() }
            for (t in 0 until triangles) buckets[islandOf[t]] += t
            val bounds = Array(ids.size) { island ->
                val boxes = buckets[island].map { UvBox.of(mesh, it) }
                UvBox(
                    boxes.minOf { it.minU }, boxes.minOf { it.minV },
                    boxes.maxOf { it.maxU }, boxes.maxOf { it.maxV },
                )
            }
            return UvIslands(islandOf, Array(ids.size) { buckets[it].toIntArray() }, bounds, mesh)
        }
    }
}
